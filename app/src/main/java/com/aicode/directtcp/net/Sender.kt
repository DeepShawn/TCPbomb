package com.aicode.directtcp.net

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 主动连接对端并发送数据。
 *
 * 说明：这是「新应用自己再建一条 TCP 连接」的实现，不碰老应用那条连接——
 * 没有 root 时跨进程写别人的 socket 是不可能的（fd 属于进程）。
 * 免配对的点在于：地址直接从 /proc/net/tcp 里读出来，不需要扫码/握手。
 */
class Sender(private val callback: Callback) {

    interface Callback {
        fun onConnected(host: String, port: Int, localPort: Int)
        fun onSent(bytes: Int)
        fun onReply(chunk: String)
        fun onClosed(reason: String)
        fun onError(message: String)
    }

    private val lock = Any()
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var readerThread: Thread? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var host: String = ""
        private set

    @Volatile
    var port: Int = 0
        private set

    /** 阻塞式连接，调用方放到工作线程。返回是否成功。 */
    fun connect(host: String, port: Int, timeoutMs: Int = 6000): Boolean {
        close()
        this.host = host
        this.port = port
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            synchronized(lock) {
                socket = sock
                out = sock.getOutputStream()
            }
            isConnected = true
            callback.onConnected(host, port, sock.localPort)
            startReader(sock)
            true
        } catch (e: Exception) {
            try {
                sock.close()
            } catch (_: Exception) {
            }
            isConnected = false
            callback.onError("连接 $host:$port 失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            false
        }
    }

    /** 发送文本，lineEnding 可传 ""、"\n"、"\r\n"。返回写入的字节数，失败返回 -1。 */
    fun send(text: String, lineEnding: String): Int {
        val payload = (text + lineEnding).toByteArray(Charsets.UTF_8)
        return sendRaw(payload)
    }

    fun sendRaw(payload: ByteArray): Int = synchronized(lock) {
        val stream = out
        if (!isConnected || stream == null) {
            callback.onError("还没连接，先点「连接」")
            return -1
        }
        try {
            stream.write(payload)
            stream.flush()
            callback.onSent(payload.size)
            payload.size
        } catch (e: Exception) {
            isConnected = false
            callback.onError("发送失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            -1
        }
    }

    private fun startReader(sock: Socket) {
        readerThread?.interrupt()
        readerThread = Thread({
            try {
                val reader = sock.getInputStream().reader(Charsets.UTF_8)
                val buf = CharArray(1024)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (n > 0) callback.onReply(String(buf, 0, n))
                }
                callback.onClosed("对端关闭了连接")
            } catch (e: Exception) {
                if (isConnected) {
                    callback.onError("读取出错：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                }
            } finally {
                isConnected = false
            }
        }, "tcp-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        isConnected = false
        readerThread?.interrupt()
        readerThread = null
        synchronized(lock) {
            try {
                out?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            out = null
            socket = null
        }
    }
}
