package com.keepasskey.app.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        cleanupLegacyQuickUnlockData()
        cleanupLegacyUnlockPasskeyData()
    }

    /**
     * 一次性清理 ISSUE-P3-327 移除的解锁断言体系残留（2026-09-25 用户裁决）：
     * 遗留 `_passkey_*` prefs 键与两类 Keystore 别名（per-database 断言密钥对 +
     * 登记记录防篡改 HMAC 密钥）。断言层移除后 [revokeAllBiometricData] 的枚举后缀
     * 不再包含 `_passkey_*`，若不在此清理，两者将成为无人管理残留
     * （Wave 12 清退旧 PIN 体系即同一模式；Keystore 不可达时跳过，下次启动重试）。
     */
    private fun cleanupLegacyUnlockPasskeyData() {
        // best-effort：prefs 枚举 / Keystore 访问任一失败都不得阻断构造（JVM 测试假 prefs
        // 未实现 getAll、真机 Keystore 不可达等），残留随下次启动重试
        runCatching {
            val legacySuffixes = listOf("_passkey_pub", "_passkey_cred", "_passkey_count", "_passkey_mac")
            val dbIds = mutableSetOf<String>()
            val editor = prefs.edit()
            for (key in prefs.all.keys) {
                for (suffix in legacySuffixes) {
                    if (key.endsWith(suffix)) {
                        editor.remove(key)
                        key.dropLast(suffix.length).takeIf { it.isNotEmpty() }?.let { dbIds.add(it) }
                        break
                    }
                }
            }
            editor.apply()
            val km = keystoreManager ?: return
            for (dbId in dbIds) {
                runCatching { km.deleteKey(KeystoreManager.legacyUnlockPasskeyAliasFor(dbId)) }
            }
            runCatching { km.deleteKey(KeystoreManager.LEGACY_UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS) }
        }
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

    /**
     * 撤销全部生物识别数据（ISSUE-P2-253：**关闭开关 = 删除**）。
     *
     * ① 从 prefs 键集合按已知后缀解析出全部已登记库 ID，逐库删除其**封印密钥**的
     *   Keystore 别名（单点构造见 [KeystoreManager.sealAliasFor]）——逐项 `runCatching`，
     *   单个别名删除失败不阻断其余撤销（Keystore 不可达时跳过密钥删除，
     *   **prefs 清空仍必须执行**）；
     * ② 清空全部 prefs（各库封印 `iv`/`cipher`）。
     *
     * **刻意不删**：[KeystoreManager.AUTOFILL_AUTH_KEY_ALIAS]（自动填充放行绑定与本开关无关）。
     * 解锁断言体系的别名残留由 [cleanupLegacyUnlockPasskeyData] 启动期一次性清理
     * （ISSUE-P3-327）。
     */
    fun revokeAllBiometricData() {
        val dbIds = mutableSetOf<String>()
        for (key in prefs.all.keys) {
            for (suffix in DB_KEY_SUFFIXES) {
                if (key.endsWith(suffix)) {
                    val dbId = key.dropLast(suffix.length)
                    if (dbId.isNotEmpty()) dbIds.add(dbId)
                    break
                }
            }
        }
        keystoreManager?.let { km ->
            for (dbId in dbIds) {
                // 单个别名删除失败不阻断其余撤销（残留别名不含明文，且下次撤销重试）
                runCatching { km.deleteKey(KeystoreManager.sealAliasFor(dbId)) }
            }
        }
        clearAll()
    }

    companion object {
        private const val PREFS_NAME = "com.keepasskey.biometric_credentials"

        /** Wave 12 前遗留 QuickUnlock PIN 体系 prefs 文件名（仅供启动期清理引用） */
        private const val LEGACY_QUICK_UNLOCK_PREFS = "com.keepasskey.quick_unlock_pin"

        /**
         * prefs 中以库 ID 为前缀的键后缀全集（ISSUE-P2-253）：
         * [revokeAllBiometricData] 据此枚举全部已登记库；新增键后缀须同步登记于此。
         */
        private val DB_KEY_SUFFIXES = listOf(
            "_iv",
            "_cipher"
        )
    }
}
