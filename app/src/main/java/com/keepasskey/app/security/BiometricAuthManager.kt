package com.keepasskey.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 生物识别能力检测结果
 */
enum class BiometricStatus {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NOT_ENROLLED,
    SECURITY_UPDATE_REQUIRED
}

/**
 * 生物识别认证结果
 */
sealed interface BiometricResult {
    data class Success(val cipher: Cipher?) : BiometricResult

    /**
     * 认证失败结果。
     *
     * [errString] 是**内部诊断标识**——可能来自系统 [BiometricPrompt.AuthenticationCallback.onAuthenticationError]
     * 的错误描述透传，也可能是本工程定义的稳定英文诊断码（如
     * [BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC]）。它**仅**用于日志留痕与失败分型判据，
     * **任何一处都不得直接展示给用户**（ISSUE-P3-14）。
     *
     * 用户可见文案一律由消费侧按 [errorCode] 映射到已资源化字符串
     * （见 `com.keepasskey.app.ui.screens.unlock.BiometricFailureMessagePolicy`：
     * [BiometricAuthManager.ERROR_INTEGRITY_BLOCKED] → `R.string.sec_biometric_integrity_blocked`），
     * 从而保证中英双语一致，且文案不再随系统语言漂移。当前全部消费点（app 模块）：
     * - `UnlockViewModel.handleBiometricResult`：按错误码映射资源后展示，诊断串仅落日志；
     * - `UnlockViewModel.requestBiometricEnrollment`：登记失败为 fail-safe 静默语义，仅落日志；
     * - `passkey/CredentialVerificationLauncher`、`autofill/AutofillConfirmActivity`：丢弃该字段，
     *   仅按失败/取消分型改变控制流。
     */
    data class Error(val errorCode: Int, val errString: String) : BiometricResult

    data object Failed : BiometricResult
    data object Cancelled : BiometricResult
}

/**
 * AndroidX 生物识别管理器。
 * 封装系统级 BiometricPrompt，支持硬件 CryptoObject 对接、免输主密码解封主数据库。
 *
 * Wave 12 统一快速解锁语义（ISSUE-P1-08 收敛为「仅强生物识别」）：
 * - 认证器集合参数化；快速解锁统一使用 [UNLOCK_AUTHENTICATORS]
 *   （`BIOMETRIC_STRONG`，不含设备锁屏凭据——锁屏弱 PIN 会拉低解封门槛，
 *   且含 AUTH_DEVICE_CREDENTIAL 的密钥被系统忽略生物录入失效标志）：
 *   仅 Class 3 强生物识别可授权，
 *   与 KeystoreManager.REQUIRED_AUTHENTICATOR_TYPES（密钥生成侧授权集合）严格一致——
 *   官方硬性要求「解锁加密操作请求的认证器集合必须与密钥生成时一致」；
 * - 官方互斥约束：允许 DEVICE_CREDENTIAL 时系统以「使用锁屏凭据」入口取代负向按钮，
 *   此时调用 setNegativeButtonText 属于错误用法，本类强制规避；
 * - 纯解锁/封印场景默认免二次确认（confirmationRequired=false，仅影响生物识别路径）；
 * - ISSUE-P2-08（ZT-13）：设备运行完整性风险态下禁用生物快速解锁（fail-closed）——
 *   由 [RuntimeIntegrityGate] 注入裁决，风险态不弹生物识别、回落主密码路径。
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val runtimeIntegrityGate: RuntimeIntegrityGate
) {

    /**
     * 检查当前设备是否满足指定认证器集合的可用条件（默认 Class 3 强生物识别且已录入）
     */
    fun canAuthenticate(
        context: Context,
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG
    ): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SECURITY_UPDATE_REQUIRED
            else -> BiometricStatus.HARDWARE_UNAVAILABLE
        }
    }

    /**
     * 发起 BiometricPrompt 认证弹窗。
     *
     * @param authenticators 允许的认证器集合（位或组合）；与密钥生成侧授权集合保持一致
     * @param confirmationRequired 生物识别成功后是否需要二次确认（纯解锁场景传 false）
     * @param cipher 非空时以 CryptoObject 发起（授权后方可 doFinal）
     * @param negativeButtonText 负向按钮文案；仅当集合不含 DEVICE_CREDENTIAL 时生效
     *   （官方约束：设备凭据路径由系统入口取代负向按钮，二者互斥）
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String = "",
        authenticators: Int = UNLOCK_AUTHENTICATORS,
        confirmationRequired: Boolean = false,
        cipher: Cipher? = null,
        negativeButtonText: String? = null,
        onResult: (BiometricResult) -> Unit
    ) {
        // ISSUE-P2-08：完整性风险态（含扫描未完成的未判定态）禁用生物快速解锁，
        // 以显式失败结果回落主密码路径，绝不静默放行
        if (runtimeIntegrityGate.currentEnforcement().disableBiometricQuickUnlock) {
            onResult(BiometricResult.Error(ERROR_INTEGRITY_BLOCKED, INTEGRITY_BLOCKED_DIAGNOSTIC))
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val usesDeviceCredential =
            authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply {
                if (subtitle.isNotBlank()) {
                    setSubtitle(subtitle)
                }
                // 官方互斥约束：允许设备凭据时禁止再设置负向按钮
                if (!usesDeviceCredential && negativeButtonText != null) {
                    setNegativeButtonText(negativeButtonText)
                }
                setAllowedAuthenticators(authenticators)
                setConfirmationRequired(confirmationRequired)
            }
            .build()

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authenticatedCipher = result.cryptoObject?.cipher
                onResult(BiometricResult.Success(authenticatedCipher))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    onResult(BiometricResult.Cancelled)
                } else {
                    onResult(BiometricResult.Error(errorCode, errString.toString()))
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onResult(BiometricResult.Failed)
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        if (cipher != null) {
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } else {
            biometricPrompt.authenticate(promptInfo)
        }
    }

    /**
     * 为封印快速解锁凭据准备加密 Cipher（设备凭据绑定密钥）
     */
    fun prepareEncryptCipher(databaseId: String): Cipher {
        val alias = getAliasForDatabase(databaseId)
        return keystoreManager.initDeviceCredentialEncryptCipher(alias)
    }

    /**
     * 为解封凭据准备解密 Cipher（设备凭据绑定密钥）
     */
    fun prepareDecryptCipher(databaseId: String, iv: ByteArray): Cipher {
        val alias = getAliasForDatabase(databaseId)
        return keystoreManager.initDeviceCredentialDecryptCipher(iv, alias)
    }

    /**
     * 生成各数据库独立的硬件密钥别名
     */
    fun getAliasForDatabase(databaseId: String): String {
        return "${KeystoreManager.BIOMETRIC_KEY_ALIAS}_$databaseId"
    }

    /**
     * 删除特定数据库的硬件密钥别名
     */
    fun deleteKeyForDatabase(databaseId: String) {
        keystoreManager.deleteKey(getAliasForDatabase(databaseId))
    }

    companion object {
        /** ISSUE-P2-08：设备完整性风险导致生物快速解锁被禁用的结果码（区别于系统错误码） */
        const val ERROR_INTEGRITY_BLOCKED = -2

        /** ISSUE-P3-01/14：宿主 Activity 销毁等极端情形下系统不回调、登记侧超时熔断的结果码（区别于系统错误码） */
        const val ERROR_AUTH_TIMEOUT = -3

        /**
         * ISSUE-P3-14：完整性风险禁用生物快速解锁的**内部诊断标识**（稳定英文码，非用户可见文案）。
         *
         * 用户可见文案统一经消费侧按 [ERROR_INTEGRITY_BLOCKED] 映射到
         * `R.string.sec_biometric_integrity_blocked`（中英双语已具备），
         * 本类不再持有任何硬编码中文文案，失败语义与 UI 完全解耦。
         */
        const val INTEGRITY_BLOCKED_DIAGNOSTIC = "INTEGRITY_BLOCKED"

        /** ISSUE-P3-14：认证超时熔断的内部诊断标识（同 [INTEGRITY_BLOCKED_DIAGNOSTIC]，仅日志留痕，不对外展示） */
        const val AUTH_TIMEOUT_DIAGNOSTIC = "AUTH_TIMEOUT"

        /**
         * 快速解锁统一认证器集合：仅 Class 3 强生物识别。
         * ISSUE-P1-08：设备锁屏凭据（PIN/图案/密码）不再可解封（弱凭据降级 + 生物录入失效标志被忽略）。
         *
         * P1 整改：不再在本处独立书写位或表达式（原实现与密钥生成侧双写、靠注释维系），
         * 改由 [UnlockAuthPolicy] 统一声明语义后投影——两侧从此不可能漂移。
         */
        val UNLOCK_AUTHENTICATORS: Int get() = UnlockAuthPolicy.promptAuthenticators
    }
}
