package com.aicode.directtcp.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 端到端复现：抓取阶段从 tun 里读到的 SYN → 解析出对端地址 →
 * 用同一地址建立新连接发消息 → 对端收到。
 *
 * 没有真机也能验证「解析 → 发送」这一段的数据是通的。
 */
class CaptureFlowTest {

    private fun ipv4Packet(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): ByteArray {
        val body = ByteArray(20)
        body[0] = (srcPort shr 8).toByte()
        body[1] = srcPort.toByte()
        body[2] = (dstPort shr 8).toByte()
        body[3] = dstPort.toByte()
        body[12] = 0x50
        body[13] = 0x02 // SYN

        val total = 20 + body.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[8] = 64
        p[9] = 6
        System.arraycopy(InetAddress.getByName(srcIp).address, 0, p, 12, 4)
        System.arraycopy(InetAddress.getByName(dstIp).address, 0, p, 16, 4)
        System.arraycopy(body, 0, p, 20, body.size)
        return p
    }

    @Test
    fun `从 SYN 解析出对端地址并直连发送`() {
        // 充当「另一台手机」的服务端
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<String>()
        val ready = CountDownLatch(1)
        Thread {
            try {
                ready.countDown()
                val socket = server.accept()
                received.put(DataInputStream(socket.getInputStream()).readUTF())
                socket.close()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        ready.await(2, TimeUnit.SECONDS)

        // 1) 抓取阶段：模拟 tun 读到老应用发出的 SYN
        val syn = ipv4Packet("10.66.0.2", 51234, "127.0.0.1", server.localPort)
        val captured = PacketParser.parseTcpSyn(syn)
        requireNotNull(captured)
        assertEquals("127.0.0.1", captured.dstIp)
        assertEquals(server.localPort, captured.dstPort)

        // 2) 发送阶段：用抓到的地址另建连接（App 的行为）
        val sender = Sender(object : Sender.Callback {
            override fun onConnected(host: String, port: Int, localPort: Int) = Unit
            override fun onSent(bytes: Int) = Unit
            override fun onReply(chunk: String) = Unit
            override fun onClosed(reason: String) = Unit
            override fun onError(message: String) = Unit
        })
        try {
            val ok = sender.connect(captured.dstIp, captured.dstPort)
            assertEquals(true, ok)

            // DataOutputStream.writeUTF 需要长度前缀，这里手工构造
            val text = "hello from captured endpoint"
            val payload = byteArrayOf(
                (text.toByteArray().size shr 8).toByte(),
                text.toByteArray().size.toByte(),
            ) + text.toByteArray()
            assertEquals(payload.size, sender.sendRaw(payload))

            assertEquals(text, received.poll(3, TimeUnit.SECONDS))
        } finally {
            sender.close()
            server.close()
        }
    }
}
