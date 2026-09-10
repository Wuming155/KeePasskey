package com.keepasskey.app.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-17 语义红线单测：`maskPasswordsDefault` / `maskTotpDefault` 是**默认值**，
 * 不是**强制覆盖**——偏好只决定字段初始遮掩态，用户本次会话的显式展开/收起恒优先。
 *
 * 纯函数直测：不依赖 Android、不依赖 Compose，断言确定性。
 */
class FieldMaskPolicyTest {

    // ===== 用户尚未操作：遵从偏好默认值 =====

    @Test
    fun `偏好默认遮掩且用户未操作时初始态为遮掩`() {
        assertTrue(FieldMaskPolicy.initialMaskState(defaultMasked = true, userOverride = null))
    }

    @Test
    fun `偏好默认不遮掩且用户未操作时初始态为明文`() {
        assertFalse(FieldMaskPolicy.initialMaskState(defaultMasked = false, userOverride = null))
    }

    // ===== 用户已显式操作：偏好不得覆盖（回归红线） =====

    @Test
    fun `用户显式展开后偏好默认遮掩不改写其展开态`() {
        // 回归红线：设置流再次发射（pref 仍为 true）不得把用户手动展开的字段重新盖上
        assertFalse(FieldMaskPolicy.initialMaskState(defaultMasked = true, userOverride = false))
    }

    @Test
    fun `用户显式收起后偏好默认不遮掩不改写其收起态`() {
        assertTrue(FieldMaskPolicy.initialMaskState(defaultMasked = false, userOverride = true))
    }

    // ===== 偏好方向变化不影响已操作字段 =====

    @Test
    fun `用户已操作时偏好任意翻转结果恒为用户意图`() {
        val userIntent = listOf(true, false)
        val prefValues = listOf(true, false)
        for (override in userIntent) {
            for (pref in prefValues) {
                assertEquals(
                    "override=$override 时偏好 pref=$pref 不得改写用户意图",
                    override,
                    FieldMaskPolicy.initialMaskState(defaultMasked = pref, userOverride = override)
                )
            }
        }
    }

    @Test
    fun `初始遮掩态与 initialMaskState 为同一真源`() {
        // maskPasswordsDefault=true → 初始遮掩；maskTotpDefault=false → 初始明文
        val passwordMasked = FieldMaskPolicy.initialMaskState(defaultMasked = true, userOverride = null)
        val totpMasked = FieldMaskPolicy.initialMaskState(defaultMasked = false, userOverride = null)
        assertTrue(passwordMasked)
        assertFalse(totpMasked)
    }
}
