package com.keepasskey.app.data.breach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 泄露比对哈希器单元测试（TASK-47）。
 *
 * 已知答案向量取自公开常量：SHA-1("password") = `5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8`
 * （Pwned Passwords 官方文档示例值，非真实凭据）。
 */
class BreachHasherTest {

    @Test
    fun `SHA-1 十六进制输出与长度符合 k-匿名协议`() {
        val hex = BreachHasher.sha1HexUpper("password".toByteArray())
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", hex)
        assertEquals(40, hex.length)
    }

    @Test
    fun `前缀后缀拆分只上送 5 位前缀`() {
        val (prefix, suffix) = BreachHasher.splitPrefixSuffix(
            BreachHasher.sha1HexUpper("password".toByteArray())
        )
        assertEquals("5BAA6", prefix)
        assertEquals(BreachHasher.PREFIX_LENGTH, prefix.length)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", suffix)
        // 上送前缀 + 本地后缀必须能还原完整哈希
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", prefix + suffix)
    }

    @Test
    fun `长度非法时 fail-closed 拒绝拆分`() {
        assertThrows(IllegalArgumentException::class.java) {
            BreachHasher.splitPrefixSuffix("5BAA6")
        }
    }

    @Test
    fun `空输入仍产出合法 SHA-1 且不抛异常`() {
        assertEquals("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709", BreachHasher.sha1HexUpper(ByteArray(0)))
    }
}
