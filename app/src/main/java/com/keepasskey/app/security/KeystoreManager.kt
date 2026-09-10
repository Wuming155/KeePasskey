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
 * - Wave 12：快速解锁密钥改为统一认证绑定密钥。ISSUE-P1-08 起收敛为「**仅强生物识别**」授权
 *   （setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)）：
 *   设备锁屏凭据（PIN/图案/密码）不再可解封（弱 PIN 拉低爆破门槛），
 *   且纯生物识别密钥的 setInvalidatedByBiometricEnrollment(true) 得以生效——
 *   系统录入/清空指纹时自动吊销密钥，新增指纹不会复用既有封印凭据；
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
     * 获取或生成快速解锁封印硬件密钥（Wave 12 统一；ISSUE-P1-08 起为「仅强生物识别」授权）。
     *
     * 官方语义（Android Keystore 文档）：
     * - `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`：
     *   每次加解密操作均需经 BiometricPrompt 以 Class 3 强生物识别单独授权；
     *   设备锁屏凭据（PIN/图案/密码）不再可解封——锁屏弱 PIN 会拉低爆破门槛（OWASP MASVS-AUTH-8）；
     * - 解锁加密操作时请求的认证器集合必须与密钥生成时一致（官方硬性要求），
     *   解封方须以 `BIOMETRIC_STRONG` 发起（见 BiometricAuthManager.UNLOCK_AUTHENTICATORS）；
     * - `setInvalidatedByBiometricEnrollment(true)` 对纯生物识别密钥生效：
     *   系统录入/清空指纹即吊销密钥（KeyPermanentlyInvalidated），新增指纹不会复用既有封印凭据
     *   （该标志对含 AUTH_DEVICE_CREDENTIAL 的密钥被系统忽略，故必须收敛为纯生物识别授权）。
     *
     * 密钥授权在生成后不可变（官方约束）→ 旧版本以 AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL
     * 生成的同名密钥经 [KeyInfo.getUserAuthenticationType] **全等**探测后自动删除重建，
     * 旧封印凭据随之失效（fail-safe 迁移：解封失败由 UnlockViewModel 清除陈旧凭据，
     * 用户下次以主密码完整解锁后自动重新封印，对齐 Wave 11 H4 模式）。
     */
    fun getOrCreateDeviceCredentialKey(alias: String): SecretKey {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                val matchesRequirement = try {
                    val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                    val info = factory.getKeySpec(entry.secretKey, KeyInfo::class.java)
                    // ISSUE-P1-08：全等比较（而非位包含）——旧「BIOMETRIC_STRONG | DEVICE_CREDENTIAL」密钥
                    // 虽含所需位但允许锁屏凭据解封，必须判定为不匹配并迁移重建为纯生物识别密钥
                    info.isUserAuthenticationRequired &&
                        info.userAuthenticationType == REQUIRED_AUTHENTICATOR_TYPES
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
     * 生成全新的快速解锁封印硬件密钥：StrongBox 安全元件优先，不可用自动回退 TEE。
     * 纯生物识别授权 → setInvalidatedByBiometricEnrollment(true) 生效（ISSUE-P1-08）。
     */
    fun generateNewDeviceCredentialKey(alias: String): SecretKey {
        if (isStrongBoxSupported) {
            try {
                return generateKeyInternal(
                    alias,
                    requireUserAuth = true,
                    invalidateOnBiometricEnrollment = true,
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
            invalidateOnBiometricEnrollment = true,
            strongBox = false,
            authenticatorTypes = REQUIRED_AUTHENTICATOR_TYPES
        )
    }

    /**
     * 初始化快速解锁凭据封印（加密）Cipher——纯生物识别授权密钥，
     * 返回的 Cipher 须经 BiometricPrompt（BIOMETRIC_STRONG）授权后方可 doFinal。
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

    /**
     * 获取或生成设备绑定「解锁通行密钥」ES256（P-256 ECDSA）密钥对（TASK-18）。
     *
     * 私钥生成于 Keystore 硬件内（StrongBox 优先，回退 TEE），**不可导出**；
     * ISSUE-P1-09：私钥**绑定强生物识别用户认证**（认证时间窗
     * [UNLOCK_PASSKEY_AUTH_VALIDITY_SECONDS] 内方可签名）——快速解锁流程中
     * BiometricPrompt（Class 3 强生物识别）授权解封后，同一认证事件的时间窗内
     * 即可完成断言签名；窗口外签名抛 UserNotAuthenticatedException，由调用方
     * fail-closed 拒绝（认证门控由封印密钥承担，断言承担凭据持有性证明与
     * signCount 防克隆，且不再存在「无认证即签名」的密钥形态）。
     *
     * 兼容轮换：旧规范（未绑定用户认证）的存量私钥一经发现立即删除重建——
     * 新私钥公钥与已登记记录不再匹配，断言验证 fail-closed，用户以主密码完整
     * 解锁后自动重新登记，杜绝旧密钥永久游离于认证门控之外。
     */
    @Synchronized
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
                ANDROID_KEY_STORE
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
            val factory = KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEY_STORE)
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
                UNLOCK_PASSKEY_AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
            .setUnlockedDeviceRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
    }

    /**
     * 获取或生成解锁通行密钥登记记录的**防篡改完整性 HMAC 密钥**（ISSUE-P1-09）。
     *
     * HmacSHA256，硬件内不可导出，无用户认证门控（完整性校验在解锁流程内执行，
     * 设备必为解锁态）；用于对登记记录（公钥/credentialId/signCount）计算 MAC，
     * 使「仅具备文件级写能力」（ADB 备份恢复 / 取证 / 同 UID 之外写入）的攻击者
     * 无法在不触发校验失败的前提下篡改 signCount 或替换公钥——校验失败按记录
     * 缺失处理（fail-closed，见 [BiometricCredentialStorage.getUnlockPasskey]）。
     */
    @Synchronized
    fun getOrCreateUnlockPasskeyIntegrityMac(): javax.crypto.Mac? {
        return try {
            val alias = UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS
            if (!keyStore.containsAlias(alias)) {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    ANDROID_KEY_STORE
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
            javax.crypto.Mac.getInstance("HmacSHA256").apply { init(entry.secretKey) }
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥完整性 HMAC 密钥获取失败: ${e.javaClass.simpleName} - ${e.message}")
            null
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
         * 解锁通行密钥断言签名的强生物识别认证时间窗（秒，ISSUE-P1-09）。
         * 快速解锁流程内 BiometricPrompt 授权解封后立即执行断言签名，30s 窗口
         * 覆盖正常流程；窗口外签名抛 UserNotAuthenticatedException，fail-closed。
         */
        const val UNLOCK_PASSKEY_AUTH_VALIDITY_SECONDS = 30

        /** 解锁通行密钥登记记录防篡改 HMAC 密钥别名（ISSUE-P1-09） */
        const val UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS = "com.keepasskey.unlock_passkey_integrity"

        /**
         * 快速解锁密钥的授权集合：仅 Class 3 强生物识别（per-operation）。
         * ISSUE-P1-08：设备锁屏凭据（PIN/图案/密码）不再可解封（弱凭据降级 + 生物录入失效标志被忽略）。
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
