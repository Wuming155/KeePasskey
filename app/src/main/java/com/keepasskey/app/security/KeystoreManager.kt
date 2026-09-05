package com.keepasskey.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Android Keystore 硬件安全密钥管理器。
 * 提供基于硬件 TEE / StrongBox 隔离的 AES-256-GCM 凭据封印与解封能力。
 * 遵循安全规范：
 * - 生物识别密钥启用 setUserAuthenticationRequired 要求 Class 3 强生物识别验证（per-operation 认证，
 *   无时间有效期，配合 BiometricPrompt CryptoObject 逐次授权）；
 * - 启用 setInvalidatedByBiometricEnrollment(true)，当系统录入新指纹或清空指纹时自动吊销硬件密钥，防范物理设备攻击；
 * - 支持 StrongBox 安全元件（FEATURE_STRONGBOX_KEYSTORE），不可用时自动回退 TEE；
 * - QuickUnlock 封印密钥不绑定用户认证（PIN 校验器是前置门槛 + 硬件密钥不可导出承担离线防护），
 *   旧版误生成的认证绑定密钥经 KeyInfo 探测后自动迁移重建。
 */
@Singleton
class KeystoreManager @Inject constructor(
    // 允许为 null 仅用于单测注入空实现；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val context: Context?
) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    private val isStrongBoxSupported: Boolean by lazy {
        context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) == true
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
     * 生成全新的硬件隔离对称密钥。
     * 设备具备 StrongBox 安全元件时优先落 StrongBox，元件不可用自动回退 TEE（对齐 KeePassDX DeviceUnlockManager）。
     */
    @Synchronized
    fun generateNewKey(
        alias: String = BIOMETRIC_KEY_ALIAS,
        requireUserAuth: Boolean = true,
        invalidateOnBiometricEnrollment: Boolean = true
    ): SecretKey {
        if (isStrongBoxSupported) {
            try {
                return generateKeyInternal(alias, requireUserAuth, invalidateOnBiometricEnrollment, strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // StrongBox 缺席或临时繁忙：回退 TEE 生成
            }
        }
        return generateKeyInternal(alias, requireUserAuth, invalidateOnBiometricEnrollment, strongBox = false)
    }

    private fun generateKeyInternal(
        alias: String,
        requireUserAuth: Boolean,
        invalidateOnBiometricEnrollment: Boolean,
        strongBox: Boolean
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

        if (requireUserAuth) {
            // API 30+ 显式约束为 Class 3 强生物识别验证（per-operation，不接受锁屏密码/PIN/图案降级替代）
            specBuilder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }

        if (strongBox) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    /**
     * 获取或生成不绑定用户认证的硬件密钥（QuickUnlock 主凭据封印专用）。
     *
     * 认证语义说明：QuickUnlock 的安全门槛是 PIN 校验器（PBKDF2）+ 硬件密钥不可导出；
     * 该密钥若绑定 per-operation 用户认证，则无 BiometricPrompt CryptoObject 的纯 PIN 封印/解封
     * 在真机上必然抛 UserNotAuthenticatedException（历史缺陷），故此别名必须以非认证密钥存在。
     * 旧版本生成的认证绑定密钥经 [KeyInfo] 探测后自动删除重建——旧密文随之失效，
     * 用户下次以主密码完整解锁后重新封印（fail-safe 迁移）。
     */
    fun getOrCreateUnauthenticatedKey(alias: String): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                val isAuthBound = try {
                    val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                    factory.getKeySpec(entry.secretKey, KeyInfo::class.java).isUserAuthenticationRequired
                } catch (_: Exception) {
                    // 规格探测失败按认证绑定处理，触发迁移重建
                    true
                }
                if (!isAuthBound) {
                    return entry.secretKey
                }
                deleteKey(alias)
            }
        }
        return generateNewKey(alias, requireUserAuth = false, invalidateOnBiometricEnrollment = false)
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
     * 初始化 QuickUnlock 主凭据封印（加密）Cipher——使用不绑定用户认证的硬件密钥
     */
    fun initSealCipher(alias: String): Cipher {
        val key = getOrCreateUnauthenticatedKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * 初始化 QuickUnlock 主凭据解封（解密）Cipher
     */
    fun initUnsealCipher(iv: ByteArray, alias: String): Cipher {
        val key = getOrCreateUnauthenticatedKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher
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
