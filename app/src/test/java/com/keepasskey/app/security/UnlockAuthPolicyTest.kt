package com.keepasskey.app.security

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UnlockAuthPolicy 封印策略单元测试（ISSUE-P1-08）：
 * 覆盖「仅强生物识别」认证器集合选择分支与封印许可闸门（弱凭据禁用封印）。
 *
 * 常量均为编译期内联的 Java static final int，JVM 环境可直接断言位掩码语义。
 */
class UnlockAuthPolicyTest {

    // ── 认证器集合：不得含设备锁屏凭据 ─────────────────────────────

    @Test
    fun `密钥生成侧授权集合仅含强生物识别`() {
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, UnlockAuthPolicy.keystoreAuthTypes)
        // 关键安全断言：不含 AUTH_DEVICE_CREDENTIAL——含该位的密钥可被系统锁屏 PIN/图案/密码解封，
        // 且 setInvalidatedByBiometricEnrollment 被系统忽略（新增指纹不使既有凭据失效）
        assertEquals(0, UnlockAuthPolicy.keystoreAuthTypes and KeyProperties.AUTH_DEVICE_CREDENTIAL)
    }

    @Test
    fun `认证请求侧认证器集合仅含强生物识别`() {
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            UnlockAuthPolicy.promptAuthenticators
        )
        assertEquals(
            0,
            UnlockAuthPolicy.promptAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    }

    @Test
    fun `密钥生成侧与认证请求侧语义一致`() {
        // 官方硬性要求：解锁加密操作请求的认证器集合必须与密钥生成时一致。
        // 两侧掩码数值分属不同枚举体系，但「是否含设备凭据」语义必须同真同假。
        val keystoreHasCredential =
            UnlockAuthPolicy.keystoreAuthTypes and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0
        val promptHasCredential =
            UnlockAuthPolicy.promptAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0
        assertEquals(keystoreHasCredential, promptHasCredential)
        assertFalse(keystoreHasCredential)
    }

    // ── 封印许可闸门：弱凭据禁用封印 ─────────────────────────────

    @Test
    fun `设备具备已录入的强生物识别时允许封印`() {
        assertTrue(UnlockAuthPolicy.canSeal(BiometricStatus.AVAILABLE))
    }

    @Test
    fun `无强生物识别硬件时禁用封印`() {
        assertFalse(UnlockAuthPolicy.canSeal(BiometricStatus.NO_HARDWARE))
    }

    @Test
    fun `强生物硬件暂不可用时禁用封印`() {
        assertFalse(UnlockAuthPolicy.canSeal(BiometricStatus.HARDWARE_UNAVAILABLE))
    }

    @Test
    fun `强生物识别未录入时禁用封印`() {
        assertFalse(UnlockAuthPolicy.canSeal(BiometricStatus.NOT_ENROLLED))
    }

    @Test
    fun `安全更新未完成时禁用封印`() {
        assertFalse(UnlockAuthPolicy.canSeal(BiometricStatus.SECURITY_UPDATE_REQUIRED))
    }
}
