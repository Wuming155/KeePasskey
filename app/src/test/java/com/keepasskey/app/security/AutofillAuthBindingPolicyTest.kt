package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher

/**
 * AutofillAuthBindingPolicy 单元测试（ISSUE-P3-52）。
 *
 * 核心安全断言：自动填充的认证放行**必须**携带 CryptoObject（非空 Cipher）；
 * 认证成功但无 Cipher / 失败 / 取消一律不得放行明文凭据（fail-closed）。
 */
class AutofillAuthBindingPolicyTest {

    private val dummyCipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    @Test
    fun `认证成功且携带 Cipher 时可放行`() {
        assertTrue(AutofillAuthBindingPolicy.isBound(BiometricResult.Success(dummyCipher)))
    }

    @Test
    fun `认证成功但无 Cipher 时不得放行`() {
        assertFalse(AutofillAuthBindingPolicy.isBound(BiometricResult.Success(null)))
    }

    @Test
    fun `失败与取消结果一律不得放行`() {
        assertFalse(AutofillAuthBindingPolicy.isBound(BiometricResult.Failed))
        assertFalse(AutofillAuthBindingPolicy.isBound(BiometricResult.Cancelled))
        assertFalse(
            AutofillAuthBindingPolicy.isBound(
                BiometricResult.Error(BiometricAuthManager.ERROR_INTEGRITY_BLOCKED, "INTEGRITY_BLOCKED")
            )
        )
    }
}
