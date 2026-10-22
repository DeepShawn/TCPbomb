package com.aicode.directtcp.net

/**
 * 一条本机 TCP 会话，来自 /proc/net/tcp 或 /proc/net/tcp6。
 *
 * 这两个文件是全局可读的（0444），不需要 root，也不需要任何权限，
 * 因此可以用它观察「其他 App 已经建立了哪些连接」，从而拿到对端地址。
 */
data class TcpSession(
    val uid: Int,
    val inode: Long,
    val localIp: String,
    val localPort: Int,
    val remoteIp: String,
    val remotePort: Int,
    val state: TcpState,
) {
    /** 稳定标识，用于列表复用与选中判断 */
    val key: String get() = "$localIp:$localPort>$remoteIp:$remotePort#$inode"

    val isEstablished: Boolean get() = state == TcpState.ESTABLISHED

    /** 对端是否可以直连（LISTEN / 未连接的对端没有意义） */
    val hasRemote: Boolean get() = remotePort != 0 && remoteIp != "0.0.0.0" && remoteIp != "::"

    val endpoint: String get() = "$remoteIp:$remotePort"
}

enum class TcpState(val code: String, val label: String) {
    ESTABLISHED("01", "已建立"),
    SYN_SENT("02", "SYN_SENT"),
    SYN_RECV("03", "SYN_RECV"),
    FIN_WAIT1("04", "FIN_WAIT1"),
    FIN_WAIT2("05", "FIN_WAIT2"),
    TIME_WAIT("06", "TIME_WAIT"),
    CLOSE("07", "CLOSE"),
    CLOSE_WAIT("08", "CLOSE_WAIT"),
    LAST_ACK("09", "LAST_ACK"),
    LISTEN("0A", "监听中"),
    CLOSING("0B", "CLOSING"),
    UNKNOWN("??", "未知");

    companion object {
        fun of(code: String): TcpState =
            TcpState.entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 一次扫描的结果，带上读取失败原因（有些 ROM 会限制 procfs，需要提示用户） */
data class ScanResult(
    val sessions: List<TcpSession>,
    val errors: List<String> = emptyList(),
)
