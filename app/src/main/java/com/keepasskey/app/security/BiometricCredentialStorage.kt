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
 * 构造时清理遗留 prefs 文件与遗留 Keystore 别名（fail-safe：旧封印凭据随之失效，
 * 用户下次以主密码完整解锁后自动重新封印）。
 *
 * ## 迁移的幂等完成标记（ENG-20261028-44）
 *
 * 构造线程与成本：本类为 `@Singleton`，由 Hilt 在**首个注入方所在线程**构造，生产注入方是
 * `UnlockViewModel` / `SettingsViewModel` 等 ViewModel ⇒ **主线程**构造。构造除一次
 * `getSharedPreferences` 之外，原实现每次进程启动都无条件执行 `deleteSharedPreferences` 与
 * `KeyStore.containsAlias`/`deleteEntry`（首次访问 AndroidKeyStore 要 `load(null)`）——
 * 这类文件系统与 Keystore 绑定器调用在主线程上是白付的冷启动开销。
 *
 * 因此清理只在 [LEGACY_MIGRATION_DONE_KEY] 缺省时执行一次：**只有两步清理都被确认成功**
 * （`deleteSharedPreferences` 返回 true、Keystore 删除未抛异常）才写完成标记；任一步失败就保留
 * 未完成状态，下次进程启动重试——失败重试的迁移价值（旧封印凭据失效）不被削弱。
 * 仍留在构造期同步执行（而非后台迁移）是刻意取舍：迁移必须先于本实例的任何封印/解封使用完成，
 * 否则同进程内可能出现「旧别名尚在、新凭据已写入」的交错。
 *
 * 已知边界：标记是「本机已清理」的结论。先安装旧版（重新产生遗留数据）再覆盖升级时，
 * 标记为 true 会跳过重扫；旧版写入的这些键与别名在新版已无任何读取方，属惰性残留，
 * 不影响封印凭据的失效保障。
 *
 * 编解码说明：使用 java.util.Base64（API 26+，minSdk 36 恒满足）而非 android.util.Base64，
 * 消除 JVM 单元测试对 Android 桩实现的依赖。
 */
@Singleton
class BiometricCredentialStorage internal constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager?,
    private val keyDeleter: ((String) -> Unit)?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager? = null
    ) : this(context, keystoreManager, null)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 遗留迁移是否已完成（进程内快照）：仅在两步清理都确认成功后置位，[clearAll] 不清除该标记 */
    @Volatile
    private var legacyMigrationDone = prefs.getBoolean(LEGACY_MIGRATION_DONE_KEY, false)

    init {
        migrateLegacyDataOnce()
    }

    /**
     * 一次性遗留数据迁移（ENG-20261028-44）：已完成则零操作；否则依次清理两类遗留数据，
     * **两步皆确认成功**才落完成标记。清理本身是幂等的，失败时下次构造/下次进程启动重试。
     */
    private fun migrateLegacyDataOnce() {
        if (legacyMigrationDone) return
        val passkeyCleared = cleanupLegacyUnlockPasskeyData()
        val quickUnlockCleared = cleanupLegacyQuickUnlockData()
        if (passkeyCleared && quickUnlockCleared) {
            legacyMigrationDone = true
            // 标记写入失败只影响下次启动重跑一次幂等清理，不得阻断构造
            runCatching { prefs.edit().putBoolean(LEGACY_MIGRATION_DONE_KEY, true).apply() }
        }
    }

    /**
     * 一次性清理 ISSUE-P3-327 移除的解锁断言体系残留（2026-09-25 用户裁决）：
     * 遗留 `_passkey_*` prefs 键与两类 Keystore 别名（per-database 断言密钥对 +
     * 登记记录防篡改 HMAC 密钥）。断言层移除后 [revokeAllBiometricData] 的枚举后缀
     * 不再包含 `_passkey_*`，若不在此清理，两者将成为无人管理残留。
     *
     * 失败重试与重试索引保护（ENG-20261028-44）：
     * 必须先确认硬件别名删除成功，才能移除对应的 prefs 键；未提供 Keystore 删除能力
     * 或任一硬件别名删除失败时，必须完整保留未成功的 dbId 键作为后续重试索引，
     * 绝不得在硬件删除前删 prefs，避免重试时丢失别名引用导致假性全清。
     *
     * @return 仅在全部硬件别名删除成功且对应 prefs 键已清理时为 true
     */
    private fun cleanupLegacyUnlockPasskeyData(): Boolean {
        val hasDeleter = keystoreManager != null || keyDeleter != null
        if (!hasDeleter) return false

        // 1. 枚举需要清理的遗留键与 dbId，建立 dbId -> 键集合的映射（先不删 prefs）
        val dbIdToKeys = mutableMapOf<String, MutableList<String>>()
        val scanSuccess = runCatching {
            for (key in prefs.all.keys) {
                for (suffix in LEGACY_PASSKEY_KEY_SUFFIXES) {
                    if (key.endsWith(suffix)) {
                        val dbId = key.dropLast(suffix.length)
                        if (dbId.isNotEmpty()) {
                            dbIdToKeys.getOrPut(dbId) { mutableListOf() }.add(key)
                        }
                        break
                    }
                }
            }
        }.isSuccess
        if (!scanSuccess) return false

        var allAliasesDeleted = true
        val successfullyDeletedDbIds = mutableListOf<String>()

        // 2. 先逐库删除 AndroidKeyStore 硬件别名
        for (dbId in dbIdToKeys.keys) {
            val alias = KeystoreManager.legacyUnlockPasskeyAliasFor(dbId)
            val deleted = runCatching { deleteKey(alias) }.isSuccess
            if (deleted) {
                successfullyDeletedDbIds.add(dbId)
            } else {
                allAliasesDeleted = false
            }
        }

        // 3. 删除完整性 HMAC 密钥别名
        val integrityKeyDeleted = runCatching {
            deleteKey(KeystoreManager.LEGACY_UNLOCK_PASSKEY_INTEGRITY_KEY_ALIAS)
        }.isSuccess
        if (!integrityKeyDeleted) {
            allAliasesDeleted = false
        }

        // 4. 仅对硬件删除确认成功的库移除 prefs 键；失败的库保留 prefs 键作为下次启动的重试索引
        if (successfullyDeletedDbIds.isNotEmpty()) {
            runCatching {
                val editor = prefs.edit()
                for (dbId in successfullyDeletedDbIds) {
                    dbIdToKeys[dbId]?.forEach { key -> editor.remove(key) }
                }
                editor.apply()
            }
        }

        return allAliasesDeleted
    }

    /**
     * 一次性清理 Wave 12 前遗留的 QuickUnlock PIN 体系数据（遗留 prefs 文件 + 非认证硬件密钥别名）。
     *
     * 顺序：必须先删除硬件 Keystore 别名，成功后才删除遗留 prefs 文件，确保硬件操作失败时不丢失状态。
     *
     * @return 仅在 Keystore 删除未抛异常且遗留 prefs 文件确已删除时为 true
     */
    private fun cleanupLegacyQuickUnlockData(): Boolean {
        val hasDeleter = keystoreManager != null || keyDeleter != null
        if (!hasDeleter) return false

        val aliasDeleted = runCatching {
            deleteKey(KeystoreManager.LEGACY_QUICK_UNLOCK_KEY_ALIAS)
        }.isSuccess
        if (!aliasDeleted) return false

        // JVM 单测注入的 ContextWrapper(null) 无真实底层，此处静默跳过并视为未成功（保持重试）
        val prefsDeleted = runCatching {
            context.deleteSharedPreferences(LEGACY_QUICK_UNLOCK_PREFS)
        }.getOrDefault(false)
        return prefsDeleted
    }

    /** 执行别名删除（优先委托 keyDeleter，缺省回落 keystoreManager） */
    private fun deleteKey(alias: String) {
        val customDeleter = keyDeleter
        if (customDeleter != null) {
            customDeleter.invoke(alias)
        } else {
            keystoreManager?.deleteKey(alias)
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
     * 清除所有数据库的封印凭据。
     *
     * 遗留迁移完成标记不属于凭据：若一并清掉，撤销后每次进程启动都要重跑一次遗留清理
     * （文件与 Keystore 访问），因此按当前进程快照原样保留（ENG-20261028-44）。
     */
    fun clearAll() {
        val editor = prefs.edit().clear()
        if (legacyMigrationDone) editor.putBoolean(LEGACY_MIGRATION_DONE_KEY, true)
        editor.apply()
    }

    /**
     * 撤销全部生物识别数据（ISSUE-P2-253：**关闭开关 = 删除**）。
     *
     * ① 从 prefs 键集合按已知后缀解析出全部已登记库 ID，逐库删除其**封印密钥**的
     *   Keystore 别名（单点构造见 [KeystoreManager.sealAliasFor]）——逐项 `runCatching`，
     *   单个别名删除失败不阻断其余撤销（Keystore 不可达时跳过密钥删除，
     *   **prefs 清空仍必须执行**）；
     * ② 清空全部凭据 prefs（各库封印 `iv`/`cipher`；遗留迁移完成标记不属凭据，见 [clearAll]）。
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
         * 遗留迁移完成标记键（ENG-20261028-44）：`true` 表示两类遗留数据都已确认清理成功，
         * 后续构造不再访问遗留 prefs 文件与 Keystore 别名。与凭据同文件以免多读一个 prefs 文件，
         * 由 [clearAll] 保留。
         */
        private const val LEGACY_MIGRATION_DONE_KEY = "legacy_migration_done"

        /**
         * ISSUE-P3-327 遗留解锁断言体系的 prefs 键后缀（库 ID 前缀 + 后缀，见
         * [KeystoreManager.legacyUnlockPasskeyAliasFor]）。
         */
        private val LEGACY_PASSKEY_KEY_SUFFIXES = listOf(
            "_passkey_pub",
            "_passkey_cred",
            "_passkey_count",
            "_passkey_mac"
        )

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
