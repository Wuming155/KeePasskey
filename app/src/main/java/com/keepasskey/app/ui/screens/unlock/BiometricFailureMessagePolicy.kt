package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
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
 * ISSUE-P2-355 AC④：错误码按档分流——lockout（锁定类）/ hardware（硬件类）/ timeout（超时类）
 * / 其他（策略兜底）。用户因此能区分「可稍后重试」与「须改用主密码」，不再全归一为同一句。
 * 分档常量与 `androidx.biometric.BiometricPrompt.ERROR_*` 逐位对齐；刻意**不直接 import**
 * Android 类——本对象保持纯 JVM（无 Android 依赖）可单测（类加载面与既有 KDoc 承诺不变）。
 *
 * 非 Composable、无 Android 依赖的纯映射，可直接单测（含「同一错误码 + 任意诊断串 → 同一文案」断言，
 * 用于锁死「文案不再取自 errString」这一契约）。
 */
internal object BiometricFailureMessagePolicy {

    /** `BiometricPrompt.ERROR_HW_UNAVAILABLE`：硬件不可用 */
    private const val ERROR_HW_UNAVAILABLE = 1

    /** `BiometricPrompt.ERROR_TIMEOUT`：认证超时 */
    private const val ERROR_TIMEOUT = 3

    /** `BiometricPrompt.ERROR_LOCKOUT`：临时锁定（失败次数过多） */
    private const val ERROR_LOCKOUT = 7

    /** `BiometricPrompt.ERROR_LOCKOUT_PERMANENT`：永久锁定（需重录生物特征） */
    private const val ERROR_LOCKOUT_PERMANENT = 9

    /** `BiometricPrompt.ERROR_NO_BIOMETRICS`：本机未录入生物特征（硬件/录入面） */
    private const val ERROR_NO_BIOMETRICS = 11

    /** `BiometricPrompt.ERROR_HW_NOT_PRESENT`：设备无生物识别硬件 */
    private const val ERROR_HW_NOT_PRESENT = 12

    /**
     * 映射失败结果到用户可见文案（按 [BiometricResult.Error.errorCode] 分档）。
     *
     * - lockout 档（[ERROR_LOCKOUT] / [ERROR_LOCKOUT_PERMANENT]）→ [R.string.sec_biometric_lockout]；
     * - hardware 档（[ERROR_HW_UNAVAILABLE] / [ERROR_HW_NOT_PRESENT] / [ERROR_NO_BIOMETRICS]）→
     *   [R.string.sec_biometric_hardware]；
     * - timeout 档（[ERROR_TIMEOUT]）→ [R.string.sec_biometric_timeout]；
     * - 其他（策略兜底）→ [R.string.sec_biometric_auth_failed]。
     */
    fun of(error: BiometricResult.Error): UiMessage = when (error.errorCode) {
        ERROR_LOCKOUT, ERROR_LOCKOUT_PERMANENT -> UiMessage(R.string.sec_biometric_lockout)
        ERROR_HW_UNAVAILABLE, ERROR_HW_NOT_PRESENT, ERROR_NO_BIOMETRICS ->
            UiMessage(R.string.sec_biometric_hardware)
        ERROR_TIMEOUT -> UiMessage(R.string.sec_biometric_timeout)
        else -> UiMessage(R.string.sec_biometric_auth_failed)
    }
}
