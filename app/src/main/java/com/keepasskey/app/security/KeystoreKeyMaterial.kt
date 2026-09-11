package com.keepasskey.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.keepasskey.app.data.logger.DebugLogBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore 密钥材料底层协作者（ISSUE-P3-29 纯结构性拆分，无行为变更）。
 *
 * 承接 [KeystoreManager] 的密钥生成、硬件落位探测、旧密钥轮换迁移与 Cipher/Mac 初始化等
 * **实现细节**，使公开门面 [KeystoreManager] 仅保留 public API 与委托。本类为同包 `internal`
 * 可见性，不对外暴露、不构成任何新增公开 API。
 *
 * 安全语义与原 [KeystoreManager] 实现逐字一致：密钥别名、`KeyGenParameterSpec` 各项、
 * StrongBox 回退、`KeyPermanentlyInvalidatedException` 清理判据、`KeyInfo` 探测与轮换重建
 * 判据均原样承载；敏感数据不落地为 `String`，日志仅含别名与异常类型。
 *
 * 迁移/失效路径中的密钥删除经 [onDeleteKey] 回调至 [KeystoreManager.deleteKey]（`@Synchronized`），
 * 保持原实现「删除操作在 `KeystoreManager` 监视器下串行」的同步语义不变。
 */
internal class KeystoreKeyMaterial(
    private val context: Context?,
    private val debugLog: DebugLogBuffer?,
    private val onDeleteKey: (String) -> Unit
) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KeystoreManager.ANDROID_KEY_STORE).apply {
            load(null)
        }
    }

    private val isStrongBoxSupported: Boolean by lazy {
        context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) == true
    }

    /** [KeystoreManager.getOrCreateKey] 实现：命中返回既有密钥，否则按参数生成。 */
    fun getOrCreateAesKey(
        alias: String,
        requireUserAuth: Boolean,
        invalidateOnBiometricEnrollment: Boolean
    ): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }
        return generateNewAesKey(alias, requireUserAuth, invalidateOnBiometricEnrollment)
    }

    /** [KeystoreManager.generateNewKey] 实现：StrongBox 优先，不可用回退 TEE。 */
    fun generateNewAesKey(
        alias: String,
        requireUserAuth: Boolean,
        invalidateOnBiometricEnrollment: Boolean
    ): SecretKey {
        if (isStrongBoxSupported) {
            try {
                return generateAesKey(alias, requireUserAuth, invalidateOnBiometricEnrollment, strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // StrongBox 缺席或临时繁忙：回退 TEE 生成
            }
        }
        return generateAesKey(alias, requireUserAuth, invalidateOnBiometricEnrollment, strongBox = false)
    }

    private fun generateAesKey(
        alias: String,
        requireUserAuth: Boolean,
        invalidateOnBiometricEnrollment: Boolean,
        strongBox: Boolean,
        authenticatorTypes: Int = KeyProperties.AUTH_BIOMETRIC_STRONG,
        unlockedDeviceRequired: Boolean = requireUserAuth
    ): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KeystoreManager.ANDROID_KEY_STORE
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
            .setUnlockedDeviceRequired(unlockedDeviceRequired)

        if (requireUserAuth) {
            // per-operation 授权（timeout=0）：认证器集合由 authenticatorTypes 决定——
            // 纯生物识别密钥用 AUTH_BIOMETRIC_STRONG；快速解锁密钥用 AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL
            // （后者允许设备锁屏 PIN/图案/密码作为强认证授权来源，官方推荐的凭据绑定方式）
            specBuilder.setUserAuthenticationParameters(0, authenticatorTypes)
        }

        if (strongBox) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(specBuilder.build())
        val key = keyGenerator.generateKey()
        // 生成即校验硬件落位：诊断性告警（不硬失败，兼容模拟器/CI 的软件 Keystore）
        val level = probeSecurityLevel(alias)
        if (level == KeyProperties.SECURITY_LEVEL_SOFTWARE) {
            debugLog?.warn(
                TAG,
                "密钥 $alias 落位软件 Keystore（非 TEE/StrongBox），硬件隔离未生效"
            )
        }
        return key
    }

    /**
     * 查询指定别名的密钥实际硬件落位等级（原始 `KeyInfo.securityLevel`）。
     * 密钥不存在或查询异常时返回 [KeyProperties.SECURITY_LEVEL_UNKNOWN]。
     */
    fun probeSecurityLevel(alias: String): Int {
        return try {
            if (!keyStore.containsAlias(alias)) return KeyProperties.SECURITY_LEVEL_UNKNOWN
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
                ?: return KeyProperties.SECURITY_LEVEL_UNKNOWN
            val factory = KeyFactory.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KeystoreManager.ANDROID_KEY_STORE
            )
            val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java)
            info.securityLevel
        } catch (_: Exception) {
            KeyProperties.SECURITY_LEVEL_UNKNOWN
        }
    }

    /** [KeystoreManager.deleteKey] 的底层实现：删除指定密钥别名（不存在则无操作）。 */
    fun deleteKeyEntry(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    /** [KeystoreManager.getOrCreateDeviceCredentialKey] 实现（含 ISSUE-P1-08 全等探测迁移）。 */
    fun getOrCreateDeviceCredentialKey(alias: String): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                val matchesRequirement = try {
                    val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreManager.ANDROID_KEY_STORE)
                    val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java)
                    // ISSUE-P1-08：全等比较（而非位包含）——旧「BIOMETRIC_STRONG | DEVICE_CREDENTIAL」密钥
                    // 虽含所需位但允许锁屏凭据解封，必须判定为不匹配并迁移重建为纯生物识别密钥
                    info.isUserAuthenticationRequired &&
                        info.userAuthenticationType == KeystoreManager.REQUIRED_AUTHENTICATOR_TYPES
                } catch (_: Exception) {
                    // 规格探测失败按不匹配处理，触发迁移重建（fail-safe）
                    false
                }
                if (matchesRequirement) {
                    return entry.secretKey
                }
                onDeleteKey(alias)
            }
        }
        return generateNewDeviceCredentialKey(alias)
    }

    /** [KeystoreManager.generateNewDeviceCredentialKey] 实现：StrongBox 优先，回退 TEE。 */
    fun generateNewDeviceCredentialKey(alias: String): SecretKey {
        if (isStrongBoxSupported) {
            try {
                return generateAesKey(
                    alias,
                    requireUserAuth = true,
                    invalidateOnBiometricEnrollment = true,
                    strongBox = true,
                    authenticatorTypes = KeystoreManager.REQUIRED_AUTHENTICATOR_TYPES
                )
            } catch (_: StrongBoxUnavailableException) {
                // StrongBox 缺席或临时繁忙：回退 TEE 生成
            }
        }
        return generateAesKey(
            alias,
            requireUserAuth = true,
            invalidateOnBiometricEnrollment = true,
            strongBox = false,
            authenticatorTypes = KeystoreManager.REQUIRED_AUTHENTICATOR_TYPES
        )
    }

    /** [KeystoreManager.initDeviceCredentialEncryptCipher] 实现。 */
    fun initDeviceCredentialEncryptCipher(alias: String): Cipher {
        val key = getOrCreateDeviceCredentialKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * [KeystoreManager.initAutofillAuthCipher] 实现（ISSUE-P3-52）。
     *
     * 复用与快速解锁一致的「纯强生物识别绑定」密钥规格：该 Cipher **仅用于** BiometricPrompt 的
     * `CryptoObject` 绑定（证明本次放行操作经强生物识别授权），**不执行 doFinal**。
     */
    fun initAutofillAuthCipher(): Cipher {
        val key = getOrCreateDeviceCredentialKey(KeystoreManager.AUTOFILL_AUTH_KEY_ALIAS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** [KeystoreManager.initDeviceCredentialDecryptCipher] 实现（含失效密钥清理）。 */
    fun initDeviceCredentialDecryptCipher(
        iv: ByteArray,
        alias: String
    ): Cipher {
        try {
            val key = getOrCreateDeviceCredentialKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            // 系统指纹增删导致密钥永久失效，立即移除已废弃的密钥别名
            onDeleteKey(alias)
            throw e
        }
    }

    /** [KeystoreManager.getOrCreateUnlockPasskeyPair] 实现（含旧规范密钥轮换重建）。 */
    fun getOrCreateUnlockPasskeyPair(alias: String): KeyPair? {
        try {
            if (keyStore.containsAlias(alias)) {
                val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    if (isAuthBoundUnlockPasskey(entry)) {
                        return KeyPair(entry.certificate.publicKey, entry.privateKey)
                    }
                    // 旧规范密钥（未绑定用户认证）：轮换重建，登记记录随之失效（fail-closed 重登记）
                    debugLog?.warn(TAG, "检测到未绑定用户认证的旧解锁通行密钥，执行轮换重建")
                    keyStore.deleteEntry(alias)
                }
            }
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KeystoreManager.ANDROID_KEY_STORE
            )
            val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            if (isStrongBoxSupported) {
                try {
                    generator.initialize(buildUnlockPasskeySpec(alias, purposes, strongBox = true))
                    return generator.generateKeyPair()
                } catch (_: StrongBoxUnavailableException) {
                    // StrongBox 缺席：回退 TEE 生成
                }
            }
            generator.initialize(buildUnlockPasskeySpec(alias, purposes, strongBox = false))
            return generator.generateKeyPair()
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥生成/读取失败: ${e.javaClass.simpleName} - ${e.message}")
            return null
        }
    }

    /** 判断存量私钥是否已按 ISSUE-P1-09 规范绑定用户认证（时间窗 > 0） */
    private fun isAuthBoundUnlockPasskey(entry: KeyStore.PrivateKeyEntry): Boolean {
        return try {
            val factory = KeyFactory.getInstance(entry.privateKey.algorithm, KeystoreManager.ANDROID_KEY_STORE)
            val keyInfo = factory.getKeySpec(entry.privateKey, KeyInfo::class.java)
            keyInfo.isUserAuthenticationRequired &&
                keyInfo.userAuthenticationValidityDurationSeconds > 0
        } catch (e: Exception) {
            // 特性探测失败按未绑定处理（fail-closed：宁可轮换，不可放行无认证密钥）
            debugLog?.warn(TAG, "解锁通行密钥认证绑定探测失败: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun buildUnlockPasskeySpec(alias: String, purposes: Int, strongBox: Boolean): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(alias, purposes)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationParameters(
                KeystoreManager.UNLOCK_PASSKEY_AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
            .setUnlockedDeviceRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
    }

    /** [KeystoreManager.getOrCreateUnlockPasskeyIntegrityMac] 实现。 */
    fun getOrCreateUnlockPasskeyIntegrityMac(): Mac? {
        return try {
            val alias = KeystoreManager.UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS
            if (!keyStore.containsAlias(alias)) {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    KeystoreManager.ANDROID_KEY_STORE
                )
                generator.init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setKeySize(KEY_SIZE_BITS)
                        .build()
                )
                generator.generateKey()
            }
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry ?: return null
            Mac.getInstance("HmacSHA256").apply { init(entry.secretKey) }
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥完整性 HMAC 密钥获取失败: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val TAG = "KeystoreManager"
    }
}
