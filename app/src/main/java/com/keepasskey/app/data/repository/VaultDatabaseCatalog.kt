package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.core.log.AppLog
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已知密码库注册表（ISSUE-P3-31 批次 B 自 `RealVaultRepository` 拆出，纯结构性改动）。
 *
 * 职责单一：外部库元数据的 SharedPreferences 读写（[KnownDatabaseEntry] 编解码）、
 * 活动库 ID 的持久化，以及「沙盒内部 `*.kdbx` + 已知外部库」列表的构建与活动态标定。
 *
 * 本类**不持有**反应式状态：列表构建结果由 [buildDatabaseList] 返回，
 * 是否推送到 `databasesFlow` 仍由仓库决定（语义与拆分前完全一致）。
 */
internal class VaultDatabaseCatalog(
    private val context: Context,
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession
) {

    /** `ISSUE-P2-465`：活动库 ID 的持久化单点（与同步配置命名空间共用同一份记录） */
    private val activeIdStore = ActiveDatabaseIdStore(context)

    /** 一条已知（含外部）密码库的持久化元数据 */
    internal data class KnownDatabaseEntry(
        val id: String,
        val name: String,
        val path: String,
        val isRemote: Boolean,
        val syncType: String,
        val lastOpenedAt: String
    )

    fun loadKnownDatabases(): List<KnownDatabaseEntry> {
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return emptyList()
            val raw = sp.getString(KEY_KNOWN_DATABASES, null) ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            raw.split(RECORD_SEPARATOR).filter { it.isNotBlank() }.mapNotNull { record ->
                val fields = record.split(FIELD_SEPARATOR)
                if (fields.size >= 6) {
                    KnownDatabaseEntry(
                        id = fields[0],
                        name = fields[1],
                        path = fields[2],
                        isRemote = fields[3].toBoolean(),
                        syncType = fields[4],
                        lastOpenedAt = fields[5]
                    )
                } else null
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "读取已知密码库列表失败，按空列表回落", t)
            emptyList()
        }
    }

    fun saveKnownDatabases(entries: List<KnownDatabaseEntry>) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
            val encoded = entries.joinToString(RECORD_SEPARATOR) { entry ->
                listOf(
                    entry.id,
                    entry.name,
                    entry.path,
                    entry.isRemote.toString(),
                    entry.syncType,
                    entry.lastOpenedAt
                ).joinToString(FIELD_SEPARATOR)
            }
            sp.edit().putString(KEY_KNOWN_DATABASES, encoded).apply()
        } catch (t: Throwable) {
            AppLog.w(TAG, "写入已知密码库列表失败（条目数=${entries.size}）", t)
        }
    }

    /**
     * 读取活动库 ID。
     *
     * `ISSUE-P2-465`：记录本体与键名已抽出为 [ActiveDatabaseIdStore]（同步配置的按库命名空间
     * 需读同一份记录，键名单点定义），本方法只是转发，容错语义与抽出前逐字一致。
     */
    fun loadActiveDbId(): String? = activeIdStore.load()

    /** 登记 / 清除活动库 ID（null = 清除）；转发 [ActiveDatabaseIdStore.save]。 */
    fun saveActiveDbId(id: String?) = activeIdStore.save(id)

    /**
     * `ISSUE-P3-230`：该库路径是否**缺少**持久化读授权（非 `content://` 恒 false）。
     *
     * 查询失败（`ContentResolver` 异常）按「未知」处理 ⇒ false（不误报）——
     * 与 [persistedReadUriStrings] 的 `null` 语义一致，绝不把查询失败谎报成「缺授权」。
     */
    private fun lacksPersistedPermission(path: String): Boolean {
        if (!path.startsWith("content://")) return false
        val granted = persistedReadUriStrings(context) ?: return false
        return lacksPersistedReadPermission(path, granted)
    }

    /**
     * 单条已知库登记 → 列表投影（自 `buildDatabaseList` 抽出，纯结构性改动）。
     *
     * `content://` 来源逐次向 Provider 查询文件大小（失败按缺省 32KB 回落），
     * 其余按本地文件实际长度计。
     */
    private suspend fun knownEntryToInfo(ext: KnownDatabaseEntry): VaultDatabaseInfo {
        val sizeKb = if (ext.path.startsWith("content://")) {
            withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(ext.path)
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && cursor.moveToFirst()) {
                            (cursor.getLong(sizeIndex) / 1024).coerceAtLeast(1)
                        } else null
                    } ?: 32L
                } catch (t: Throwable) { // cancel-n/a: 保护段为阻塞式 ContentResolver 查询（无挂起点）
                    AppLog.w(TAG, "查询外部库大小失败，按缺省 32KB 回落", t)
                    32L
                }
            }
        } else {
            val f = File(ext.path)
            if (f.exists()) (f.length() / 1024).coerceAtLeast(1) else 32L
        }
        return VaultDatabaseInfo(
            id = ext.id,
            name = ext.name,
            path = ext.path,
            isRemote = ext.isRemote,
            syncType = ext.syncType,
            lastOpenedAt = strings.get(R.string.repo_last_opened_ready),
            fileSizeFormatted = "$sizeKb KB",
            isActive = false,
            encryptionPreset = "AES-256 + Argon2id",
            // ISSUE-P3-230：`content://` 库缺持久化读授权时，重启后打不开——列表给出
            // 可辨识状态与重授入口（查询失败按「未知」处理，不误报）
            lacksPersistedPermission = lacksPersistedPermission(ext.path)
        )
    }

    /**
     * 构建「已知外部库 + 沙盒内部 `*.kdbx`」的合并列表并标定活动态。
     *
     * 副作用（与拆分前一致）：当已有条目均未被标为活动时，把首条写回为活动库 ID。
     */
    suspend fun buildDatabaseList(): List<VaultDatabaseInfo> {
        val filesDir = context.filesDir
        val kdbxFiles = withContext(Dispatchers.IO) {
            filesDir?.listFiles { file ->
                file.extension.equals("kdbx", ignoreCase = true)
            } ?: emptyArray()
        }

        val knownExternal = loadKnownDatabases().map { knownEntryToInfo(it) }

        // ISSUE-P3-404：云端导入的库文件物理落地在本沙盒（`CloudVaultImporter` 下载副本），
        // 目录扫描会为同一文件再产出一笔「本地设备存储」条目——「密码库管理」出现两张卡
        // 指向同一物理库（登记条目 id=绝对路径、扫描条目 id=文件名，末尾 `distinctBy` 挡不住）。
        // 对齐 keepass2android 的单条目口径：登记表条目对同一本地路径恒优先，扫描侧先剔除，
        // 云端卡片成为该库唯一展示，本地副本只是它的落地实现细节。
        val registeredLocalPaths = knownExternal
            .filter { !it.path.startsWith("content://") }
            // cancel-n/a: File.canonicalPath 为阻塞式路径解析，无挂起点
            .mapNotNull { ext -> runCatching { File(ext.path).canonicalPath }.getOrNull() }
            .toSet()
        val internalEntries = kdbxFiles
            // cancel-n/a: File.canonicalPath 为阻塞式路径解析，无挂起点
            .filter { file -> runCatching { file.canonicalPath }.getOrNull() !in registeredLocalPaths }
            .map { file ->
                val sizeKb = (file.length() / 1024).coerceAtLeast(1)
                VaultDatabaseInfo(
                    id = file.name,
                    name = file.name,
                    path = file.absolutePath,
                    isRemote = false,
                    syncType = strings.get(R.string.repo_sync_type_local_device),
                    lastOpenedAt = strings.get(R.string.repo_last_opened_ready),
                    fileSizeFormatted = "$sizeKb KB",
                    isActive = false,
                    encryptionPreset = "AES-256 + Argon2id"
                )
            }

        // 合并外部库与沙盒内部库
        val combined = (knownExternal + internalEntries).distinctBy { it.id }

        val sessionActiveIdentifier = databaseSession.currentPathIdentifier ?: databaseSession.currentFile?.name
        val storedActiveId = loadActiveDbId()
        val effectiveActiveId = sessionActiveIdentifier ?: storedActiveId

        if (combined.isEmpty()) {
            return emptyList()
        }
        // 活动库唯一判定：优先按 id 精确命中。仅当 id 未命中时，才回退 path / name
        // （兼容历史遗留的 storedActiveId 可能落为路径 / 文件名的旧记录）。
        // 不能用 id/path/name 的「或」逻辑：两个库文件名相同时（例如本地 vault.kdbx 与
        // content://…/vault.kdbx 同名）会被同时标为活动库，冷启动 firstOrNull{isActive} 取列表首个，
        // 从而回显错库的密钥文件 —— 这正是「杀后台冷启动才暴露」的现象（热重启内存态正确）。
        // ISSUE-P2-465 后续修复。
        val resolvedActive = combined.firstOrNull { it.id == effectiveActiveId }
            ?: combined.firstOrNull { it.path == effectiveActiveId || it.name == effectiveActiveId }
        val list = combined.map { db ->
            val isActive = db.id == resolvedActive?.id
            db.copy(
                isActive = isActive,
                lastOpenedAt = if (isActive && databaseSession.state.value == DatabaseSession.SessionState.OPENED) {
                    strings.get(R.string.repo_last_opened_in_use)
                } else {
                    strings.get(R.string.repo_last_opened_ready)
                }
            )
        }
        return if (list.none { it.isActive }) {
            list.mapIndexed { index, item ->
                if (index == 0) {
                    saveActiveDbId(item.id)
                    item.copy(isActive = true)
                } else item
            }
        } else {
            list
        }
    }

    private companion object {
        const val TAG = "VaultDbCatalog"

        /** 与 [ActiveDatabaseIdStore.PREFS_NAME] 同一文件（活动库 ID 键由该单点持有） */
        val PREFS_NAME = ActiveDatabaseIdStore.PREFS_NAME
        const val KEY_KNOWN_DATABASES = "known_databases_v1"
        const val RECORD_SEPARATOR = "\u0002"
        const val FIELD_SEPARATOR = "\u0001"
    }
}
