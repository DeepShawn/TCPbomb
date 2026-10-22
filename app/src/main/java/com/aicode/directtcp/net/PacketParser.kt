package com.aicode.directtcp.net

import java.net.InetAddress

/**
 * tun 里的原始 IP 包解析：识别 TCP SYN、识别 DNS 查询、构造 DNS 应答。
 *
 * 只做抓取需要的最小集合，不实现完整 TCP 栈。
 */
object PacketParser {

    data class TcpSyn(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val version: Int,
    )

    data class DnsQuery(
        val version: Int,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val payload: ByteArray,
        /** IPv4 头原始 20 字节，用于构造应答时反向交换地址 */
        val ipHeader: ByteArray,
    )

    private data class IpInfo(
        val version: Int,
        val protocol: Int,
        val srcIp: String,
        val dstIp: String,
        val payloadOffset: Int,
    )

    /** 只匹配「首次 SYN」（SYN=1 且 ACK=0），SYN-ACK 和普通 ACK 都不算 */
    fun parseTcpSyn(packet: ByteArray, length: Int = packet.size): TcpSyn? {
        val ip = parseIp(packet, length) ?: return null
        if (ip.protocol != 6) return null
        val off = ip.payloadOffset
        if (off + 20 > length) return null
        val flags = packet[off + 13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        if (!syn || ack) return null
        return TcpSyn(
            srcIp = ip.srcIp,
            srcPort = readPort(packet, off),
            dstIp = ip.dstIp,
            dstPort = readPort(packet, off + 2),
            version = ip.version,
        )
    }

    fun parseDnsQuery(packet: ByteArray, length: Int = packet.size): DnsQuery? {
        val ip = parseIp(packet, length) ?: return null
        if (ip.protocol != 17) return null
        val off = ip.payloadOffset
        if (off + 8 > length) return null
        val dstPort = readPort(packet, off + 2)
        if (dstPort != 53) return null
        val udpLen = ((packet[off + 4].toInt() and 0xFF) shl 8) or (packet[off + 5].toInt() and 0xFF)
        if (udpLen < 8 || off + udpLen > length) return null
        val payload = packet.copyOfRange(off + 8, off + udpLen)
        if (payload.size < 12) return null
        val header = if (ip.version == 4) packet.copyOfRange(0, 20) else ByteArray(0)
        return DnsQuery(
            version = ip.version,
            srcIp = ip.srcIp,
            srcPort = readPort(packet, off),
            dstIp = ip.dstIp,
            dstPort = dstPort,
            payload = payload,
            ipHeader = header,
        )
    }

    /**
     * 把 DNS 应答包装回 tun：交换源/目的地址与端口，重算 IPv4 头校验和。
     * IPv4 的 UDP 校验和填 0 表示「不校验」，合法，省掉伪首部计算。
     * IPv6 不做转发（返回 null），抓取场景下 DNS 一般走 IPv4。
     */
    fun buildDnsReply(query: DnsQuery, answer: ByteArray): ByteArray? {
        if (query.version != 4 || query.ipHeader.size != 20) return null
        val ipLen = 20
        val udpLen = 8 + answer.size
        val total = ipLen + udpLen
        if (total > 65535) return null

        val out = ByteArray(total)
        System.arraycopy(query.ipHeader, 0, out, 0, 20)
        // 源/目的地址互换
        System.arraycopy(query.ipHeader, 16, out, 12, 4)
        System.arraycopy(query.ipHeader, 12, out, 16, 4)
        out[2] = (total shr 8).toByte()
        out[3] = total.toByte()
        out[8] = 64 // TTL
        out[10] = 0
        out[11] = 0
        val ipSum = checksum(out, 0, 20)
        out[10] = (ipSum shr 8).toByte()
        out[11] = ipSum.toByte()

        // UDP 头：源端口=53，目的端口=原查询的源端口
        val o = ipLen
        out[o] = 0
        out[o + 1] = 53
        out[o + 2] = ((query.srcPort shr 8) and 0xFF).toByte()
        out[o + 3] = (query.srcPort and 0xFF).toByte()
        out[o + 4] = (udpLen shr 8).toByte()
        out[o + 5] = udpLen.toByte()
        out[o + 6] = 0
        out[o + 7] = 0
        System.arraycopy(answer, 0, out, ipLen + 8, answer.size)
        return out
    }

    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun parseIp(p: ByteArray, len: Int): IpInfo? {
        if (len < 20) return null
        return when ((p[0].toInt() and 0xFF) shr 4) {
            4 -> {
                val ihl = (p[0].toInt() and 0x0F) * 4
                if (ihl < 20 || len < ihl) return null
                val total = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
                if (total < ihl) return null
                IpInfo(4, p[9].toInt() and 0xFF, ipv4(p, 12), ipv4(p, 16), ihl)
            }

            6 -> {
                if (len < 40) return null
                IpInfo(6, p[6].toInt() and 0xFF, ipv6(p, 8), ipv6(p, 24), 40)
            }

            else -> null
        }
    }

    private fun readPort(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xFF) shl 8) or (p[off + 1].toInt() and 0xFF)

    private fun ipv4(p: ByteArray, off: Int): String =
        "${p[off].toInt() and 0xFF}.${p[off + 1].toInt() and 0xFF}.${p[off + 2].toInt() and 0xFF}.${p[off + 3].toInt() and 0xFF}"

    private fun ipv6(p: ByteArray, off: Int): String =
        InetAddress.getByAddress(p.copyOfRange(off, off + 16)).hostAddress?.substringBefore('%') ?: "?"
}
