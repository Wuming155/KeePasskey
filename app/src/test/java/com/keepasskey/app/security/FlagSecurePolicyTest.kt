package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlagSecurePolicy 守卫状态机单元测试（ISSUE-P2-09 / ZT-14；2026-09-12 开关即生效语义）。
 *
 * 覆盖：锁定态无条件强制遮蔽（fail-closed）、解锁态只看用户开关（关闭 = 真实解除）。
 */
class FlagSecurePolicyTest {

    @Test
    fun `会话锁定时即使用户关闭开关也强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(sessionLocked = true, userEnabled = true))
        assertTrue(FlagSecurePolicy.shouldApplySecure(sessionLocked = true, userEnabled = false))
    }

    @Test
    fun `解锁且用户开关开启时强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(sessionLocked = false, userEnabled = true))
    }

    @Test
    fun `解锁且用户关闭开关时真实解除遮蔽`() {
        // 2026-09-12 用户裁决：原「关闭后仍默认强制 + 5 分钟临时豁免」模型构成假开关
        // （UI 风险确认从未接通豁免入口），改为开关即生效
        assertFalse(FlagSecurePolicy.shouldApplySecure(sessionLocked = false, userEnabled = false))
    }
}
