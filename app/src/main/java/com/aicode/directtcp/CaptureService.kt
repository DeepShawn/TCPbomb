package com.aicode.directtcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.aicode.directtcp.net.CaptureBus
import com.aicode.directtcp.net.CaptureEvent
import com.aicode.directtcp.net.PacketParser
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 抓取目标应用下一次 TCP 连接的目的地址。
 *
 * 原理：只把目标应用的流量路由进 tun（addAllowedApplication），读到第一个
 * SYN 就拿到目的 IP:端口，然后立刻关闭 VPN —— 被吞掉的 SYN 会由目标应用重传，
 * 此时流量已回到物理网卡，它的连接照常建立，我们则拿着地址去直连发送。
 *
 * 期间目标应用的 DNS 也会进 tun，这里做一次 UDP 转发（无 root，普通 socket 即可，
 * 因为本应用自身不在 allowed 列表里，自己的流量不会进 tun）。
 */
class CaptureService : VpnService() {

    companion object {
        const val ACTION_START = "com.aicode.directtcp.capture.START"
        const val ACTION_STOP = "com.aicode.directtcp.capture.STOP"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_TIMEOUT = "extra_timeout"
        const val DEFAULT_TIMEOUT = 45_000L

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 0x7C01

        fun start(context: Context, targetPackage: String?, timeoutMs: Long = DEFAULT_TIMEOUT) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE, targetPackage)
                putExtra(EXTRA_TIMEOUT, timeoutMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dnsExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "dns-relay") }
    private val finished = AtomicBoolean(false)

    private var tun: ParcelFileDescriptor? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    private val timeoutTask = Runnable { finish("抓取超时，没有看到新的 TCP 连接") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish("已取消抓取")
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundCompat()
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                val timeout = intent.getLongExtra(EXTRA_TIMEOUT, DEFAULT_TIMEOUT)
                // establish() 可能要几百毫秒，别占主线程
                Thread({ begin(pkg, timeout) }, "vpn-setup").apply { isDaemon = true }.start()
            }

            else -> finish("未知指令")
        }
        return START_NOT_STICKY
    }

    private fun begin(targetPackage: String?, timeoutMs: Long) {
        if (running || finished.get()) return

        val builder = Builder()
            .setSession("直连投递")
            .setMtu(1500)
            .addAddress("10.66.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)

        try {
            if (!targetPackage.isNullOrEmpty()) {
                builder.addAllowedApplication(targetPackage)
            }
            // 本应用自己的流量绝不能进 tun，否则 DNS 转发会打环
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            CaptureBus.emit(CaptureEvent.Error("无法限制抓取范围：${e.message}"))
            finish(null)
            return
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            CaptureBus.emit(CaptureEvent.Error("建立 VPN 失败：${e.message}"))
            finish(null)
            return
        }
        if (pfd == null) {
            CaptureBus.emit(CaptureEvent.Error("系统没有允许 VPN"))
            finish(null)
            return
        }

        tun = pfd
        running = true
        CaptureBus.emit(CaptureEvent.Started(targetPackage))
        mainHandler.postDelayed(timeoutTask, timeoutMs)

        readThread = Thread({ readLoop(pfd) }, "vpn-read").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(65535)
        try {
            while (running) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue

                val syn = PacketParser.parseTcpSyn(buffer, n)
                if (syn != null) {
                    CaptureBus.emit(
                        CaptureEvent.Captured(
                            host = syn.dstIp,
                            port = syn.dstPort,
                            fromIp = syn.srcIp,
                            fromPort = syn.srcPort,
                        )
                    )
                    finish(null)
                    return
                }

                val dns = PacketParser.parseDnsQuery(buffer, n)
                if (dns != null) relayDns(dns, output)
            }
        } catch (e: Exception) {
            if (running) CaptureBus.emit(CaptureEvent.Error("读取 tun 失败：${e.message}"))
        } finally {
            runCatching { input.close() }
        }
    }

    /** 把 DNS 查询用普通 UDP socket 转出去，再把应答写回 tun */
    private fun relayDns(query: PacketParser.DnsQuery, output: FileOutputStream) {
        if (query.version != 4) return
        dnsExecutor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = 2500
                val servers = listOf("223.5.5.5", "1.1.1.1", "8.8.8.8")
                var answer: ByteArray? = null
                for (server in servers) {
                    try {
                        val address = InetAddress.getByName(server)
                        socket.send(DatagramPacket(query.payload, query.payload.size, address, 53))
                        val response = DatagramPacket(ByteArray(4096), 4096)
                        socket.receive(response)
                        answer = response.data.copyOf(response.length)
                        break
                    } catch (_: Exception) {
                        // 换下一个 DNS
                    }
                }
                val reply = answer?.let { PacketParser.buildDnsReply(query, it) } ?: return@execute
                synchronized(output) {
                    output.write(reply)
                    output.flush()
                }
            } catch (_: Exception) {
                // DNS 转发失败就静默丢弃，不影响抓取主流程
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "对端抓取", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("正在抓取对端地址")
                .setContentText("等目标 App 发起下一次 TCP 连接…")
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("正在抓取对端地址")
                .setContentText("等目标 App 发起下一次 TCP 连接…")
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun finish(reason: String?) {
        if (!finished.compareAndSet(false, true)) return
        running = false
        mainHandler.removeCallbacks(timeoutTask)
        runCatching { tun?.close() }
        tun = null
        readThread = null
        dnsExecutor.shutdownNow()
        reason?.let { CaptureBus.emit(CaptureEvent.Stopped(it)) }
        runCatching { stopForeground(true) }
        stopSelf()
    }

    override fun onRevoke() {
        finish("系统撤销了 VPN 授权")
        super.onRevoke()
    }

    override fun onDestroy() {
        finish(null)
        super.onDestroy()
    }
}
