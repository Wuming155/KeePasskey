package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.security.BiometricResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生物识别失败文案映射单测（ISSUE-P3-14 验收 1 与「errString 不对外展示」的契约锁；
 * ISSUE-P2-355 AC④ 增补 lockout / hardware / timeout 三档分档断言）。
 *
 * 关键断言：文案只由**错误码**决定，与内部诊断串 `errString` 完全无关——
 * 一旦有人再把文案写回 `errString`（或反向从 `errString` 取值），本用例即失败。
 *
 * 原完整性闸门分支（`ERROR_INTEGRITY_BLOCKED` → 专用文案 / 命中信号点名，ISSUE-P2-227）
 * 随运行环境完整性门整体移除（ISSUE-P3-325），相应用例一并删除。
 */
class BiometricFailureMessagePolicyTest {

    @Test
    fun `其余系统错误映射到通用失败文案`() {
        // 2 = ERROR_UNABLE_TO_PROCESS，不属 lockout/hardware/timeout 任一档 → 策略兜底
        val message = BiometricFailureMessagePolicy.of(BiometricResult.Error(2, "some system error"))
        assertEquals(R.string.sec_biometric_auth_failed, message.resId)
    }

    /** 契约锁：同一错误码无论携带何种诊断串（含中文/空串/系统英文描述），映射结果必须完全一致 */
    @Test
    fun `文案映射与内部诊断串无关`() {
        val expected = R.string.sec_biometric_auth_failed
        val diagnosticVariants = listOf(
            "",
            "设备存在安全风险，已禁用生物识别快速解锁",
            "device integrity risk"
        )
        diagnosticVariants.forEach { diagnostic ->
            assertEquals(
                "诊断串 [$diagnostic] 不得影响用户可见文案的资源映射",
                expected,
                BiometricFailureMessagePolicy.of(
                    BiometricResult.Error(2, diagnostic)
                ).resId
            )
        }
    }

    @Test
    fun `映射结果不携带任何格式化参数`() {
        assertEquals(
            emptyList<Any>(),
            BiometricFailureMessagePolicy.of(
                BiometricResult.Error(7, "")
            ).args
        )
    }

    // ── ISSUE-P2-355 AC④：按 errorCode 分档（lockout / hardware / timeout / 其他） ──────

    @Test
    fun `锁定类错误码映射到锁定文案`() {
        // 7 = ERROR_LOCKOUT、9 = ERROR_LOCKOUT_PERMANENT
        listOf(7, 9).forEach { code ->
            assertEquals(
                "错误码 $code 应归入锁定档",
                R.string.sec_biometric_lockout,
                BiometricFailureMessagePolicy.of(BiometricResult.Error(code, "locked out")).resId
            )
        }
    }

    @Test
    fun `硬件类错误码映射到硬件文案`() {
        // 1 = ERROR_HW_UNAVAILABLE、12 = ERROR_HW_NOT_PRESENT、11 = ERROR_NO_BIOMETRICS
        listOf(1, 12, 11).forEach { code ->
            assertEquals(
                "错误码 $code 应归入硬件档",
                R.string.sec_biometric_hardware,
                BiometricFailureMessagePolicy.of(BiometricResult.Error(code, "no hardware")).resId
            )
        }
    }

    @Test
    fun `超时错误码映射到超时文案`() {
        // 3 = ERROR_TIMEOUT
        assertEquals(
            R.string.sec_biometric_timeout,
            BiometricFailureMessagePolicy.of(BiometricResult.Error(3, "timeout")).resId
        )
    }

    /** 分档互斥锁：三档文案彼此不同，且与策略兜底文案不同（防映射坍缩回同一句） */
    @Test
    fun `各档文案互不相同`() {
        val byTier = listOf(
            BiometricFailureMessagePolicy.of(BiometricResult.Error(7, "")).resId,
            BiometricFailureMessagePolicy.of(BiometricResult.Error(1, "")).resId,
            BiometricFailureMessagePolicy.of(BiometricResult.Error(3, "")).resId,
            BiometricFailureMessagePolicy.of(BiometricResult.Error(2, "")).resId
        )
        assertEquals("四档文案必须两两不同", byTier.size, byTier.toSet().size)
    }
}
