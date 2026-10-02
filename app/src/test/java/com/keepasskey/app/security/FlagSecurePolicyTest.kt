package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlagSecurePolicy 守卫状态机单元测试（ISSUE-P2-09 / ZT-14）。
 *
 * 语义演进：
 * - 2026-09-12：解锁态「开关即生效」取代「临时豁免」模型（假开关整改）；
 * - 2026-10-02 用户裁决（§411 走查）：撤销「锁定态无条件强制遮蔽」——
 *   用户关闭截屏防护后解锁页（主密码输入）必须真实解除遮蔽。
 *   最终口径：FLAG_SECURE **只**由用户开关决定。
 */
class FlagSecurePolicyTest {

    @Test
    fun `用户开关开启时强制遮蔽`() {
        assertTrue(FlagSecurePolicy.shouldApplySecure(userEnabled = true))
    }

    @Test
    fun `用户关闭开关时真实解除遮蔽`() {
        // 2026-09-12 用户裁决：原「关闭后仍默认强制 + 5 分钟临时豁免」模型构成假开关
        // （UI 风险确认从未接通豁免入口），改为开关即生效；
        // 2026-10-02 用户裁决：锁定态强制语义一并撤销——解锁页跟随开关真实解除
        assertFalse(FlagSecurePolicy.shouldApplySecure(userEnabled = false))
    }
}
