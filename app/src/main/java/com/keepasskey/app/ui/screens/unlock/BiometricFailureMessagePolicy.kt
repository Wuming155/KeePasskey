package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.ui.model.UiMessage

/**
 * 生物识别失败结果 → 用户可见文案的映射策略（ISSUE-P3-14）。
 *
 * 职责边界（同时满足「硬编码文案资源化」与「失败语义与 UI 解耦」两条要求）：
 * - [BiometricResult.Error.errString] 是**内部诊断标识**（系统错误描述透传，或本工程定义的稳定英文
 *   诊断码），只用于日志留痕与失败分型判据，**任何一处都不得直接展示给用户**；
 * - 用户可见文案一律在此按 [BiometricResult.Error.errorCode] 映射到已资源化的字符串
 *   （`values/strings.xml` + `values-en/strings.xml` 中英双语同时具备），
 *   由 UI 层经 [com.keepasskey.app.ui.model.resolveText] 用当前语言解析。
 *
 * 非 Composable、无 Android 依赖的纯映射，可直接单测（含「同一错误码 + 任意诊断串 → 同一文案」断言，
 * 用于锁死「文案不再取自 errString」这一契约）。
 */
internal object BiometricFailureMessagePolicy {

    /**
     * 映射失败结果到用户可见文案。
     *
     * 完整性风险态（ISSUE-P2-08 闸门 fail-closed）使用专属提示 [R.string.sec_biometric_integrity_blocked]，
     * 其余系统错误统一为通用失败提示 [R.string.sec_biometric_auth_failed]。
     */
    fun of(error: BiometricResult.Error): UiMessage = when (error.errorCode) {
        BiometricAuthManager.ERROR_INTEGRITY_BLOCKED -> UiMessage(R.string.sec_biometric_integrity_blocked)
        else -> UiMessage(R.string.sec_biometric_auth_failed)
    }
}
