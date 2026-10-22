package com.aicode.directtcp

import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aicode.directtcp.net.CaptureBus
import com.aicode.directtcp.net.CaptureEvent
import com.aicode.directtcp.net.ScanResult
import com.aicode.directtcp.net.Sender
import com.aicode.directtcp.net.SessionScanner
import com.aicode.directtcp.net.TcpSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private companion object {
        const val KEY_HISTORY = "history"
    }

    private lateinit var tvStatus: TextView
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnCapture: Button
    private lateinit var btnHistory: Button
    private lateinit var btnScan: Button
    private lateinit var spinnerTemplate: Spinner
    private lateinit var btnDisconnect: Button
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var scrollLog: ScrollView
    private lateinit var tvLog: TextView

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "directtcp-work") }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val prefs by lazy { getSharedPreferences("directtcp", MODE_PRIVATE) }

    private var pendingCapturePackage: String? = null

    private val sender = Sender(object : Sender.Callback {
        override fun onConnected(host: String, port: Int, localPort: Int) {
            post {
                status("已连接 $host:$port")
                log("已连接 $host:$port（本机端口 $localPort）")
            }
        }

        override fun onSent(bytes: Int) {
            post { log("已发送 $bytes 字节") }
        }

        override fun onReply(chunk: String) {
            post { log("← ${chunk.replace("\n", "\\n").replace("\r", "\\r").take(400)}") }
        }

        override fun onClosed(reason: String) {
            post { log("连接结束：$reason") }
        }

        override fun onError(message: String) {
            post { log("! $message") }
        }
    })

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pkg = pendingCapturePackage
        pendingCapturePackage = null
        if (result.resultCode == RESULT_OK) {
            status("已授权 VPN，开始抓取")
            CaptureService.start(this, pkg)
        } else {
            status("未授权 VPN，抓取取消")
            log("没有 VPN 授权就无法读取目标 App 的连接目的地址")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        btnConnect = findViewById(R.id.btnConnect)
        btnCapture = findViewById(R.id.btnCapture)
        btnHistory = findViewById(R.id.btnHistory)
        btnScan = findViewById(R.id.btnScan)
        spinnerTemplate = findViewById(R.id.spinnerTemplate)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        scrollLog = findViewById(R.id.scrollLog)
        tvLog = findViewById(R.id.tvLog)

        spinnerTemplate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Template.entries.map { it.label },
        )

        btnConnect.setOnClickListener { connect() }
        btnCapture.setOnClickListener { startCapture() }
        btnHistory.setOnClickListener { showHistory() }
        btnDisconnect.setOnClickListener {
            sender.close()
            log("已断开")
        }
        btnSend.setOnClickListener { send() }

        // /proc/net/tcp 只有 Android 9 及以下对普通应用开放
        btnScan.visibility = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) View.VISIBLE else View.GONE
        btnScan.setOnClickListener { scanSessions() }

        val recent = loadHistory().firstOrNull()
        if (recent != null) {
            val idx = recent.lastIndexOf(':')
            if (idx > 0) {
                etHost.setText(recent.substring(0, idx))
                etPort.setText(recent.substring(idx + 1))
            }
            status("上次用的是 $recent")
        } else {
            status("先点「抓取对端」，或直接填 IP:端口")
        }
    }

    override fun onResume() {
        super.onResume()
        CaptureBus.register(::onCaptureEvent)
    }

    override fun onPause() {
        super.onPause()
        CaptureBus.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        sender.close()
        executor.shutdownNow()
    }

    // ---------- 抓取 ----------

    private fun startCapture() {
        pickApp { pkg ->
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                pendingCapturePackage = pkg
                vpnPermission.launch(prepare)
            } else {
                status("开始抓取：$pkg")
                CaptureService.start(this, pkg)
            }
        }
    }

    private fun pickApp(onPicked: (String) -> Unit) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

        if (apps.isEmpty()) {
            toast("没有找到可启动的应用")
            return
        }
        val labels = apps.map {
            "${packageManager.getApplicationLabel(it)}（${it.packageName}）"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要抓取的老应用")
            .setItems(labels) { _, which -> onPicked(apps[which].packageName) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onCaptureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Started -> {
                status("抓取中… 请让目标 App 重新连接一次（切网络 / 重开 / 下拉刷新）")
                log("开始抓取 ${event.targetPackage ?: "全部应用"}")
            }

            is CaptureEvent.Captured -> {
                etHost.setText(event.host)
                etPort.setText(event.port.toString())
                rememberEndpoint(event.host, event.port)
                status("已抓到对端 ${event.host}:${event.port}")
                log("抓到对端 ${event.host}:${event.port}（本机 ${event.fromIp}:${event.fromPort}）")
                connect(auto = true)
            }

            is CaptureEvent.Stopped -> {
                status(event.reason)
                log(event.reason)
            }

            is CaptureEvent.Error -> {
                status("抓取失败")
                log("! ${event.message}")
            }
        }
    }

    // ---------- 发送 ----------

    private fun currentTarget(): Pair<String, Int>? {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            log("目标地址无效：$host:${etPort.text}")
            return null
        }
        return host to port
    }

    private fun connect(auto: Boolean = false) {
        val target = currentTarget() ?: return
        btnConnect.isEnabled = false
        log("连接 ${target.first}:${target.second} …")
        executor.execute {
            val ok = sender.connect(target.first, target.second)
            post { btnConnect.isEnabled = true }
            if (ok) {
                rememberEndpoint(target.first, target.second)
            } else if (auto) {
                post { status("自动连接失败，可改地址后手动点「连接」") }
            }
        }
    }

    private fun send() {
        val text = etMessage.text.toString()
        if (text.isEmpty()) {
            toast("先输入要发送的内容")
            return
        }
        val template = Template.entries[spinnerTemplate.selectedItemPosition]
        val payload = template.build(text)
        if (payload == null || payload.isEmpty()) {
            log("内容无法编码（HEX 模式需要偶数字节，如 48 65 6c）")
            return
        }
        val target = currentTarget() ?: return

        btnSend.isEnabled = false
        executor.execute {
            try {
                if (!sender.isConnected) {
                    post { log("未连接，自动连接 ${target.first}:${target.second}") }
                    if (!sender.connect(target.first, target.second)) {
                        post { toast("连接失败") }
                        return@execute
                    }
                }
                sender.sendRaw(payload)
            } finally {
                post { btnSend.isEnabled = true }
            }
        }
    }

    // ---------- 会列表（仅 Android 9 及以下可用） ----------

    private fun scanSessions() {
        status("扫描 /proc/net/tcp …")
        executor.execute {
            val result = try {
                SessionScanner.scan()
            } catch (e: Exception) {
                ScanResult(emptyList(), listOf("扫描异常：${e.message}"))
            }
            post { showSessions(result) }
        }
    }

    private fun showSessions(result: ScanResult) {
        val usable = result.sessions.filter { it.isEstablished && it.hasRemote }
        if (usable.isEmpty()) {
            status("没有可用会话${if (result.errors.isEmpty()) "" else "：" + result.errors.joinToString("; ")}")
            return
        }
        val labels = usable.map { "${ownerOf(it)}  ${it.localPort} → ${it.endpoint}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要借用的会话")
            .setItems(labels) { _, which ->
                val session = usable[which]
                etHost.setText(session.remoteIp)
                etPort.setText(session.remotePort.toString())
                rememberEndpoint(session.remoteIp, session.remotePort)
                status("已填入 ${session.endpoint}")
            }
            .setNegativeButton("取消", null)
            .show()
        status("共 ${result.sessions.size} 条会话，可用 ${usable.size} 条")
    }

    private fun ownerOf(session: TcpSession): String = try {
        packageManager.getPackagesForUid(session.uid)?.firstOrNull() ?: "uid ${session.uid}"
    } catch (e: Exception) {
        "uid ${session.uid}"
    }

    // ---------- 历史 ----------

    private fun loadHistory(): List<String> =
        prefs.getString(KEY_HISTORY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun rememberEndpoint(host: String, port: Int) {
        val entry = "$host:$port"
        val list = loadHistory().toMutableList()
        list.remove(entry)
        list.add(0, entry)
        prefs.edit().putString(KEY_HISTORY, list.take(20).joinToString("\n")).apply()
    }

    private fun showHistory() {
        val list = loadHistory()
        if (list.isEmpty()) {
            toast("还没有历史地址")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("历史地址")
            .setItems(list.toTypedArray()) { _, which ->
                val entry = list[which]
                val idx = entry.lastIndexOf(':')
                if (idx > 0) {
                    etHost.setText(entry.substring(0, idx))
                    etPort.setText(entry.substring(idx + 1))
                    status("已填入 $entry")
                }
            }
            .setNeutralButton("清空") { _, _ -> prefs.edit().remove(KEY_HISTORY).apply() }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 工具 ----------

    private fun post(block: () -> Unit) = runOnUiThread(block)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun status(text: String) {
        tvStatus.text = text
    }

    private fun log(message: String) {
        val line = "${timeFmt.format(Date())}  $message"
        val existing = tvLog.text?.toString().orEmpty()
        val merged = if (existing.isEmpty()) line else "$existing\n$line"
        val lines = merged.split('\n')
        tvLog.text = if (lines.size > 400) lines.takeLast(400).joinToString("\n") else merged
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    enum class Template(val label: String, val build: (String) -> ByteArray?) {
        TEXT_LF("文本 + \\n", { (it + "\n").toByteArray(Charsets.UTF_8) }),
        TEXT_CRLF("文本 + \\r\\n", { (it + "\r\n").toByteArray(Charsets.UTF_8) }),
        TEXT_RAW("文本无行尾", { it.toByteArray(Charsets.UTF_8) }),
        JSON_LF("JSON 行", {
            val escaped = it.replace("\\", "\\\\").replace("\"", "\\\"")
            "{\"text\":\"$escaped\"}\n".toByteArray(Charsets.UTF_8)
        }),
        HEX("HEX 字节", { hexToBytes(it) });

        companion object {
            fun hexToBytes(input: String): ByteArray? {
                val clean = input.replace(Regex("[\\s,:]"), "")
                if (clean.isEmpty() || clean.length % 2 != 0) return null
                return try {
                    ByteArray(clean.length / 2) {
                        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
