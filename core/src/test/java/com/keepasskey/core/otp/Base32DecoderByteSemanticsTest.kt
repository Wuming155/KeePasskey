package com.keepasskey.core.otp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * ISSUE-P2-12：Base32Decoder 字节语义重载与既有 String 入口行为等价，
 * 且全程不构造不可擦除的种子 String（生产路径只走字节重载）。
 */
class Base32DecoderByteSemanticsTest {

    @Test
    fun `字节入口与字符串入口解码结果一致`() {
        val text = "gez dgn bvg y3t qoj q"
        val fromString = Base32Decoder.decode(text)
        val fromBytes = Base32Decoder.decode(text.toByteArray(StandardCharsets.US_ASCII))
        try {
            assertArrayEquals("两种入口必须产生相同字节流", fromString, fromBytes)
        } finally {
            fromString.fill(0)
            fromBytes.fill(0)
        }
    }

    @Test
    fun `字节入口对大小写_填充与空白不敏感`() {
        val canonical = Base32Decoder.decode("MZXW6YTB".toByteArray(StandardCharsets.US_ASCII))
        val messy = Base32Decoder.decode("mzxw 6ytb===!@#".toByteArray(StandardCharsets.US_ASCII))
        try {
            assertTrue(canonical.isNotEmpty())
            assertArrayEquals("大小写/空白/填充/字母表外字符必须被一致忽略", canonical, messy)
        } finally {
            canonical.fill(0)
            messy.fill(0)
        }
    }

    @Test
    fun `空与非法输入返回空数组`() {
        assertEquals(0, Base32Decoder.decode(ByteArray(0)).size)
        assertEquals(0, Base32Decoder.decode("!!!!".toByteArray(StandardCharsets.US_ASCII)).size)
    }
}
