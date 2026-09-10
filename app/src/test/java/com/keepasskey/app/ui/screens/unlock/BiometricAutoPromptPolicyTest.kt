package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生物识别自动唤起状态机单测（ISSUE-P3-01 验收 1 / 3 的纯逻辑侧证据）。
 *
 * 重点锁死「死循环结构性不可达」：PENDING 只能从 IDLE 抵达，CONSUMED 为不可逆终态——
 * 因此取消/失败后即便条件再次全部满足，也绝不会重新进入 PENDING。
 */
class BiometricAutoPromptPolicyTest {

    private fun next(
        current: BiometricAutoPrompt,
        biometricEnabled: Boolean = true,
        quickUnlockAvailable: Boolean = true,
        unlockMode: UnlockMode = UnlockMode.QUICK_UNLOCK,
        hasDatabase: Boolean = true
    ): BiometricAutoPrompt = BiometricAutoPromptPolicy.next(
        current = current,
        biometricEnabled = biometricEnabled,
        quickUnlockAvailable = quickUnlockAvailable,
        unlockMode = unlockMode,
        hasDatabase = hasDatabase
    )

    @Test
    fun `条件齐备时由IDLE进入待消费`() {
        assertEquals(BiometricAutoPrompt.PENDING, next(BiometricAutoPrompt.IDLE))
    }

    @Test
    fun `开关关闭时不自动唤起`() {
        assertEquals(BiometricAutoPrompt.IDLE, next(BiometricAutoPrompt.IDLE, biometricEnabled = false))
    }

    @Test
    fun `无封印凭据时不自动唤起`() {
        assertEquals(BiometricAutoPrompt.IDLE, next(BiometricAutoPrompt.IDLE, quickUnlockAvailable = false))
    }

    @Test
    fun `无活动库时不自动唤起`() {
        assertEquals(BiometricAutoPrompt.IDLE, next(BiometricAutoPrompt.IDLE, hasDatabase = false))
    }

    @Test
    fun `用户显式选择主密码模式时不自动唤起`() {
        assertEquals(BiometricAutoPrompt.IDLE, next(BiometricAutoPrompt.IDLE, unlockMode = UnlockMode.STANDARD))
    }

    @Test
    fun `待消费状态不会被重复重算重置`() {
        assertEquals(BiometricAutoPrompt.PENDING, next(BiometricAutoPrompt.PENDING))
    }

    /**
     * 死循环不可达回归锁：已消费后条件再次全部满足（如用户切回快速解锁、状态抖动、重组），
     * 状态机仍恒为 CONSUMED，绝不回到 PENDING。
     */
    @Test
    fun `已消费为不可逆终态条件再次满足也不回到待消费`() {
        assertEquals(BiometricAutoPrompt.CONSUMED, next(BiometricAutoPrompt.CONSUMED))
        assertEquals(
            BiometricAutoPrompt.CONSUMED,
            next(
                current = BiometricAutoPrompt.CONSUMED,
                biometricEnabled = true,
                quickUnlockAvailable = true,
                unlockMode = UnlockMode.QUICK_UNLOCK,
                hasDatabase = true
            )
        )
    }
}
