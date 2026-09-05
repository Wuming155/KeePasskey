package com.keepasskey.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore 硬件安全密钥管理器。
 * 提供基于硬件 TEE / StrongBox 隔离的 AES-256-GCM 凭据封印与解封能力。
 * 遵循安全规范：
 * - 启用 setUserAuthenticationRequired 要求 Class 3 强生物识别验证；
 * - 启用 setInvalidatedByBiometricEnrollment(true)，当系统录入新指纹或清空指纹时自动吊销硬件密钥，防范物理设备攻击。
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    /**
     * 获取或生成用于生物识别保护的 AES-256-GCM 硬件密钥
     */
    @Synchronized
    fun getOrCreateKey(
        alias: String = BIOMETRIC_KEY_ALIAS,
        requireUserAuth: Boolean = true,
        invalidateOnBiometricEnrollment: Boolean = true
    ): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }
        return generateNewKey(alias, requireUserAuth, invalidateOnBiometricEnrollment)
    }

    /**
     * 生成全新的硬件隔离对称密钥
     */
    @Synchronized
    fun generateNewKey(
        alias: String = BIOMETRIC_KEY_ALIAS,
        requireUserAuth: Boolean = true,
        invalidateOnBiometricEnrollment: Boolean = true
    ): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(requireUserAuth)
            .setInvalidatedByBiometricEnrollment(invalidateOnBiometricEnrollment)

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    /**
     * 初始化用于加密凭据的 Cipher（供传递给 BiometricPrompt.CryptoObject 或直接加密）
     */
    fun initEncryptCipher(alias: String = BIOMETRIC_KEY_ALIAS): Cipher {
        val key = getOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * 初始化用于解密凭据的 Cipher
     * 若检测到密钥已因生物识别特征变更而失效（KeyPermanentlyInvalidatedException），自动清除脏密钥并抛出异常
     */
    fun initDecryptCipher(
        iv: ByteArray,
        alias: String = BIOMETRIC_KEY_ALIAS
    ): Cipher {
        try {
            val key = getOrCreateKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            // 系统指纹增删导致密钥永久失效，立即移除已废弃的密钥别名
            deleteKey(alias)
            throw e
        }
    }

    /**
     * 执行实际数据加密
     */
    fun encryptData(cipher: Cipher, plaintext: ByteArray): ByteArray {
        return cipher.doFinal(plaintext)
    }

    /**
     * 执行实际数据解密
     */
    fun decryptData(cipher: Cipher, ciphertext: ByteArray): ByteArray {
        return cipher.doFinal(ciphertext)
    }

    /**
     * 检查密钥别名是否存在
     */
    fun containsKey(alias: String = BIOMETRIC_KEY_ALIAS): Boolean {
        return keyStore.containsAlias(alias)
    }

    /**
     * 删除指定的密钥别名
     */
    @Synchronized
    fun deleteKey(alias: String = BIOMETRIC_KEY_ALIAS) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val BIOMETRIC_KEY_ALIAS = "com.keepasskey.biometric_master_key"
        const val QUICK_UNLOCK_KEY_ALIAS = "com.keepasskey.quick_unlock_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
