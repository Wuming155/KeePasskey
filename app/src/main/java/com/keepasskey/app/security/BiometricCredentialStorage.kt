package com.keepasskey.app.security

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 经 Android Keystore 硬件加密的主凭据安全存储仓库。
 * 存储的内容为 AES-256-GCM 密文与对应的初始化向量 (IV)，即使设备 root 或读取明文 XML 也无法解密，
 * 必须通过硬件 TEE/StrongBox 结合活体生物特征认证授权方可解包。
 */
@Singleton
class BiometricCredentialStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 保存加密凭据及初始化向量 (IV)
     */
    fun saveEncryptedCredential(databaseId: String, iv: ByteArray, ciphertext: ByteArray) {
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit()
            .putString("${databaseId}_iv", ivB64)
            .putString("${databaseId}_cipher", cipherB64)
            .apply()
    }

    /**
     * 获取加密凭据及初始化向量 (IV)
     */
    fun getEncryptedCredential(databaseId: String): Pair<ByteArray, ByteArray>? {
        val ivB64 = prefs.getString("${databaseId}_iv", null) ?: return null
        val cipherB64 = prefs.getString("${databaseId}_cipher", null) ?: return null
        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(cipherB64, Base64.NO_WRAP)
            Pair(iv, ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查是否存在已配置的生物识别凭据
     */
    fun hasEncryptedCredential(databaseId: String): Boolean {
        return prefs.contains("${databaseId}_iv") && prefs.contains("${databaseId}_cipher")
    }

    /**
     * 清除特定数据库的生物凭据
     */
    fun clearCredential(databaseId: String) {
        prefs.edit()
            .remove("${databaseId}_iv")
            .remove("${databaseId}_cipher")
            .apply()
    }

    /**
     * 清除所有数据库的生物凭据
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "com.keepasskey.biometric_credentials"
    }
}
