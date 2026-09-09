package com.keepasskey.app.passkey

import com.keepasskey.app.security.BiometricStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P0-02 (ZT-02) 凭据下发用户验证门控单测。
 *
 * 背景：Credential Manager 密码填充通道此前零用户验证——`PasswordCredentialEntry` 未挂
 * `BiometricPromptData`，`PasswordFillActivity` 亦无确认，密码库解锁态下任意应用可
 * 在用户零交互时取得明文密码。
 *
 * 本测锁定「无确认路径不得放行」这一安全不变式：门控判定完全由无 Android 依赖的
 * [CredentialFillVerifier] 承担，因此可在纯 JVM 下穷举验证等级 × 验证结果的全部组合。
 */
class CredentialFillVerifierTest {

    private val verifier = CredentialFillVerifier()

    // ---------- 验证等级映射 ----------

    @Test
    fun `设备认证器可用时要求生物识别级验证`() {
        assertEquals(
            CredentialFillRequirement.BIOMETRIC,
            verifier.requirementFor(BiometricStatus.AVAILABLE)
        )
    }

    @Test
    fun `设备无可用认证器时一律降级为手动确认而非免验证`() {
        listOf(
            BiometricStatus.NO_HARDWARE,
            BiometricStatus.HARDWARE_UNAVAILABLE,
            BiometricStatus.NOT_ENROLLED,
            BiometricStatus.SECURITY_UPDATE_REQUIRED
        ).forEach { status ->
            assertEquals(
                "状态 $status 不得退化为免验证",
                CredentialFillRequirement.MANUAL_CONFIRMATION,
                verifier.requirementFor(status)
            )
        }
    }

    // ---------- 生物识别要求下的放行判定 ----------

    @Test
    fun `要求生物识别时通过即放行`() {
        assertTrue(
            verifier.isSatisfied(
                CredentialFillRequirement.BIOMETRIC,
                CredentialUserVerification.BiometricSucceeded
            )
        )
    }

    @Test
    fun `要求生物识别时未发生任何验证不得放行`() {
        assertFalse(
            verifier.isSatisfied(
                CredentialFillRequirement.BIOMETRIC,
                CredentialUserVerification.None
            )
        )
    }

    @Test
    fun `要求生物识别时认证失败或用户取消均不得放行`() {
        listOf(
            CredentialUserVerification.BiometricFailed,
            CredentialUserVerification.BiometricCancelled
        ).forEach { verification ->
            assertFalse(
                "验证结果 $verification 不得放行",
                verifier.isSatisfied(CredentialFillRequirement.BIOMETRIC, verification)
            )
        }
    }

    @Test
    fun `要求生物识别时手动确认不得降级放行`() {
        listOf(
            CredentialUserVerification.ManualConfirmed,
            CredentialUserVerification.ManualCancelled
        ).forEach { verification ->
            assertFalse(
                "验证结果 $verification 不得绕过生物识别要求",
                verifier.isSatisfied(CredentialFillRequirement.BIOMETRIC, verification)
            )
        }
    }

    // ---------- 手动确认要求下的放行判定 ----------

    @Test
    fun `要求手动确认时用户点选确认即放行`() {
        assertTrue(
            verifier.isSatisfied(
                CredentialFillRequirement.MANUAL_CONFIRMATION,
                CredentialUserVerification.ManualConfirmed
            )
        )
    }

    @Test
    fun `要求手动确认时更强的生物识别通过同样放行`() {
        assertTrue(
            verifier.isSatisfied(
                CredentialFillRequirement.MANUAL_CONFIRMATION,
                CredentialUserVerification.BiometricSucceeded
            )
        )
    }

    @Test
    fun `要求手动确认时未确认或取消均不得放行`() {
        listOf(
            CredentialUserVerification.None,
            CredentialUserVerification.ManualCancelled,
            CredentialUserVerification.BiometricFailed,
            CredentialUserVerification.BiometricCancelled
        ).forEach { verification ->
            assertFalse(
                "验证结果 $verification 不得放行",
                verifier.isSatisfied(CredentialFillRequirement.MANUAL_CONFIRMATION, verification)
            )
        }
    }

    // ---------- 全局不变式 ----------

    @Test
    fun `任何要求等级下未发生验证都不得放行`() {
        CredentialFillRequirement.entries.forEach { requirement ->
            assertFalse(
                "要求 $requirement 下未验证不得放行",
                verifier.isSatisfied(requirement, CredentialUserVerification.None)
            )
        }
    }

    @Test
    fun `非成功类验证结果在全部要求等级下均不得放行`() {
        val failures = listOf(
            CredentialUserVerification.None,
            CredentialUserVerification.BiometricFailed,
            CredentialUserVerification.BiometricCancelled,
            CredentialUserVerification.ManualCancelled
        )
        CredentialFillRequirement.entries.forEach { requirement ->
            failures.forEach { verification ->
                assertFalse(
                    "要求 $requirement 下 $verification 不得放行",
                    verifier.isSatisfied(requirement, verification)
                )
            }
        }
    }
}
