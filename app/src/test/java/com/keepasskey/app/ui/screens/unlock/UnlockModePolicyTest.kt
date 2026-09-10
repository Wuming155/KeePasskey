package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解锁模式推导策略单测（ISSUE-P3-01 根因回归锁）。
 *
 * 核心回归点：设置流（生物识别开关）与数据库流（封印凭据可用性）抵达顺序不定，
 * 无论顺序如何，两者最终取值一旦齐备就必须推导出快速解锁模式——
 * 原实现把推导绑定在「设置抵达」这一刻，先到者定格终态，导致真机第二次解锁不弹生物识别。
 */
class UnlockModePolicyTest {

    @Test
    fun `开关开启且存在封印凭据时推导为快速解锁`() {
        assertEquals(
            UnlockMode.QUICK_UNLOCK,
            UnlockModePolicy.resolve(
                biometricEnabled = true,
                quickUnlockAvailable = true,
                explicitSelection = null
            )
        )
    }

    @Test
    fun `开关关闭时即便存在封印凭据也回落主密码模式`() {
        assertEquals(
            UnlockMode.STANDARD,
            UnlockModePolicy.resolve(
                biometricEnabled = false,
                quickUnlockAvailable = true,
                explicitSelection = null
            )
        )
    }

    @Test
    fun `无封印凭据时回落主密码模式`() {
        assertEquals(
            UnlockMode.STANDARD,
            UnlockModePolicy.resolve(
                biometricEnabled = true,
                quickUnlockAvailable = false,
                explicitSelection = null
            )
        )
    }

    @Test
    fun `显式选择优先于可用性推导`() {
        assertEquals(
            UnlockMode.STANDARD,
            UnlockModePolicy.resolve(
                biometricEnabled = true,
                quickUnlockAvailable = true,
                explicitSelection = UnlockMode.STANDARD
            )
        )
        assertEquals(
            UnlockMode.QUICK_UNLOCK,
            UnlockModePolicy.resolve(
                biometricEnabled = false,
                quickUnlockAvailable = false,
                explicitSelection = UnlockMode.QUICK_UNLOCK
            )
        )
    }

    /**
     * 竞态回归锁（真机 P3-01 现象的最小复现）：
     * 设置先抵达时凭据状态尚未就绪 → 只能推导主密码模式；
     * 凭据随后抵达后**必须**能重算出快速解锁模式（原实现此步无重算时机，终态被先到者定格）。
     */
    @Test
    fun `设置先到、凭据后到时仍能重算出快速解锁模式`() {
        val afterSettingsOnly = UnlockModePolicy.resolve(
            biometricEnabled = true,
            quickUnlockAvailable = false,
            explicitSelection = null
        )
        assertEquals("设置先到时应先呈现主密码模式", UnlockMode.STANDARD, afterSettingsOnly)

        val afterSealedCredentialArrived = UnlockModePolicy.resolve(
            biometricEnabled = true,
            quickUnlockAvailable = true,
            explicitSelection = null
        )
        assertEquals("凭据后到时必须重算为快速解锁模式", UnlockMode.QUICK_UNLOCK, afterSealedCredentialArrived)
    }
}
