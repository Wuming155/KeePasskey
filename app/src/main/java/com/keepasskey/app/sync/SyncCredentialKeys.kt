package com.keepasskey.app.sync

import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.sync.engine.SyncCache

/**
 * 同步凭据的**键清单与按库命名空间**（`ISSUE-P2-465`：自 `SyncCredentialsStore` 的私有
 * companion 抽出——键清单长度已达规模闸门上限，且命名空间化后需与迁移逻辑同处可测）。
 *
 * ## 键形态
 *
 * 旧全局键（`webdav_url` 等）原样保留为**基名**；按库键 = `<基名>_<SHA-256(dbId) hex>`
 * （`dbId` 见 [ActiveVaultSyncNamespace]，摘要手法与 `KeyFileVaultCopyStore` /
 * `RealSettingsRepository.keyFileMemoryDigest` / `SyncVaultBindingStore` 同口径——
 * 同库恒同键、结构上不可能读到另一个库的记录）。
 *
 * `dbId` 为空（无活动库）时退回基名 = **旧全局键**：既是升级前的存量形态，也是
 * 无库场景（库选择器预填）下唯一可用的记录位置。
 *
 * ## 存量迁移（`ISSUE-P2-465` AC②）
 *
 * 升级后首次在**已知活动库**的语境下读写同步配置时，把存量全局配置**一次性**归属该库
 * （[adoptLegacyInto]），随后物理删除全局键 ⇒ 其余库初始为未配置。
 * 若该库命名空间下已有自己的记录（例如升级后用户已先配置过），迁移**不再覆盖**它，
 * 但全局键**照样清除**——一次性语义下不留下可被后续任意库「捡走」的无主配置。
 */
internal object SyncCredentialKeys {

    /** 当前同步协议（`CloudSyncProvider.name`） */
    const val PROVIDER = "sync_provider"

    const val WEBDAV_URL = "webdav_url"
    const val WEBDAV_USERNAME = "webdav_username"
    const val WEBDAV_REMOTE_PATH = "webdav_remote_path"
    const val WEBDAV_PASSWORD_IV = "webdav_password_iv"
    const val WEBDAV_PASSWORD_CIPHER = "webdav_password_cipher"

    /**
     * Wave 14 已废弃的证书锁定键：只保留基名供加载期一次性物理清除遗留数据。
     * **刻意不进迁移清单**——它是待销毁的残留（值无意义），随首次加载就地删除即可。
     */
    const val WEBDAV_CERT_PINS = "webdav_cert_pins"

    const val S3_ENDPOINT = "s3_endpoint"
    const val S3_BUCKET = "s3_bucket"
    const val S3_REGION = "s3_region"
    const val S3_ACCESS_KEY = "s3_access_key"
    const val S3_ACCESS_KEY_IV = "s3_access_key_iv"
    const val S3_ACCESS_KEY_CIPHER = "s3_access_key_cipher"
    const val S3_OBJECT_KEY = "s3_object_key"
    const val S3_SECRET_IV = "s3_secret_iv"
    const val S3_SECRET_CIPHER = "s3_secret_cipher"
    const val S3_USE_PATH_STYLE = "s3_use_path_style"
    const val S3_CLOCK_OFFSET = "s3_clock_offset_millis"

    /** 「该库已有自己的配置记录」的锚点键：命中任一即不参与存量迁移（防旧键顶掉新配置） */
    private val RECORD_ANCHORS = listOf(WEBDAV_URL, S3_ENDPOINT)

    // 迁移需按类型读写：SharedPreferences 无「按原类型搬运」的通用入口，故按存储类型分列
    private val STRING_BASES = listOf(
        PROVIDER,
        WEBDAV_URL, WEBDAV_USERNAME, WEBDAV_REMOTE_PATH,
        WEBDAV_PASSWORD_IV, WEBDAV_PASSWORD_CIPHER,
        S3_ENDPOINT, S3_BUCKET, S3_REGION, S3_OBJECT_KEY,
        S3_ACCESS_KEY, S3_ACCESS_KEY_IV, S3_ACCESS_KEY_CIPHER, S3_SECRET_IV, S3_SECRET_CIPHER
    )
    private val BOOLEAN_BASES = listOf(S3_USE_PATH_STYLE)
    private val LONG_BASES = listOf(S3_CLOCK_OFFSET)

    /** 全部按库键的基名（迁移判定与 [SyncCredentialsStore.clearFor] 的清理清单共用） */
    val ALL_BASES: List<String> = STRING_BASES + BOOLEAN_BASES + LONG_BASES

    /** 命名空间键名：[dbId] 为空即旧全局键（无活动库时的兼容形态） */
    fun scoped(base: String, dbId: String?): String =
        if (dbId.isNullOrBlank()) base else "$base$NAMESPACE_SEPARATOR${digest(dbId)}"

    /**
     * 存量全局键 → [dbId] 名下的一次性迁移（AC②）。无活动库、或已无存量键时为空操作。
     *
     * 返回值仅供日志：是否真的把配置归属给了该库（false 的两种成因——已迁移过、
     * 或该库自己已有记录——都不影响「全局键被清除」这一结果）。
     */
    fun adoptLegacyInto(prefs: SharedPreferences, dbId: String?, debugLog: DebugLogBuffer?): Boolean {
        if (dbId.isNullOrBlank()) return false
        if (ALL_BASES.none { prefs.contains(it) }) return false

        val targetHasRecord = RECORD_ANCHORS.any { prefs.contains(scoped(it, dbId)) }
        val editor = prefs.edit()
        if (!targetHasRecord) {
            STRING_BASES.forEach { base ->
                prefs.getString(base, null)?.let { editor.putString(scoped(base, dbId), it) }
            }
            BOOLEAN_BASES.forEach { base ->
                if (prefs.contains(base)) editor.putBoolean(scoped(base, dbId), prefs.getBoolean(base, false))
            }
            LONG_BASES.forEach { base ->
                if (prefs.contains(base)) editor.putLong(scoped(base, dbId), prefs.getLong(base, 0L))
            }
        }
        ALL_BASES.forEach { editor.remove(it) }
        editor.apply()
        // 只记布尔与计数（命名空间标识是路径 / URI，属敏感插值，绝不进日志）
        debugLog?.info(
            TAG,
            "同步配置按库归属：moved=$targetHasRecord legacyKeys=${ALL_BASES.size}"
        )
        return !targetHasRecord
    }

    /** 清除该命名空间下的全部键（[SyncCredentialsStore.clearFor] 的清理清单落点） */
    fun removeScoped(prefs: SharedPreferences, dbId: String?) {
        val editor = prefs.edit()
        (ALL_BASES + WEBDAV_CERT_PINS).forEach { editor.remove(scoped(it, dbId)) }
        editor.apply()
    }

    private fun digest(dbId: String): String =
        SyncCache.sha256Hex(dbId.toByteArray(Charsets.UTF_8))

    private const val NAMESPACE_SEPARATOR = "_"
    private const val TAG = "SyncCredentialKeys"
}
