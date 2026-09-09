package com.keepasskey.app.passkey

import com.keepasskey.app.security.BiometricStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据下发前的用户验证要求等级。
 *
 * - [BIOMETRIC]：设备具备可用认证器（强生物识别或锁屏凭据），要求系统级 BiometricPrompt 通过；
 * - [MANUAL_CONFIRMATION]：设备无可用认证器，退化为受保护窗口内的显式手动点选确认
 *   （与 Autofill 通道 [com.keepasskey.app.autofill.AutofillConfirmActivity] 的回退策略一致）。
 */
enum class CredentialFillRequirement {
    BIOMETRIC,
    MANUAL_CONFIRMATION
}

/**
 * 一次凭据下发过程中**实际发生**的用户验证结果。
 *
 * [NONE] 表示尚未发生任何验证（例如 Activity 被绕过直接触达下发路径），
 * 是必须被拦截的默认态——门控判定的出发点是「未证明即拒绝」。
 */
sealed interface CredentialUserVerification {
    /** 未发生任何验证 */
    data object None : CredentialUserVerification

    /** 系统级生物识别/锁屏凭据认证通过 */
    data object BiometricSucceeded : CredentialUserVerification

    /** 系统级认证失败（多次识别不通过、硬件错误等） */
    data object BiometricFailed : CredentialUserVerification

    /** 用户主动取消系统级认证 */
    data object BiometricCancelled : CredentialUserVerification

    /** 用户在受保护窗口内手动点选确认 */
    data object ManualConfirmed : CredentialUserVerification

    /** 用户在受保护窗口内手动取消 */
    data object ManualCancelled : CredentialUserVerification
}

/**
 * Credential Manager 凭据下发的用户验证门控内核（ISSUE-P0-02 / ZT-02）。
 *
 * 背景：此前 [CredentialResponseAssembler] 构造 `PasswordCredentialEntry` 时未挂
 * `BiometricPromptData`，且 [PasswordFillActivity] 自身不做任何确认——密码库一旦处于解锁态，
 * 任意调起 Credential Manager 的应用即可在**用户零交互**下取得明文密码。
 *
 * 门控形态的选择依据（为何不在 entry 上挂 `BiometricPromptData` 了事）：
 * 当前依赖的 `androidx.credentials:1.6.0` 中 `BiometricPromptData` 标注为
 * `@RestrictTo(LIBRARY)`，`PendingIntentHandler` **未提供** `BiometricPromptResult` 的读取入口，
 * 提供方 Activity 无法判定系统门控究竟是成功、失败还是被绕过——挂上即得「看起来已验证」
 * 的假象，无法闭环。因此改为在填充 Activity 内**自持**门控：验证结果可判定、可记录、
 * 可被纯 JVM 单测覆盖，且任何未证明路径一律 fail-closed。
 *
 * 本类刻意保持为无 Android 依赖的纯 Kotlin 决策核，便于单元测试直接断言
 * 「无确认路径不得放行」这一安全不变式。
 */
@Singleton
class CredentialFillVerifier @Inject constructor() {

    /**
     * 依据设备认证器可用状态决定本次下发所要求的验证等级。
     *
     * 任何非 [BiometricStatus.AVAILABLE] 状态（无硬件、硬件不可用、未录入、需安全更新）
     * 一律降级为 [CredentialFillRequirement.MANUAL_CONFIRMATION]——降级的是**验证手段**，
     * 绝不是「免验证」：两条路径都必须产生可判定的用户意图后才允许下发。
     */
    fun requirementFor(biometricStatus: BiometricStatus): CredentialFillRequirement =
        if (biometricStatus == BiometricStatus.AVAILABLE) {
            CredentialFillRequirement.BIOMETRIC
        } else {
            CredentialFillRequirement.MANUAL_CONFIRMATION
        }

    /**
     * 判定实际发生的验证结果是否满足所要求的等级。
     *
     * fail-closed 语义：除显式成功外的任何结果（未验证 / 失败 / 取消 / 手段与要求不匹配）
     * 一律返回 false。特别地——要求生物识别时手动确认**不予放行**，防止要求等级被绕过降级。
     */
    fun isSatisfied(
        requirement: CredentialFillRequirement,
        verification: CredentialUserVerification
    ): Boolean = when (requirement) {
        CredentialFillRequirement.BIOMETRIC ->
            verification == CredentialUserVerification.BiometricSucceeded

        // 手动确认要求下，更强的生物识别通过同样视为满足（不因手段更强而拒绝）
        CredentialFillRequirement.MANUAL_CONFIRMATION ->
            verification == CredentialUserVerification.ManualConfirmed ||
                verification == CredentialUserVerification.BiometricSucceeded
    }
}
