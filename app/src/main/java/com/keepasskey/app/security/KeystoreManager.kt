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
 * 遵循安全规范（对齐 Android 官方 Keystore 文档）：
 * - 生物识别密钥启用 setUserAuthenticationRequired 要求 Class 3 强生物识别验证（per-operation 认证，
 *   无时间有效期，配合 BiometricPrompt CryptoObject 逐次授权）；
 * - 纯生物识别密钥启用 setInvalidatedByBiometricEnrollment(true)，系统录入/清空指纹时自动吊销密钥，防范物理设备攻击；
 * - Wave 12：快速解锁密钥改为「强生物识别 或 设备锁屏凭据」双重授权绑定
 *   （setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)），
 *   取代旧版「非认证密钥 + 应用内 PIN 校验器」方案——爆破门槛从自选应用 PIN 提升到系统锁屏凭据强度，
 *   且用户认证约束可由安全硬件强制执行（isUserAuthenticationRequirementEnforcedBySecureHardware）；
 * - 认证绑定密钥启用 setUnlockedDeviceRequired(true)（设备须处于解锁态方可使用，官方推荐的
 *   与认证要求互补的纵深约束；Android 12–14 的已知缺陷在 15+ 修复，本项目 minSdk 36 恒安全）。
 *   非认证密钥（如 WebDAV 同步凭据封印密钥）不启用——后台同步需在锁屏态可用；
 * - 密钥生成后经 KeyInfo.getSecurityLevel() 校验落位（StrongBox / TEE / 软件），
 *   软件级 Keystore 时记录警告（诊断 API：[getKeySecurityLevel]），不硬失败以兼容模拟器/CI；
 * - 支持 StrongBox 安全元件（FEATURE_STRONGBOX_KEYSTORE），不可用时自动回退 TEE。
 */
@Singleton
class KeystoreManager @Inject constructor(
    // 允许为 null 仅用于单测注入空实现；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val context: Context?,
    // 允许为 null 仅用于单测手动构造；生产 DI 注入
    private val debugLog: DebugLogBuffer? = null
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
        strongBox: Boolean,
        authenticatorTypes: Int = KeyProperties.AUTH_BIOMETRIC_STRONG,
        unlockedDeviceRequired: Boolean = requireUserAuth
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
        val level = getKeySecurityLevel(alias)
        if (level == KeySecurityLevel.SOFTWARE) {
            debugLog?.warn(
                TAG,
                "密钥 $alias 落位软件 Keystore（非 TEE/StrongBox），硬件隔离未生效"
            )
        }
        return key
    }

    /**
     * 密钥硬件落位等级（对齐官方 KeyInfo.getSecurityLevel()，API 31+，minSdk 36 恒可用）。
     */
    enum class KeySecurityLevel {
        /** StrongBox 安全元件（最高隔离） */
        STRONGBOX,

        /** TEE（可信执行环境） */
        TRUSTED_ENVIRONMENT,

        /** 软件 Keystore（无硬件隔离，应告警） */
        SOFTWARE,

        /** 探测失败（密钥不存在或查询异常） */
        UNKNOWN
    }

    /**
     * 查询指定别名的密钥实际硬件落位等级。
     * 注意：`setIsStrongBoxBacked(true)` 只是请求 StrongBox，实际落位须以本方法探测为准。
     */
    fun getKeySecurityLevel(alias: String): KeySecurityLevel {
        return try {
            if (!keyStore.containsAlias(alias)) return KeySecurityLevel.UNKNOWN
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
                ?: return KeySecurityLevel.UNKNOWN
            val factory = KeyFactory.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            )
            val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java)
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
                KeyProperties.SECURITY_LEVEL_UNKNOWN -> KeySecurityLevel.UNKNOWN
                else -> KeySecurityLevel.UNKNOWN
            }
        } catch (_: Exception) {
            KeySecurityLevel.UNKNOWN
        }
    }

    /**
     * 获取或生成「生物识别 + 设备锁屏凭据」双重授权绑定的硬件密钥（Wave 12 统一快速解锁专用）。
     *
     * 官方语义（Android Keystore 文档）：
     * - `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`：
     *   每次加解密操作均需经 BiometricPrompt 以强生物识别**或**设备锁屏凭据（PIN/图案/密码）单独授权；
     * - 解锁加密操作时请求的认证器集合必须与密钥生成时一致（官方硬性要求），
     *   解封方须以 `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` 发起（见 BiometricAuthManager.UNLOCK_AUTHENTICATORS）；
     * - `setInvalidatedByBiometricEnrollment` 对含 AUTH_DEVICE_CREDENTIAL 的密钥被系统忽略
     *   （锁屏凭据变更不触发失效），故本密钥不设置该标志，如实反映官方语义。
     *
     * 密钥授权在生成后不可变（官方约束）→ 旧版本以仅 AUTH_BIOMETRIC_STRONG 生成的同名密钥
     * 经 [KeyInfo.getUserAuthenticationType] 探测后自动删除重建，旧封印凭据随之失效
     * （fail-safe 迁移：用户下次以主密码完整解锁后自动重新封印，对齐 Wave 11 H4 模式）。
     */
    fun getOrCreateDeviceCredentialKey(alias: String): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                val matchesRequirement = try {
                    val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                    val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java)
                    info.isUserAuthenticationRequired &&
                        (info.userAuthenticationType and REQUIRED_AUTHENTICATOR_TYPES) == REQUIRED_AUTHENTICATOR_TYPES
                } catch (_: Exception) {
                    // 规格探测失败按不匹配处理，触发迁移重建（fail-safe）
                    false
                }
                if (matchesRequirement) {
                    return entry.secretKey
                }
                deleteKey(alias)
            }
        }
        return generateNewDeviceCredentialKey(alias)
    }

    /**
     * 生成全新的设备凭据绑定硬件密钥：StrongBox 安全元件优先，不可用自动回退 TEE。
     */
    fun generateNewDeviceCredentialKey(alias: String): SecretKey {
        if (isStrongBoxSupported) {
            try {
                return generateKeyInternal(
                    alias,
                    requireUserAuth = true,
                    invalidateOnBiometricEnrollment = false,
                    strongBox = true,
                    authenticatorTypes = REQUIRED_AUTHENTICATOR_TYPES
                )
            } catch (_: StrongBoxUnavailableException) {
                // StrongBox 缺席或临时繁忙：回退 TEE 生成
            }
        }
        return generateKeyInternal(
            alias,
            requireUserAuth = true,
            invalidateOnBiometricEnrollment = false,
            strongBox = false,
            authenticatorTypes = REQUIRED_AUTHENTICATOR_TYPES
        )
    }

    /**
     * 初始化快速解锁凭据封印（加密）Cipher——设备凭据绑定密钥，
     * 返回的 Cipher 须经 BiometricPrompt（BIOMETRIC_STRONG | DEVICE_CREDENTIAL）授权后方可 doFinal。
     */
    fun initDeviceCredentialEncryptCipher(alias: String): Cipher {
        val key = getOrCreateDeviceCredentialKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * 初始化快速解锁凭据解封（解密）Cipher。
     * 若检测到密钥已因生物识别特征变更而失效（KeyPermanentlyInvalidatedException），自动清除脏密钥并抛出异常
     */
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

        /**
         * Wave 12 前遗留的 QuickUnlock 非认证密钥别名：该密钥不绑定用户认证（历史设计），
         * 现由设备凭据绑定密钥取代；常量仅供 BiometricCredentialStorage 启动期清理旧别名，不再生成新密钥。
         */
        const val LEGACY_QUICK_UNLOCK_KEY_ALIAS = "com.keepasskey.quick_unlock_key"

        /**
         * 快速解锁密钥的授权集合：强生物识别 或 设备锁屏凭据（per-operation）。
         *
         * P1 整改：不再在本处独立书写位或表达式，改由 [UnlockAuthPolicy] 单点声明语义——
         * 官方要求「解锁加密操作请求的认证器集合必须与密钥生成时一致」，
         * 该约束现由同一份策略同时喂给密钥生成侧与本常量，杜绝两侧漂移。
         */
        val REQUIRED_AUTHENTICATOR_TYPES: Int get() = UnlockAuthPolicy.keystoreAuthTypes

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val TAG = "KeystoreManager"
    }
}
