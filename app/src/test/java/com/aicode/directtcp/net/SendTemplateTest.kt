package com.aicode.directtcp.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendTemplateTest {

    @Test
    fun `文本模板追加行尾`() {
        assertArrayEquals("hi\n".toByteArray(), SendTemplate.TEXT_LF.build("hi"))
        assertArrayEquals("hi\r\n".toByteArray(), SendTemplate.TEXT_CRLF.build("hi"))
        assertArrayEquals("hi".toByteArray(), SendTemplate.TEXT_RAW.build("hi"))
    }

    @Test
    fun `JSON 模板转义引号与反斜杠`() {
        val bytes = SendTemplate.JSON_LF.build("a\"b\\c")!!
        assertEquals("{\"text\":\"a\\\"b\\\\c\"}\n", String(bytes))
    }

    @Test
    fun `HEX 模板接受三种分隔`() {
        val expect = byteArrayOf(0x48, 0x65, 0x6c)
        assertArrayEquals(expect, SendTemplate.HEX.build("48 65 6c"))
        assertArrayEquals(expect, SendTemplate.HEX.build("48,65,6c"))
        assertArrayEquals(expect, SendTemplate.HEX.build("48656c"))
    }

    @Test
    fun `HEX 非法输入返回 null`() {
        assertNull(SendTemplate.HEX.build("48656"))   // 奇数长度
        assertNull(SendTemplate.HEX.build("zz"))      // 非法字符
        assertNull(SendTemplate.HEX.build(""))
    }
}
