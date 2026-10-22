package com.aicode.directtcp.net

/**
 * 发送内容模板。纯 Kotlin，不依赖 Android，方便单元测试。
 */
enum class SendTemplate(val label: String, val build: (String) -> ByteArray?) {
    TEXT_LF("文本 + \\n", { (it + "\n").toByteArray(Charsets.UTF_8) }),
    TEXT_CRLF("文本 + \\r\\n", { (it + "\r\n").toByteArray(Charsets.UTF_8) }),
    TEXT_RAW("文本无行尾", { it.toByteArray(Charsets.UTF_8) }),
    JSON_LF("JSON 行", {
        val escaped = it.replace("\\", "\\\\").replace("\"", "\\\"")
        "{\"text\":\"$escaped\"}\n".toByteArray(Charsets.UTF_8)
    }),
    HEX("HEX 字节", { hexToBytes(it) });

    companion object {
        /** "48 65 6c" / "48,65,6c" / "48656c" 都接受；奇数长度或非法字符返回 null */
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
