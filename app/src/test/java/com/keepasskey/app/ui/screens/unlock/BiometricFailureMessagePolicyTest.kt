package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.IntegrityBlockReason
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

    // ========== ISSUE-P2-227：点名具体命中信号（归因不再笼统） ==========

    @Test
    fun `携带可调试构建信号时点名该信号专属文案`() {
        val message = BiometricFailureMessagePolicy.of(
            BiometricResult.Error(
                errorCode = BiometricAuthManager.ERROR_INTEGRITY_BLOCKED,
                errString = BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC,
                blockReasons = listOf(IntegrityBlockReason.DEBUGGABLE_BUILD)
            )
        )
        assertEquals(R.string.sec_biometric_block_debuggable, message.resId)
    }

    @Test
    fun `多信号并存时点名危害度最高的一项`() {
        // 清单由闸门按危害度降序产出（动态攻击特征在前），UI 取首项
        val message = BiometricFailureMessagePolicy.of(
            BiometricResult.Error(
                errorCode = BiometricAuthManager.ERROR_INTEGRITY_BLOCKED,
                errString = BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC,
                blockReasons = listOf(
                    IntegrityBlockReason.HOOK_FRAMEWORK,
                    IntegrityBlockReason.DEBUGGABLE_BUILD,
                    IntegrityBlockReason.UNTRUSTED_INSTALLER
                )
            )
        )
        assertEquals(R.string.sec_biometric_block_hook, message.resId)
    }

    @Test
    fun `扫描未判定态用未判定专属文案而非风险指控`() {
        val message = BiometricFailureMessagePolicy.of(
            BiometricResult.Error(
                errorCode = BiometricAuthManager.ERROR_INTEGRITY_BLOCKED,
                errString = BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC,
                blockReasons = listOf(IntegrityBlockReason.SCAN_UNDETERMINED)
            )
        )
        assertEquals(R.string.sec_biometric_block_undetermined, message.resId)
    }

    /** 契约锁（ISSUE-P1-10 沿用）：每条专属文案都不得带格式化参数，避免把标识类数据插值进 UI */
    @Test
    fun `点名信号后仍不携带任何格式化参数`() {
        IntegrityBlockReason.values().forEach { reason ->
            assertEquals(
                "原因 $reason 的专属文案不得声明占位符",
                emptyList<Any>(),
                BiometricFailureMessagePolicy.of(
                    BiometricResult.Error(
                        errorCode = BiometricAuthManager.ERROR_INTEGRITY_BLOCKED,
                        errString = "",
                        blockReasons = listOf(reason)
                    )
                ).args
            )
        }
    }
}
