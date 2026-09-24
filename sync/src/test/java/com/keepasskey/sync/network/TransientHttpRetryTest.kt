package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [TransientHttpRetry] 纯调度内核单测（ISSUE-P3-298 ③）。
 *
 * 覆盖：成功即返回、瞬时失败按次数重试、重试耗尽上抛、
 * 「业务语义异常不重试」「协程取消不重试」两条安全边界、
 * 以及 [TransientHttpRetry.isRetryableStatus] 的状态码口径。
 */
class TransientHttpRetryTest {

    @Test
    fun `首次成功即返回，不重试`() = runBlocking {
        var attempts = 0
        val result = TransientHttpRetry.run(maxAttempts = 3, baseDelayMs = 1) {
            attempts++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `IOException 瞬时失败重试至成功`() = runBlocking {
        var attempts = 0
        val result = TransientHttpRetry.run(maxAttempts = 3, baseDelayMs = 1) { _ ->
            attempts++
            if (attempts < 3) throw IOException("连接重置")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `重试耗尽后上抛最后一次失败`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                TransientHttpRetry.run(maxAttempts = 3, baseDelayMs = 1) {
                    throw IOException("持续不可达")
                }
            }
        }
    }

    @Test
    fun `业务语义异常（SyncException）不重试`() {
        var attempts = 0
        assertThrows(SyncException.AuthenticationError::class.java) {
            runBlocking {
                TransientHttpRetry.run(maxAttempts = 3, baseDelayMs = 1) {
                    attempts++
                    throw SyncException.AuthenticationError("鉴权失败")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `协程取消不属可重试失败`() {
        assertTrue(!TransientHttpRetry.isRetryableFailure(CancellationException("外部取消")))
        assertTrue(TransientHttpRetry.isRetryableFailure(IOException("连接重置")))
        assertTrue(TransientHttpRetry.isRetryableFailure(TransientHttpRetry.RetryableStatus(502)))
        assertTrue(!TransientHttpRetry.isRetryableFailure(IllegalStateException("意外")))
    }

    @Test
    fun `RetryableStatus 属 IOException 家族故可重试`() = runBlocking {
        var attempts = 0
        val result = TransientHttpRetry.run(maxAttempts = 2, baseDelayMs = 1) { _ ->
            attempts++
            if (attempts == 1) throw TransientHttpRetry.RetryableStatus(503)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `可重试状态码口径`() {
        for (code in listOf(408, 425, 429, 500, 502, 503, 504)) {
            assertTrue("HTTP $code 应可重试", TransientHttpRetry.isRetryableStatus(code))
        }
        for (code in listOf(200, 201, 204, 207, 401, 403, 404, 412)) {
            assertTrue("HTTP $code 不应可重试", !TransientHttpRetry.isRetryableStatus(code))
        }
    }

    @Test
    fun `maxAttempts 小于 1 时快速失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                TransientHttpRetry.run(maxAttempts = 0, baseDelayMs = 1) { "x" }
            }
        }
    }
}
