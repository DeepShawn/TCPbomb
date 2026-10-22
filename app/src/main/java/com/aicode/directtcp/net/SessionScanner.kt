package com.aicode.directtcp.net

import java.io.File
import java.net.InetAddress

/**
 * 解析 /proc/net/tcp、/proc/net/tcp6。
 *
 * 行格式（内核 net/ipv4/tcp_ipv4.c: tcp4_seq_show）：
 *
 *   sl  local_address rem_address   st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode
 *   0:  0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000   0  12345 1 ...
 *
 * 地址是小端序的 32 位十六进制（IPv6 是 4 组小端 32 位，共 32 个字符）。
 */
object SessionScanner {

    private const val PROC_TCP = "/proc/net/tcp"
    private const val PROC_TCP6 = "/proc/net/tcp6"

    fun scan(): ScanResult {
        val sessions = ArrayList<TcpSession>()
        val errors = ArrayList<String>()

        for (path in listOf(PROC_TCP, PROC_TCP6)) {
            val file = File(path)
            if (!file.exists()) {
                errors += "$path 不存在"
                continue
            }
            try {
                // 只读一次，避免扫描期间文件变化导致的异常
                val text = file.readText()
                sessions += parse(text, ipv6 = path.endsWith("6"))
            } catch (e: Exception) {
                errors += "读取 $path 失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            }
        }

        val sorted = sessions.sortedWith(
            compareByDescending<TcpSession> { it.isEstablished }
                .thenByDescending { it.hasRemote }
                .thenBy { it.uid }
                .thenBy { it.remotePort }
        )
        return ScanResult(sorted, errors)
    }

    fun parse(text: String, ipv6: Boolean): List<TcpSession> {
        val out = ArrayList<TcpSession>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("sl")) continue
            parseLine(line, ipv6)?.let(out::add)
        }
        return out
    }

    private fun parseLine(line: String, ipv6: Boolean): TcpSession? {
        val f = line.split(Regex("\\s+"))
        if (f.size < 10) return null

        // 0: 0100007F:1F90 00000000:0000 0A ...
        val local = decodeEndpoint(f[1], ipv6) ?: return null
        val remote = decodeEndpoint(f[2], ipv6) ?: return null
        val state = TcpState.of(f[3])

        // 标准列位；个别内核/ROM 列数有差异，做一次回退
        var uid = f[7].toIntOrNull()
        var inode = f[9].toLongOrNull()
        if (uid == null || inode == null) {
            val numbers = f.drop(4).mapNotNull { it.toLongOrNull() }
            if (numbers.size >= 3) {
                inode = numbers.last()
                uid = numbers[numbers.size - 3].toInt()
            }
        }
        if (uid == null) uid = -1
        if (inode == null) inode = -1L

        return TcpSession(
            uid = uid,
            inode = inode,
            localIp = local.first,
            localPort = local.second,
            remoteIp = remote.first,
            remotePort = remote.second,
            state = state,
        )
    }

    /** "0100007F:1F90" -> ("127.0.0.1", 8080) */
    fun decodeEndpoint(field: String, ipv6: Boolean): Pair<String, Int>? {
        val idx = field.lastIndexOf(':')
        if (idx <= 0) return null
        val ipHex = field.substring(0, idx)
        val port = field.substring(idx + 1).toIntOrNull(16) ?: return null
        val ip = if (ipv6) decodeIpv6(ipHex) else decodeIpv4(ipHex)
        return ip?.let { it to port }
    }

    fun decodeIpv4(hex: String): String? {
        if (hex.length != 8) return null
        val v = hex.toLongOrNull(16) ?: return null
        // /proc 里是小端：低字节是第一个八位组
        return "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
    }

    fun decodeIpv6(hex: String): String? {
        if (hex.length != 32) return null
        val bytes = ByteArray(16)
        for (g in 0 until 4) {
            val group = hex.substring(g * 8, g * 8 + 8).toLongOrNull(16) ?: return null
            bytes[g * 4 + 0] = (group and 0xFF).toByte()
            bytes[g * 4 + 1] = ((group shr 8) and 0xFF).toByte()
            bytes[g * 4 + 2] = ((group shr 16) and 0xFF).toByte()
            bytes[g * 4 + 3] = ((group shr 24) and 0xFF).toByte()
        }
        // ::ffff:a.b.c.d 折叠回 IPv4，方便直接拿去连接
        val v4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        if (v4Mapped) {
            return "${bytes[12].toInt() and 0xFF}.${bytes[13].toInt() and 0xFF}" +
                ".${bytes[14].toInt() and 0xFF}.${bytes[15].toInt() and 0xFF}"
        }
        return InetAddress.getByAddress(bytes).hostAddress?.substringBefore('%')
    }
}
