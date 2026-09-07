package com.keepasskey.app.security

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager

/**
 * 快速解锁认证策略：认证器集合的**唯一语义声明点**。
 *
 * P1 整改背景：所需认证器此前在两处各写一份位或常量——
 * [KeystoreManager.REQUIRED_AUTHENTICATOR_TYPES]（密钥生成侧，喂给 KeyGenParameterSpec）
 * 与 [BiometricAuthManager.UNLOCK_AUTHENTICATORS]（认证请求侧，喂给 BiometricPrompt），
 * 仅靠注释维系一致性。官方对 crypto-based 认证有硬性要求：
 * > If unlocking cryptographic operation(s), it is the application's responsibility to request
 * > authentication with the proper set of authenticators (e.g. match the authenticators specified
 * > during key generation).
 * 一旦两侧漂移，轻则静默降级，重则 `IllegalArgumentException` 崩溃。
 *
 * 为什么不直接把一侧常量赋给另一侧：
 * `KeyProperties.AUTH_*` 与 `BiometricManager.Authenticators.*` 是**数值互不相同**的两套位掩码，
 * 分属 Keystore 与 framework BiometricPrompt 两个不同接受方，跨枚举直接赋值会得到无意义的数值。
 * 因此本策略只统一「语义」，再各自投影为对应枚举的合法位掩码。
 */
internal object UnlockAuthPolicy {

    /** 是否需要 Class 3（Strong）生物识别 */
    private const val USE_BIOMETRIC_STRONG = true

    /** 是否允许设备锁屏凭据（PIN / 图案 / 密码）作为回退路径 */
    private const val USE_DEVICE_CREDENTIAL = true

    /**
     * 密钥生成侧授权集合（KeyGenParameterSpec.setUserAuthenticationParameters 第二参数）
     */
    val keystoreAuthTypes: Int = buildMask(
        biometricStrong = KeyProperties.AUTH_BIOMETRIC_STRONG.takeIf { USE_BIOMETRIC_STRONG },
        deviceCredential = KeyProperties.AUTH_DEVICE_CREDENTIAL.takeIf { USE_DEVICE_CREDENTIAL }
    )

    /**
     * 认证请求侧认证器集合（BiometricPrompt.PromptInfo.setAllowedAuthenticators）
     */
    val promptAuthenticators: Int = buildMask(
        biometricStrong = BiometricManager.Authenticators.BIOMETRIC_STRONG.takeIf { USE_BIOMETRIC_STRONG },
        deviceCredential = BiometricManager.Authenticators.DEVICE_CREDENTIAL.takeIf { USE_DEVICE_CREDENTIAL }
    )

    private fun buildMask(biometricStrong: Int?, deviceCredential: Int?): Int =
        listOfNotNull(biometricStrong, deviceCredential).fold(0) { acc, flag -> acc or flag }
}
