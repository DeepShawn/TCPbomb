package com.aicode.directtcp.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SessionScannerTest {

    private val sample = """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:8A2B 5DB8D822:01BB 01 00000000:00000000 00:00000000 00000000  10123        0 13371 1 0000000000000000 100 0 0 10 0
           1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10123        0 13372 1 0000000000000000 100 0 0 10 0
           2: 0100007F:9C40 0100007F:1F90 06 00000000:00000000 03:00000000 00000000     0        0 13373 1 0000000000000000 100 0 0 10 0
    """.trimIndent()

    @Test
    fun `解析 IPv4 会话与列位`() {
        val sessions = SessionScanner.parse(sample, ipv6 = false)

        assertEquals(3, sessions.size)

        val chat = sessions[0]
        assertEquals("127.0.0.1", chat.localIp)
        assertEquals(0x8A2B, chat.localPort)
        assertEquals("34.216.184.93", chat.remoteIp)
        assertEquals(443, chat.remotePort)
        assertEquals(TcpState.ESTABLISHED, chat.state)
        assertEquals(10123, chat.uid)
        assertEquals(13371L, chat.inode)
        assertTrue(chat.isEstablished)
        assertTrue(chat.hasRemote)
        assertEquals("34.216.184.93:443", chat.endpoint)
    }

    @Test
    fun `LISTEN 没有对端`() {
        val listen = SessionScanner.parse(sample, ipv6 = false)[1]
        assertEquals(TcpState.LISTEN, listen.state)
        assertEquals(0, listen.remotePort)
        assertEquals("0.0.0.0", listen.remoteIp)
        assertFalse(listen.hasRemote)
        assertFalse(listen.isEstablished)
    }

    @Test
    fun `TIME_WAIT 被识别`() {
        val tw = SessionScanner.parse(sample, ipv6 = false)[2]
        assertEquals(TcpState.TIME_WAIT, tw.state)
    }

    @Test
    fun `小端 IPv4 解码`() {
        assertEquals("127.0.0.1", SessionScanner.decodeIpv4("0100007F"))
        assertEquals("0.0.0.0", SessionScanner.decodeIpv4("00000000"))
        assertEquals("192.168.1.10", SessionScanner.decodeIpv4("0A01A8C0"))
        assertEquals("255.255.255.255", SessionScanner.decodeIpv4("FFFFFFFF"))
        assertNull(SessionScanner.decodeIpv4("0100"))
    }

    @Test
    fun `IPv6 与 v4-mapped 折叠`() {
        // ::ffff:127.0.0.1 —— 每组 32 位小端
        assertEquals("127.0.0.1", SessionScanner.decodeIpv6("0000000000000000FFFF00000100007F"))
        // 2001:db8::1
        val decoded = SessionScanner.decodeIpv6("B80D0120000000000000000001000000")
        assertEquals(InetAddress.getByName("2001:db8::1").hostAddress, decoded)
        assertNull(SessionScanner.decodeIpv6("B80D0120"))
    }

    @Test
    fun `端口按十六进制解析`() {
        assertEquals("127.0.0.1" to 8080, SessionScanner.decodeEndpoint("0100007F:1F90", false))
        assertEquals("10.0.0.1" to 65535, SessionScanner.decodeEndpoint("0100000A:FFFF", false))
        assertNull(SessionScanner.decodeEndpoint("0100007F", false))
        assertNull(SessionScanner.decodeEndpoint("0100007F:ZZZZ", false))
    }

    @Test
    fun `表头与脏行被跳过`() {
        val dirty = """
              sl  local_address rem_address   st
               0: 0100007F:1F90 0100007F:8A2B 01
               1: garbage
               bad line here
               2: 0100007F:8A2B 0100007F:1F90 01 00:00 00:00 00 1000 0 555
        """.trimIndent()
        val sessions = SessionScanner.parse(dirty, ipv6 = false)
        // 字段不足、纯垃圾行都被丢弃，只保留结构完整的最后一行
        assertEquals(1, sessions.size)
        assertEquals(555L, sessions[0].inode)
    }
}
