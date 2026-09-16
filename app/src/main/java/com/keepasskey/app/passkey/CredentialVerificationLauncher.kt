package com.keepasskey.app.passkey

import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.security.AutofillAuthBindingPolicy
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult

/**
 * 在受保护窗口内请求一次「凭据下发 / Passkey 签发」的用户验证
 * （自 [PasswordFillActivity] 的门控实现抽取，供密码填充与 Passkey 断言 / 注册共用）。
 *
 * ISSUE-P0-03 (ZT-03) 语义：
 * - 设备具备可用强认证器 → 由 [BiometricAuthManager] 拉起系统级 BiometricPrompt；
 * - 设备无可用认证器 → 退化为受保护窗口内的显式手动确认（[CredentialFillConfirmScreen]），
 *   但**降级的是验证手段，绝不是免验证**——未产生任何可判定的用户意图前绝不回调 [onVerified]；
 * - 仅当 [CredentialFillVerifier.isSatisfied] 裁决通过才回调 [onVerified]，
 *   其结果可能是强验证（[CredentialUserVerification.BiometricSucceeded]）或
 *   手动确认（[CredentialUserVerification.ManualConfirmed]）——调用方需据此如实决定
 *   WebAuthn UV 位（参见 [PasskeyAuthFlags]），不得无条件置 `UV=1`；
 * - 取消 / 失败 / 硬件错误一律回调 [onRejected]，由调用方 fail-closed 结束流程。
 *
 * ## ISSUE-P2-76（威胁建模 Q-2）：CM 通道的验证升级为**密码学绑定**
 *
 * 原实现对本通道只做「回调级」验证（`BiometricResult.Success` 即放行），与自动填充通道
 * （Keystore `CryptoObject` 绑定 + [AutofillAuthBindingPolicy.isBound]）不同级——hook 可伪造回调。
 * 现复用自动填充已验证可行的同一机制：以 [BiometricAuthManager.prepareAutofillAuthCipher] 准备
 * 绑定 Cipher 并作为 `CryptoObject` 发起认证，**要求认证结果确带该 Cipher**（`isBound`）才放行；
 * 若设备无法建立绑定（Keystore 不可用，返回 null），**降级为受保护窗口内的显式手动确认**
 * （与自动填充通道同一退化策略），绝不回落到「无绑定的回调级放行」。
 *
 * 本函数需在主线程调用（内部 [setContent] 与系统验证回调均要求主线程）。
 */
internal fun FragmentActivity.requestCredentialUserVerification(
    biometricAuthManager: BiometricAuthManager,
    fillVerifier: CredentialFillVerifier,
    title: String,
    biometricSubtitle: String,
    manualHint: String,
    confirmText: String,
    cancelText: String,
    onVerified: (CredentialUserVerification) -> Unit,
    onRejected: () -> Unit
) {
    val status = biometricAuthManager.canAuthenticate(
        this,
        BiometricAuthManager.UNLOCK_AUTHENTICATORS
    )
    val requirement = fillVerifier.requirementFor(status)

    when (requirement) {
        CredentialFillRequirement.BIOMETRIC -> {
            // ISSUE-P2-76：先准备密码学绑定 Cipher；取不到即按既有退化策略走手动确认
            val authCipher = biometricAuthManager.prepareAutofillAuthCipher()
            if (authCipher == null) {
                showManualConfirmation(title, manualHint, confirmText, cancelText, onVerified, onRejected)
                return
            }
            biometricAuthManager.authenticate(
                activity = this,
                title = title,
                subtitle = biometricSubtitle,
                authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS,
                cipher = authCipher
            ) { result ->
                val verification = when (result) {
                    is BiometricResult.Success -> CredentialUserVerification.BiometricSucceeded
                    is BiometricResult.Cancelled -> CredentialUserVerification.BiometricCancelled
                    is BiometricResult.Failed -> CredentialUserVerification.BiometricFailed
                    is BiometricResult.Error -> CredentialUserVerification.BiometricFailed
                }
                // 双条件：验证结论满足要求 **且** 结果确带绑定 Cipher（无 CryptoObject 不放行）
                if (fillVerifier.isSatisfied(requirement, verification) &&
                    AutofillAuthBindingPolicy.isBound(result)
                ) {
                    onVerified(verification)
                } else {
                    onRejected()
                }
            }
        }

        CredentialFillRequirement.MANUAL_CONFIRMATION -> {
            showManualConfirmation(title, manualHint, confirmText, cancelText, onVerified, onRejected)
        }
    }
}

/** 受保护窗口内的显式手动确认（无可用强认证器 / 无法建立密码学绑定时的退化路径）。 */
private fun FragmentActivity.showManualConfirmation(
    title: String,
    manualHint: String,
    confirmText: String,
    cancelText: String,
    onVerified: (CredentialUserVerification) -> Unit,
    onRejected: () -> Unit
) {
    setContent {
        // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
        ApplyObscuredTouchFilter()
        CredentialFillConfirmScreen(
            title = title,
            hint = manualHint,
            confirmText = confirmText,
            cancelText = cancelText,
            onConfirm = {
                onVerified(CredentialUserVerification.ManualConfirmed)
            },
            onCancel = onRejected
        )
    }
}
