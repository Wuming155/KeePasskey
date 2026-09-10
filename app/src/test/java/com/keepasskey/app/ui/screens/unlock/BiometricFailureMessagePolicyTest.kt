package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生物识别失败文案映射单测（ISSUE-P3-14 验收 1 与「errString 不对外展示」的契约锁）。
 *
 * 关键断言：文案只由**错误码**决定，与内部诊断串 `errString` 完全无关——
 * 一旦有人再把文案写回 `errString`（或反向从 `errString` 取值），本用例即失败。
 */
class BiometricFailureMessagePolicyTest {

    @Test
    fun `完整性风险态映射到已资源化的专用文案`() {
        val message = BiometricFailureMessagePolicy.of(
            BiometricResult.Error(
                BiometricAuthManager.ERROR_INTEGRITY_BLOCKED,
                BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC
            )
        )
        assertEquals(R.string.sec_biometric_integrity_blocked, message.resId)
    }

    @Test
    fun `其余系统错误映射到通用失败文案`() {
        val message = BiometricFailureMessagePolicy.of(BiometricResult.Error(7, "some system error"))
        assertEquals(R.string.sec_biometric_auth_failed, message.resId)
    }

    /** 契约锁：同一错误码无论携带何种诊断串（含中文/空串/系统英文描述），映射结果必须完全一致 */
    @Test
    fun `文案映射与内部诊断串无关`() {
        val expected = R.string.sec_biometric_integrity_blocked
        val diagnosticVariants = listOf(
            BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC,
            "",
            "设备完整性风险，已禁用生物识别快速解锁",
            "device integrity risk"
        )
        diagnosticVariants.forEach { diagnostic ->
            assertEquals(
                "诊断串 [$diagnostic] 不得影响用户可见文案的资源映射",
                expected,
                BiometricFailureMessagePolicy.of(
                    BiometricResult.Error(BiometricAuthManager.ERROR_INTEGRITY_BLOCKED, diagnostic)
                ).resId
            )
        }
    }

    @Test
    fun `映射结果不携带任何格式化参数`() {
        assertEquals(
            emptyList<Any>(),
            BiometricFailureMessagePolicy.of(
                BiometricResult.Error(BiometricAuthManager.ERROR_INTEGRITY_BLOCKED, "")
            ).args
        )
    }
}
