package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.VaultDatabaseInfo
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
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
        }
    }

    fun loadActiveDbId(): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_ACTIVE_DATABASE_ID, null)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveActiveDbId(id: String?) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
            if (id == null) {
                sp.edit().remove(KEY_ACTIVE_DATABASE_ID).apply()
            } else {
                sp.edit().putString(KEY_ACTIVE_DATABASE_ID, id).apply()
            }
        } catch (_: Throwable) {
        }
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

        val internalEntries = kdbxFiles.map { file ->
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

        val knownExternal = loadKnownDatabases().map { ext ->
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
                    } catch (_: Throwable) {
                        32L
                    }
                }
            } else {
                val f = File(ext.path)
                if (f.exists()) (f.length() / 1024).coerceAtLeast(1) else 32L
            }
            VaultDatabaseInfo(
                id = ext.id,
                name = ext.name,
                path = ext.path,
                isRemote = ext.isRemote,
                syncType = ext.syncType,
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
        val list = combined.map { db ->
            val isActive = (db.id == effectiveActiveId || db.path == effectiveActiveId || db.name == effectiveActiveId)
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
        const val PREFS_NAME = "keepasskey_vault_meta"
        const val KEY_KNOWN_DATABASES = "known_databases_v1"
        const val KEY_ACTIVE_DATABASE_ID = "active_database_id"
        const val RECORD_SEPARATOR = "\u0002"
        const val FIELD_SEPARATOR = "\u0001"
    }
}
