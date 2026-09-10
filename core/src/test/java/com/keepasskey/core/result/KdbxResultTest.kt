package com.keepasskey.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P1-10 (ZT-10)：KdbxResult.message 兜底脱敏回归——
 * 未显式提供 userMessage 时不得把裸异常 message 上浮 UI。
 */
class KdbxResultTest {

    @Test
    fun `未提供 userMessage 时兜底为固定通用文案，绝不透出异常 message`() {
        val failure = KdbxResult.runCatching<String> {
            throw IllegalStateException("https://secret.example.com/path user@example.com")
        }

        val message = (failure as KdbxResult.Failure).message
        assertEquals("未知错误", message)
        // 敏感内容不得经兜底文案外泄
        assertFalse(message.contains("secret.example.com"))
        assertFalse(message.contains("user@example.com"))
    }

    @Test
    fun `显式 userMessage 优先于兜底文案`() {
        val failure = KdbxResult.Failure(IllegalStateException("raw message"), "解锁密码库失败")

        assertEquals("解锁密码库失败", failure.message)
    }

    @Test
    fun `原始异常仍保留在 error 字段供日志侧脱敏记录`() {
        val failure = KdbxResult.runCatching<String> {
            throw IllegalStateException("raw message")
        }

        val error = (failure as KdbxResult.Failure).error
        assertTrue(error is IllegalStateException)
        assertEquals("raw message", error.message)
    }

    @Test
    fun `onFailure 回调携带的 message 同样受兜底约束`() {
        val failure = KdbxResult.runCatching<String> {
            throw IllegalStateException("https://secret.example.com/path")
        }

        var observed: String? = null
        failure.onFailure { _, message -> observed = message }

        assertEquals("未知错误", observed)
    }
}
