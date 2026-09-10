package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlagSecurePolicy 守卫状态机单元测试（ISSUE-P2-09 / ZT-14）。
 *
 * 覆盖「锁定时强制开」「解锁时用户开关 × 风险确认临时豁免」的组合行为，
 * 以及豁免到期/边界（nowMs == 截止时间即失效）。
 */
class FlagSecurePolicyTest {

    private val window = FlagSecurePolicy.TEMPORARY_EXEMPTION_WINDOW_MS

    @Test
    fun `会话锁定时即使用户关闭开关也强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(true, true, null, 0L))
        assertTrue(FlagSecurePolicy.shouldApplySecure(true, false, null, 0L))
    }

    @Test
    fun `会话锁定时有效临时豁免同样不生效`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(true, false, window, window / 2))
    }

    @Test
    fun `解锁且用户开关开启时强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(false, true, null, 0L))
    }

    @Test
    fun `解锁且用户关闭开关但无风险确认时仍 fail-closed 强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(false, false, null, 0L))
    }

    @Test
    fun `解锁且用户关闭开关但存在有效临时豁免时允许解除遮蔽`() {
        assertFalse(FlagSecurePolicy.shouldApplySecure(false, false, window, window / 2))
    }

    @Test
    fun `临时豁免到期后恢复强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(false, false, window, window + 1))
    }

    @Test
    fun `临时豁免边界时刻视为已失效`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(false, false, window, window))
    }

    @Test
    fun `豁免生效状态判定与主裁决一致`() {
        assertTrue(FlagSecurePolicy.isExemptionActive(false, window, 0L))
        assertFalse(FlagSecurePolicy.isExemptionActive(false, null, 0L))
        assertFalse(FlagSecurePolicy.isExemptionActive(false, window, window))
        assertFalse(FlagSecurePolicy.isExemptionActive(true, window, 0L))
    }
}
