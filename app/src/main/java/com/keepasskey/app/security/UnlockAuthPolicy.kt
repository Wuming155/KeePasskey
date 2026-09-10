package com.keepasskey.app.security

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager

/**
 * 快速解锁认证策略：认证器集合的**唯一语义声明点**。
 *
 * ISSUE-P1-08 整改：认证器集合收敛为「**仅强生物识别**」（不含 DEVICE_CREDENTIAL）。
 * 原方案「BIOMETRIC_STRONG | DEVICE_CREDENTIAL」存在两条同源安全缺陷：
 * 1. 锁屏为 4/6 位 PIN 时，解封门槛由 Class 3 生物识别退化为弱 PIN——攻破锁屏即等同获得主密码
 *    （封存内容即主密码 UTF-8 明文），违背 OWASP MASVS-AUTH-8 认证强度要求；
 * 2. 含 `AUTH_DEVICE_CREDENTIAL` 的密钥被系统**忽略** `setInvalidatedByBiometricEnrollment` 标志
 *    （官方语义：锁屏凭据变更不触发失效）——任何人新增自己的指纹后既有封印凭据照常可用。
 * 收敛为仅强生物识别后：弱 PIN 无法解封，且录入变更即自动吊销密钥（KeyPermanentlyInvalidated）。
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

    /** ISSUE-P1-08：禁止设备锁屏凭据（PIN / 图案 / 密码）作为解封路径（弱凭据降级 + 失效语义缺陷，见类 KDoc） */
    private const val USE_DEVICE_CREDENTIAL = false

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

    /**
     * 封印许可闸门（ISSUE-P1-08 验收项 2）：仅当设备具备「硬件存在且已录入」的 Class 3 强生物识别时
     * 方可封印主密码凭据。设备无强生物识别（含未录入）时快速解锁没有可用的强认证路径——
     * 禁用封印（fail-closed），而非降级到弱锁屏凭据。
     */
    fun canSeal(strongBiometricStatus: BiometricStatus): Boolean =
        strongBiometricStatus == BiometricStatus.AVAILABLE
}
