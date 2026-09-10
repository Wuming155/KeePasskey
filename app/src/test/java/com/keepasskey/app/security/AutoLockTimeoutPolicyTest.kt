package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-13 (ZT-18/ZT-19) 自动锁定超时纯内核单测。
 *
 * 三档语义必须与设置页（SecuritySettingsScreen 自动锁定倒计时选项）严格一致：
 * -1 = 永不（不得启动定时器）、0 = 立即（退后台即超时）、> 0 = 秒数。
 */
class AutoLockTimeoutPolicyTest {

    @Test
    fun `永不档（-1）映射为 NEVER 且不提供定时器延迟`() {
        assertEquals(
            AutoLockTimeoutMode.NEVER,
            AutoLockTimeoutPolicy.modeOf(AutoLockTimeoutPolicy.NEVER_SECONDS)
        )
        // null 即「禁止启动定时器」：后台期间不得有任何延迟锁定任务
        assertNull(AutoLockTimeoutPolicy.delayMillis(AutoLockTimeoutPolicy.NEVER_SECONDS))
        assertFalse(
            AutoLockTimeoutPolicy.isExpired(
                AutoLockTimeoutPolicy.NEVER_SECONDS,
                elapsedMillis = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `立即档（0）映射为 IMMEDIATE 且零延迟立即超时`() {
        assertEquals(
            AutoLockTimeoutMode.IMMEDIATE,
            AutoLockTimeoutPolicy.modeOf(AutoLockTimeoutPolicy.IMMEDIATE_SECONDS)
        )
        assertEquals(0L, AutoLockTimeoutPolicy.delayMillis(AutoLockTimeoutPolicy.IMMEDIATE_SECONDS))
        assertTrue(AutoLockTimeoutPolicy.isExpired(AutoLockTimeoutPolicy.IMMEDIATE_SECONDS, 0L))
        assertTrue(AutoLockTimeoutPolicy.isExpired(AutoLockTimeoutPolicy.IMMEDIATE_SECONDS, 1L))
    }

    @Test
    fun `正数档（30）按秒换算定时器延迟`() {
        assertEquals(AutoLockTimeoutMode.AFTER_SECONDS, AutoLockTimeoutPolicy.modeOf(THIRTY_SECONDS))
        assertEquals(
            THIRTY_SECONDS * AutoLockTimeoutPolicy.MILLIS_PER_SECOND,
            AutoLockTimeoutPolicy.delayMillis(THIRTY_SECONDS)
        )
    }

    @Test
    fun `正数档（30）严格在到达阈值时才判定超时`() {
        val justBefore = THIRTY_SECONDS * AutoLockTimeoutPolicy.MILLIS_PER_SECOND - 1L
        val atThreshold = THIRTY_SECONDS * AutoLockTimeoutPolicy.MILLIS_PER_SECOND

        assertFalse(AutoLockTimeoutPolicy.isExpired(THIRTY_SECONDS, justBefore))
        assertTrue(AutoLockTimeoutPolicy.isExpired(THIRTY_SECONDS, atThreshold))
        assertTrue(AutoLockTimeoutPolicy.isExpired(THIRTY_SECONDS, atThreshold + 1L))
    }

    @Test
    fun `负值统一归入永不档而非立即锁定`() {
        // 旧实现把任何 <= 0 一律判为立即锁定，导致 -1「从不」与 UI 承诺相反
        assertEquals(AutoLockTimeoutMode.NEVER, AutoLockTimeoutPolicy.modeOf(-1000))
        assertNull(AutoLockTimeoutPolicy.delayMillis(-1000))
        assertFalse(AutoLockTimeoutPolicy.isExpired(-1000, 1L))
    }

    companion object {
        private const val THIRTY_SECONDS = 30
    }
}
