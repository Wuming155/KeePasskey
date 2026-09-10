package com.keepasskey.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-15 回归：受保护剪贴板的 CharArray 直通支撑件。
 *
 * 校验 [SensitiveCharSequence] 零拷贝只读视图语义，以及 [sensitiveTextSha256] 让
 * CharArray 通道与 String 通道得到**相同摘要**（保证剪贴板自动擦除的比对不受通道切换影响）。
 */
class SensitiveCharSequenceTest {

    @Test
    fun `视图直接读取借用数组且子序列独立`() {
        val chars = "p@ssw0rd".toCharArray()
        val seq = SensitiveCharSequence(chars)

        assertEquals(8, seq.length)
        assertEquals('p', seq[0])
        assertEquals('d', seq[7])
        assertEquals("ssw0", seq.subSequence(2, 6).toString())
    }

    @Test
    fun `CharArray 通道与 String 通道摘要一致`() {
        val value = "CorrectHorseBatteryStaple-42!"
        val chars = value.toCharArray()
        try {
            val fromChars = sensitiveTextSha256(SensitiveCharSequence(chars))
            val fromString = sensitiveTextSha256(value)
            assertArrayEquals("两通道对同一内容必须得到相同摘要", fromString, fromChars)
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `内容变化后摘要随之变化`() {
        val charsA = "alpha-secret".toCharArray()
        val charsB = "alpha-secret!".toCharArray()
        try {
            val hashA = sensitiveTextSha256(SensitiveCharSequence(charsA))
            val hashB = sensitiveTextSha256(SensitiveCharSequence(charsB))
            assertFalse(hashA.contentEquals(hashB))
            assertEquals(32, hashA.size)
        } finally {
            charsA.fill('0')
            charsB.fill('0')
        }
    }

    @Test
    fun `UTF-8 多字节内容通道摘要仍一致`() {
        val value = "密码-中文-Ω"
        val chars = value.toCharArray()
        try {
            assertArrayEquals(
                sensitiveTextSha256(value),
                sensitiveTextSha256(SensitiveCharSequence(chars))
            )
        } finally {
            chars.fill('0')
        }
    }
}
