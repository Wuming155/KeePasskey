package com.keepasskey.app.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 解锁通行密钥登记记录（TASK-18）：公钥 / 凭据 ID / 最新 signCount */
data class UnlockPasskeyRecord(
    val publicKeyB64: String,
    val credentialIdB64: String,
    val signCount: Int
)

/**
 * 解锁通行密钥登记记录存储抽象（ISSUE-P1-09 接口化，对齐 [UnlockThrottleStore] 模式）：
 * 生产实现落 SharedPreferences + 硬件 HMAC 防篡改封存；JVM 单测注入内存实现。
 */
interface UnlockPasskeyStore {
    fun saveUnlockPasskey(databaseId: String, publicKeyB64: String, credentialIdB64: String, signCount: Int)

    /** 读取登记记录；未登记 / 记录被删 / **防篡改校验未通过** 一律返回 null（fail-closed） */
    fun getUnlockPasskey(databaseId: String): UnlockPasskeyRecord?

    /** 累进 signCount（成功断言后提交，防克隆语义见 [UnlockPasskeyManager]） */
    fun commitSignCount(databaseId: String, newSignCount: Int)

    /** 清除解锁通行密钥登记记录 */
    fun clearUnlockPasskey(databaseId: String)
}

/**
 * 经 Android Keystore 硬件加密的统一快速解锁主凭据安全存储仓库（Wave 12 收敛）。
 *
 * 快速解锁路径共用本存储（与 per-database 硬件密钥别名一一对应），
 * 存储内容为 AES-256-GCM 密文与初始化向量 (IV)——即使设备 root 或读取明文 XML 也无法解密，
 * 必须通过硬件 TEE/StrongBox 经 BiometricPrompt（Class 3 强生物识别授权，
 * ISSUE-P1-08 起不含设备锁屏凭据）后方可解包。
 *
 * Wave 12 迁移说明：旧版 QuickUnlock PIN 体系（自研 PBKDF2 校验器 + 非认证密钥封印 + 应用级熔断）
 * 已整体移除，快速解锁安全门槛改由「纯生物识别授权硬件密钥 + 系统强生物识别」承载；
 * 首次构造时清理遗留 prefs 文件与遗留 Keystore 别名（fail-safe：旧封印凭据随之失效，
 * 用户下次以主密码完整解锁后自动重新封印）。
 *
 * 编解码说明：使用 java.util.Base64（API 26+，minSdk 36 恒满足）而非 android.util.Base64，
 * 消除 JVM 单元测试对 Android 桩实现的依赖。
 */
@Singleton
class BiometricCredentialStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    // 允许为 null 仅用于 JVM 单测注入空实现；生产 DI 注入 @Singleton 真实实例
    private val keystoreManager: KeystoreManager? = null
) : UnlockPasskeyStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        cleanupLegacyQuickUnlockData()
    }

    /** 一次性清理 Wave 12 前遗留的 QuickUnlock PIN 体系数据（遗留 prefs 文件 + 非认证硬件密钥别名） */
    private fun cleanupLegacyQuickUnlockData() {
        runCatching {
            // JVM 单测注入的 ContextWrapper(null) 无真实底层，此处静默跳过
            context.deleteSharedPreferences(LEGACY_QUICK_UNLOCK_PREFS)
        }
        try {
            keystoreManager?.deleteKey(KeystoreManager.LEGACY_QUICK_UNLOCK_KEY_ALIAS)
        } catch (_: Exception) {
            // Keystore 不可达时跳过：残留密钥不含明文数据，下次启动重试
        }
    }

    /**
     * 保存加密凭据及初始化向量 (IV)
     */
    fun saveEncryptedCredential(databaseId: String, iv: ByteArray, ciphertext: ByteArray) {
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val cipherB64 = Base64.getEncoder().encodeToString(ciphertext)
        prefs.edit()
            .putString("${databaseId}_iv", ivB64)
            .putString("${databaseId}_cipher", cipherB64)
            .apply()
    }

    /**
     * 获取加密凭据及初始化向量 (IV)；数据损坏（Base64 非法）时返回 null（fail-safe，由调用方引导重新登记）
     */
    fun getEncryptedCredential(databaseId: String): Pair<ByteArray, ByteArray>? {
        val ivB64 = prefs.getString("${databaseId}_iv", null) ?: return null
        val cipherB64 = prefs.getString("${databaseId}_cipher", null) ?: return null
        return try {
            val iv = Base64.getDecoder().decode(ivB64)
            val ciphertext = Base64.getDecoder().decode(cipherB64)
            Pair(iv, ciphertext)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * 检查是否存在已封印的快速解锁凭据
     */
    fun hasEncryptedCredential(databaseId: String): Boolean {
        return prefs.contains("${databaseId}_iv") && prefs.contains("${databaseId}_cipher")
    }

    /**
     * 清除特定数据库的封印凭据
     */
    fun clearCredential(databaseId: String) {
        prefs.edit()
            .remove("${databaseId}_iv")
            .remove("${databaseId}_cipher")
            .apply()
    }

    /**
     * 清除所有数据库的封印凭据
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ── 解锁通行密钥记录（TASK-18 / ISSUE-P1-09 防篡改化）──────────────────
    //
    // 登记记录（公钥/credentialId/signCount）经硬件 HMAC 封存：任何仅具备文件级
    // 写能力的篡改（改小 signCount 绕过单调校验、替换公钥自证同源）都会导致
    // MAC 不匹配 → 记录按缺失处理（fail-closed，拒绝快速解锁）。
    // 诚实边界：同 UID 任意代码执行者可调用 Keystore 重算 MAC，该威胁域本层不设防。

    /** 保存解锁通行密钥登记记录（公钥 X.509 编码 Base64 / 凭据 ID Base64 / signCount + 完整性 MAC） */
    override fun saveUnlockPasskey(databaseId: String, publicKeyB64: String, credentialIdB64: String, signCount: Int) {
        val record = UnlockPasskeyRecord(publicKeyB64, credentialIdB64, signCount)
        prefs.edit()
            .putString("${databaseId}_passkey_pub", publicKeyB64)
            .putString("${databaseId}_passkey_cred", credentialIdB64)
            .putInt("${databaseId}_passkey_count", signCount)
            .putString("${databaseId}_passkey_mac", integrityMac(databaseId, record))
            .apply()
    }

    /** 读取解锁通行密钥登记记录；未登记 / 记录被删 / 防篡改校验未通过返回 null（fail-closed） */
    override fun getUnlockPasskey(databaseId: String): UnlockPasskeyRecord? {
        val pubB64 = prefs.getString("${databaseId}_passkey_pub", null) ?: return null
        val credB64 = prefs.getString("${databaseId}_passkey_cred", null) ?: return null
        val count = prefs.getInt("${databaseId}_passkey_count", 0)
        val record = UnlockPasskeyRecord(publicKeyB64 = pubB64, credentialIdB64 = credB64, signCount = count)
        // keystoreManager == null 仅存在于 JVM 单测注入场景：跳过完整性校验
        if (keystoreManager == null) return record
        val storedMac = prefs.getString("${databaseId}_passkey_mac", null) ?: return null
        return if (storedMac == integrityMac(databaseId, record)) record else null
    }

    /** 累进 signCount（成功断言后提交）；同步重算完整性 MAC，杜绝「改计数留旧 MAC」 */
    override fun commitSignCount(databaseId: String, newSignCount: Int) {
        val pubB64 = prefs.getString("${databaseId}_passkey_pub", null) ?: return
        val credB64 = prefs.getString("${databaseId}_passkey_cred", null) ?: return
        val record = UnlockPasskeyRecord(publicKeyB64 = pubB64, credentialIdB64 = credB64, signCount = newSignCount)
        prefs.edit()
            .putInt("${databaseId}_passkey_count", newSignCount)
            .putString("${databaseId}_passkey_mac", integrityMac(databaseId, record))
            .apply()
    }

    /** 清除解锁通行密钥登记记录（含完整性 MAC） */
    override fun clearUnlockPasskey(databaseId: String) {
        prefs.edit()
            .remove("${databaseId}_passkey_pub")
            .remove("${databaseId}_passkey_cred")
            .remove("${databaseId}_passkey_count")
            .remove("${databaseId}_passkey_mac")
            .apply()
    }

    /** 登记记录的完整性 MAC（Base64）；Keystore 不可达时返回 null（读取侧按校验失败 fail-closed） */
    private fun integrityMac(databaseId: String, record: UnlockPasskeyRecord): String? {
        val mac = keystoreManager?.getOrCreateUnlockPasskeyIntegrityMac() ?: return null
        return try {
            // 绑定 databaseId 防跨库挪用记录；字段以 '|' 分隔并经 Base64 消除歧义
            val payload = Base64.getEncoder().encodeToString(
                "$databaseId|${record.publicKeyB64}|${record.credentialIdB64}|${record.signCount}"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            Base64.getEncoder().encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "com.keepasskey.biometric_credentials"

        /** Wave 12 前遗留 QuickUnlock PIN 体系 prefs 文件名（仅供启动期清理引用） */
        private const val LEGACY_QUICK_UNLOCK_PREFS = "com.keepasskey.quick_unlock_pin"
    }
}
