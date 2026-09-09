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
    data class Error(val errorCode: Int, val errString: String) : BiometricResult
    data object Failed : BiometricResult
    data object Cancelled : BiometricResult
}

/**
 * AndroidX 生物识别管理器。
 * 封装系统级 BiometricPrompt，支持硬件 CryptoObject 对接、免输主密码解封主数据库。
 *
 * Wave 12 统一快速解锁语义：
 * - 认证器集合参数化；快速解锁统一使用 [UNLOCK_AUTHENTICATORS]
 *   （`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`）：强生物识别或设备锁屏凭据（PIN/图案/密码）任一即可授权，
 *   与 KeystoreManager.REQUIRED_AUTHENTICATOR_TYPES（密钥生成侧授权集合）严格一致——
 *   官方硬性要求「解锁加密操作请求的认证器集合必须与密钥生成时一致」；
 * - 官方互斥约束：允许 DEVICE_CREDENTIAL 时系统以「使用锁屏凭据」入口取代负向按钮，
 *   此时调用 setNegativeButtonText 属于错误用法，本类强制规避；
 * - 纯解锁/封印场景默认免二次确认（confirmationRequired=false，仅影响生物识别路径）。
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    private val keystoreManager: KeystoreManager
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
        /**
         * 快速解锁统一认证器集合：强生物识别 或 设备锁屏凭据（PIN/图案/密码）。
         *
         * P1 整改：不再在本处独立书写位或表达式（原实现与密钥生成侧双写、靠注释维系），
         * 改由 [UnlockAuthPolicy] 统一声明语义后投影——两侧从此不可能漂移。
         */
        val UNLOCK_AUTHENTICATORS: Int get() = UnlockAuthPolicy.promptAuthenticators
    }
}
