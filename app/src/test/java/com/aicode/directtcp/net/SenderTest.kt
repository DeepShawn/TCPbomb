package com.aicode.directtcp.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SenderTest {

    @Test
    fun `回环收发文本与回执`() {
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<String>()
        val serverThread = Thread {
            try {
                val socket = server.accept()
                val reader = socket.getInputStream().bufferedReader()
                received.put(reader.readLine() ?: "")
                socket.getOutputStream().write("pong\n".toByteArray())
                socket.getOutputStream().flush()
                Thread.sleep(400)
                socket.close()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }

        val connected = CountDownLatch(1)
        val gotReply = CountDownLatch(1)
        val error = AtomicReference<String>()
        val reply = AtomicReference("")

        val sender = Sender(object : Sender.Callback {
            override fun onConnected(host: String, port: Int, localPort: Int) {
                connected.countDown()
            }

            override fun onSent(bytes: Int) = Unit

            override fun onReply(chunk: String) {
                reply.set(reply.get() + chunk)
                if (reply.get().contains("pong")) gotReply.countDown()
            }

            override fun onClosed(reason: String) = Unit

            override fun onError(message: String) {
                error.set(message)
            }
        })

        serverThread.start()
        try {
            assertTrue(sender.connect("127.0.0.1", server.localPort))
            assertTrue("应触发 onConnected", connected.await(2, TimeUnit.SECONDS))

            val written = sender.send("hello", "\n")
            assertEquals(6, written)
            assertEquals("hello", received.poll(3, TimeUnit.SECONDS))
            assertTrue("应收到回执", gotReply.await(3, TimeUnit.SECONDS))
            assertEquals("pong\n", reply.get())
        } finally {
            sender.close()
            server.close()
        }
    }

    @Test
    fun `连接关闭端口失败并回调错误`() {
        val port = ServerSocket(0).use { it.localPort }
        val error = AtomicReference<String?>()
        val sender = Sender(object : Sender.Callback {
            override fun onConnected(host: String, port: Int, localPort: Int) = Unit
            override fun onSent(bytes: Int) = Unit
            override fun onReply(chunk: String) = Unit
            override fun onClosed(reason: String) = Unit
            override fun onError(message: String) {
                error.set(message)
            }
        })

        val ok = sender.connect("127.0.0.1", port, timeoutMs = 1500)
        assertFalse(ok)
        assertFalse(sender.isConnected)
        assertTrue("应回调错误信息", error.get()?.contains("失败") == true)
        sender.close()
    }

    @Test
    fun `未连接时发送返回负一`() {
        val sender = Sender(object : Sender.Callback {
            override fun onConnected(host: String, port: Int, localPort: Int) = Unit
            override fun onSent(bytes: Int) = Unit
            override fun onReply(chunk: String) = Unit
            override fun onClosed(reason: String) = Unit
            override fun onError(message: String) = Unit
        })
        assertEquals(-1, sender.send("x", "\n"))
        sender.close()
    }
}
