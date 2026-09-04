package com.keepasskey.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
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
 * 封装系统级 BiometricPrompt (BIOMETRIC_STRONG Class 3 强生物认证)，
 * 支持硬件 CryptoObject 对接、免输主密码解封主数据库、指纹变更自动失效检测。
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    private val keystoreManager: KeystoreManager
) {

    /**
     * 检查当前设备是否支持强生物识别（Class 3）且已录入指纹/面容
     */
    fun canAuthenticate(context: Context): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SECURITY_UPDATE_REQUIRED
            else -> BiometricStatus.HARDWARE_UNAVAILABLE
        }
    }

    /**
     * 发起强生物识别弹窗认证
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String = "",
        negativeButtonText: String = "使用主密码",
        cipher: Cipher? = null,
        onResult: (BiometricResult) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply {
                if (subtitle.isNotBlank()) {
                    setSubtitle(subtitle)
                }
            }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
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
     * 为保存凭据准备加密 Cipher
     */
    fun prepareEncryptCipher(databaseId: String): Cipher {
        val alias = getAliasForDatabase(databaseId)
        return keystoreManager.initEncryptCipher(alias)
    }

    /**
     * 为解封凭据准备解密 Cipher
     */
    fun prepareDecryptCipher(databaseId: String, iv: ByteArray): Cipher {
        val alias = getAliasForDatabase(databaseId)
        return keystoreManager.initDecryptCipher(iv, alias)
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
}
