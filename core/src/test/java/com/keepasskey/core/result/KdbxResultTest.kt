package com.keepasskey.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P1-10 (ZT-10)：KdbxResult 兜底脱敏回归——
 * 未显式提供 [KdbxResult.Failure.userText] 时不得把裸异常 message 上浮 UI（兜底为错误码，非异常内容）。
 *
 * ISSUE-P3-453：兜底值由「固定中文通用文案」改为 [KdbxError] 类型化错误码——
 * 下层模块（core / database）拿不到 app 字符串资源，只产机器可读的码，
 * 由 app 层的 `KdbxErrorTexts` 映射为资源文案；异常细节只留 [KdbxResult.Failure.error]（日志面）。
 */
class KdbxResultTest {

    @Test
    fun `未提供 userText 时兜底为错误码，绝不透出异常 message`() {
        val result = KdbxResult.runCatching<String> {
            throw IllegalStateException("https://secret.example.com/path user@example.com")
        }

        val failure = result as KdbxResult.Failure
        // ISSUE-P3-453：兜底值是错误码（ASCII 机器标识），不是任何用户可见文案
        assertEquals(KdbxError.UNKNOWN, failure.code)
        assertNull(failure.userText)
        assertFalse(failure.code.contains("secret.example.com"))
        assertFalse(failure.code.contains("user@example.com"))
    }

    @Test
    fun `显式 userText 优先于兜底错误码`() {
        val failure = KdbxResult.Failure(IllegalStateException("raw message"), "解锁密码库失败")

        assertEquals("解锁密码库失败", failure.userText)
    }

    @Test
    fun `原始异常仍保留在 error 字段供日志侧脱敏记录`() {
        val failure = KdbxResult.runCatching<String> {
            throw IllegalStateException("raw message")
        } as KdbxResult.Failure

        val error = failure.error
        assertTrue(error is IllegalStateException)
        assertEquals("raw message", error.message)
    }

    @Test
    fun `onFailure 回调携带的是错误码而非用户文案`() {
        val failure = KdbxResult.runCatching<String> {
            throw IllegalStateException("https://secret.example.com/path")
        }

        var observed: String? = null
        failure.onFailure { _, code -> observed = code }

        assertEquals(KdbxError.UNKNOWN, observed)
    }

    @Test
    fun `runCatching 不吞协程取消，CancellationException 原样重抛`() {
        // ISSUE-P3-570：JVM 上 kotlinx.coroutines.CancellationException 即
        // java.util.concurrent.CancellationException 的别名——取消被归一为 Failure 会
        // 破坏结构化并发的收敛契约（与 sync 的 runCatchingCancellable 同口径）。
        // core 无协程依赖，测试直接用 JVM 别名类断言同型语义。
        val cancelled = java.util.concurrent.CancellationException("cancelled")

        var rethrown: Throwable? = null
        try {
            KdbxResult.runCatching<String> { throw cancelled }
        } catch (t: Throwable) {
            rethrown = t
        }

        assertTrue("取消必须沿链重抛而非归一为 Failure", rethrown === cancelled)
    }
}
