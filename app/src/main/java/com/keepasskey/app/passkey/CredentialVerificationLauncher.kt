package com.keepasskey.app.passkey

import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
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
            biometricAuthManager.authenticate(
                activity = this,
                title = title,
                subtitle = biometricSubtitle,
                authenticators = BiometricAuthManager.UNLOCK_AUTHENTICATORS
            ) { result ->
                val verification = when (result) {
                    is BiometricResult.Success -> CredentialUserVerification.BiometricSucceeded
                    is BiometricResult.Cancelled -> CredentialUserVerification.BiometricCancelled
                    is BiometricResult.Failed -> CredentialUserVerification.BiometricFailed
                    is BiometricResult.Error -> CredentialUserVerification.BiometricFailed
                }
                if (fillVerifier.isSatisfied(requirement, verification)) {
                    onVerified(verification)
                } else {
                    onRejected()
                }
            }
        }

        CredentialFillRequirement.MANUAL_CONFIRMATION -> {
            setContent {
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
    }
}
