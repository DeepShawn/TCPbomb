package com.aicode.directtcp.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class PacketParserTest {

    private fun ipv4(proto: Int, src: String, dst: String, body: ByteArray): ByteArray {
        val total = 20 + body.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(InetAddress.getByName(src).address, 0, p, 12, 4)
        System.arraycopy(InetAddress.getByName(dst).address, 0, p, 16, 4)
        System.arraycopy(body, 0, p, 20, body.size)
        return p
    }

    private fun tcp(srcPort: Int, dstPort: Int, flags: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val t = ByteArray(20 + payload.size)
        t[0] = (srcPort shr 8).toByte()
        t[1] = srcPort.toByte()
        t[2] = (dstPort shr 8).toByte()
        t[3] = dstPort.toByte()
        t[12] = 0x50
        t[13] = flags.toByte()
        System.arraycopy(payload, 0, t, 20, payload.size)
        return t
    }

    private fun udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val len = 8 + payload.size
        val u = ByteArray(len)
        u[0] = (srcPort shr 8).toByte()
        u[1] = srcPort.toByte()
        u[2] = (dstPort shr 8).toByte()
        u[3] = dstPort.toByte()
        u[4] = (len shr 8).toByte()
        u[5] = len.toByte()
        System.arraycopy(payload, 0, u, 8, payload.size)
        return u
    }

    @Test
    fun `识别 IPv4 SYN`() {
        val packet = ipv4(6, "192.168.1.10", "93.184.216.34", tcp(51234, 9000, 0x02))
        val syn = PacketParser.parseTcpSyn(packet)
        assertNotNull(syn)
        assertEquals("192.168.1.10", syn!!.srcIp)
        assertEquals(51234, syn.srcPort)
        assertEquals("93.184.216.34", syn.dstIp)
        assertEquals(9000, syn.dstPort)
        assertEquals(4, syn.version)
    }

    @Test
    fun `SYN-ACK 和普通 ACK 不算`() {
        val synAck = ipv4(6, "1.1.1.1", "192.168.1.10", tcp(443, 51234, 0x12))
        assertNull(PacketParser.parseTcpSyn(synAck))

        val ack = ipv4(6, "1.1.1.1", "192.168.1.10", tcp(443, 51234, 0x10))
        assertNull(PacketParser.parseTcpSyn(ack))
    }

    @Test
    fun `识别 IPv6 SYN`() {
        val body = tcp(40000, 8080, 0x02)
        val p = ByteArray(40 + body.size)
        p[0] = 0x60
        p[6] = 6
        p[7] = 64
        System.arraycopy(InetAddress.getByName("2001:db8::2").address, 0, p, 8, 16)
        System.arraycopy(InetAddress.getByName("2001:db8::1").address, 0, p, 24, 16)
        System.arraycopy(body, 0, p, 40, body.size)

        val syn = PacketParser.parseTcpSyn(p)
        assertNotNull(syn)
        assertEquals("2001:db8:0:0:0:0:0:2", syn!!.srcIp)
        assertEquals("2001:db8:0:0:0:0:0:1", syn.dstIp)
        assertEquals(8080, syn.dstPort)
        assertEquals(6, syn.version)
    }

    @Test
    fun `非 TCP 包被忽略`() {
        val icmp = ipv4(1, "1.1.1.1", "2.2.2.2", ByteArray(8))
        assertNull(PacketParser.parseTcpSyn(icmp))
        assertNull(PacketParser.parseDnsQuery(icmp))
    }

    @Test
    fun `解析 DNS 查询`() {
        val dnsPayload = ByteArray(29).also { it[0] = 0x12; it[1] = 0x34 }
        val packet = ipv4(17, "10.66.0.2", "223.5.5.5", udp(40500, 53, dnsPayload))

        val query = PacketParser.parseDnsQuery(packet)
        assertNotNull(query)
        assertEquals("10.66.0.2", query!!.srcIp)
        assertEquals(40500, query.srcPort)
        assertEquals("223.5.5.5", query.dstIp)
        assertEquals(53, query.dstPort)
        assertArrayEquals(dnsPayload, query.payload)

        // 非 53 端口的 UDP 不算
        assertNull(PacketParser.parseDnsQuery(ipv4(17, "10.66.0.2", "1.1.1.1", udp(40500, 5353, dnsPayload))))
    }

    @Test
    fun `构造 DNS 应答时地址端口互换且校验和有效`() {
        val dnsPayload = ByteArray(29)
        val queryPacket = ipv4(17, "10.66.0.2", "223.5.5.5", udp(40500, 53, dnsPayload))
        val query = PacketParser.parseDnsQuery(queryPacket)!!
        val answer = ByteArray(45) { 0x7F }

        val reply = PacketParser.buildDnsReply(query, answer)
        assertNotNull(reply)

        // 源/目的地址互换
        val src = InetAddress.getByAddress(reply!!.copyOfRange(12, 16)).hostAddress
        val dst = InetAddress.getByAddress(reply.copyOfRange(16, 20)).hostAddress
        assertEquals("223.5.5.5", src)
        assertEquals("10.66.0.2", dst)

        // IPv4 头长度与总长
        val total = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        assertEquals(20 + 8 + answer.size, total)
        assertEquals(total, reply.size)

        // 校验和自检：整个头再算一次应为 0
        assertEquals(0, PacketParser.checksum(reply, 0, 20))

        // UDP：源端口 53，目的端口是客户端端口，校验和为 0（IPv4 允许）
        assertEquals(53, ((reply[20].toInt() and 0xFF) shl 8) or (reply[21].toInt() and 0xFF))
        assertEquals(40500, ((reply[22].toInt() and 0xFF) shl 8) or (reply[23].toInt() and 0xFF))
        assertEquals(0, reply[26].toInt())
        assertEquals(0, reply[27].toInt())

        assertArrayEquals(answer, reply.copyOfRange(28, reply.size))
    }

    @Test
    fun `IPv6 的 DNS 不构造应答`() {
        val body = udp(40000, 53, ByteArray(20))
        val p = ByteArray(40 + body.size)
        p[0] = 0x60
        p[6] = 17
        System.arraycopy(InetAddress.getByName("2001:db8::2").address, 0, p, 8, 16)
        System.arraycopy(InetAddress.getByName("2001:db8::1").address, 0, p, 24, 16)
        System.arraycopy(body, 0, p, 40, body.size)

        val query = PacketParser.parseDnsQuery(p)
        assertNotNull(query)
        assertNull(PacketParser.buildDnsReply(query!!, ByteArray(10)))
    }
}
