package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于真实 KDBX 数据库引擎与 DatabaseSession 的生产级数据仓库。
 * 遵循推荐架构规范：屏蔽底层加解密与 XML 序列化细节，向上层 UI 提供反应式状态流与 CRUD 契约。
 */
@Singleton
class RealVaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession
) : VaultRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val databasesFlow = MutableStateFlow<List<VaultDatabaseInfo>>(emptyList())

    // 内存回收站缓冲，用于保存移入回收站但尚未物理彻底抹除的条目 ID
    private val recycledEntryIds = MutableStateFlow<Set<String>>(emptySet())

    init {
        refreshDatabases()
        // 观察会话，当数据库发生变化时同步刷新
        repositoryScope.launch {
            databaseSession.databaseFlow.collect {
                refreshDatabases()
            }
        }
    }

    private fun refreshDatabases() {
        val filesDir = context.filesDir ?: return
        val kdbxFiles = filesDir.listFiles { file ->
            file.extension.equals("kdbx", ignoreCase = true)
        } ?: emptyArray()

        val currentActivePath = databaseSession.currentFile?.absolutePath

        val list = if (kdbxFiles.isEmpty()) {
            // 如果本地尚无 kdbx 文件，提供一个占位默认库描述供 UI 引导
            listOf(
                VaultDatabaseInfo(
                    id = "default_vault",
                    name = "默认密码库.kdbx",
                    path = File(filesDir, "default_vault.kdbx").absolutePath,
                    isRemote = false,
                    syncType = "本地存储",
                    lastOpenedAt = "尚未创建",
                    fileSizeFormatted = "0 KB",
                    isActive = false,
                    encryptionPreset = "AES-256 + Argon2id"
                )
            )
        } else {
            kdbxFiles.map { file ->
                val sizeKb = (file.length() / 1024).coerceAtLeast(1)
                val isActive = file.absolutePath == currentActivePath
                VaultDatabaseInfo(
                    id = file.name,
                    name = file.name,
                    path = file.absolutePath,
                    isRemote = false,
                    syncType = "本地设备存储",
                    lastOpenedAt = if (isActive) "当前使用中" else "本地就绪",
                    fileSizeFormatted = "$sizeKb KB",
                    isActive = isActive,
                    encryptionPreset = "AES-256 + Argon2id"
                )
            }
        }
        databasesFlow.value = list
    }

    override fun getDatabases(): Flow<List<VaultDatabaseInfo>> = databasesFlow.asStateFlow()

    override suspend fun selectDatabase(id: String) {
        val filesDir = context.filesDir ?: return
        val targetFile = File(filesDir, id)
        if (targetFile.exists()) {
            // 选定数据库，更新激活态
            databasesFlow.value = databasesFlow.value.map { db ->
                db.copy(isActive = db.id == id)
            }
        }
    }

    override suspend fun createDatabase(
        name: String,
        masterPassword: String,
        keyFile: Boolean,
        preset: String
    ) {
        val filesDir = context.filesDir ?: return
        val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
        val targetFile = File(filesDir, fileName)

        val useArgon2 = !preset.contains("AES-KDF", ignoreCase = true)
        val passwordChars = masterPassword.toCharArray()
        try {
            databaseSession.create(
                file = targetFile,
                name = name.removeSuffix(".kdbx"),
                passwordChars = passwordChars,
                useArgon2 = useArgon2
            )
            refreshDatabases()
        } finally {
            passwordChars.fill('0')
        }
    }

    override suspend fun removeDatabase(id: String) {
        val filesDir = context.filesDir ?: return
        val targetFile = File(filesDir, id)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (databaseSession.currentFile?.name == id) {
            databaseSession.close()
        }
        refreshDatabases()
    }

    override suspend fun importExternalDatabase(name: String, path: String, syncType: String) {
        val externalFile = File(path)
        val filesDir = context.filesDir ?: return
        val destFile = File(filesDir, externalFile.name)
        if (externalFile.exists() && externalFile.absolutePath != destFile.absolutePath) {
            externalFile.copyTo(destFile, overwrite = true)
        }
        refreshDatabases()
    }

    override fun getGroups(): Flow<List<VaultGroup>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                listOf(RECYCLE_BIN_GROUP)
            } else {
                val allKdbxGroups = db.rootGroup.allGroups()
                val uiGroups = allKdbxGroups.map { kdbxGroup ->
                    VaultGroup(
                        id = kdbxGroup.id.toHexString(),
                        name = kdbxGroup.name,
                        parentId = kdbxGroup.parentGroupId?.toHexString(),
                        iconName = "folder",
                        updatedAt = formatInstant(kdbxGroup.times.lastModificationTime),
                        createdAt = formatInstant(kdbxGroup.times.creationTime),
                        isRecycleBin = false
                    )
                }
                uiGroups + RECYCLE_BIN_GROUP
            }
        }
    }

    override suspend fun saveGroup(group: VaultGroup) {
        val kdbxGroup = KdbxGroup(
            id = parseUuidOrRandom(group.id),
            parentGroupId = group.parentId?.let { parseUuidOrNull(it) },
            name = group.name,
            iconId = 48
        )
        databaseSession.saveGroup(kdbxGroup)
        databaseSession.save()
    }

    override suspend fun deleteGroup(id: String) {
        if (id == RECYCLE_BIN_GROUP_ID) return
        val uuid = parseUuidOrNull(id) ?: return
        databaseSession.deleteGroup(uuid)
        databaseSession.save()
    }

    override fun getEntries(): Flow<List<UiVaultEntry>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                emptyList()
            } else {
                val recycled = recycledEntryIds.value
                db.rootGroup.allEntries().map { kdbxEntry ->
                    val entryIdHex = kdbxEntry.id.toHexString()
                    val isRecycled = entryIdHex in recycled
                    val effectiveGroupId = if (isRecycled) RECYCLE_BIN_GROUP_ID else kdbxEntry.parentGroupId?.toHexString()

                    mapKdbxEntryToUi(kdbxEntry, effectiveGroupId)
                }
            }
        }
    }

    override fun getEntry(id: String): Flow<UiVaultEntry?> {
        return getEntries().map { list -> list.find { it.id == id } }
    }

    override suspend fun saveEntry(entry: UiVaultEntry) {
        val kdbxEntry = mapUiEntryToKdbx(entry)
        databaseSession.saveEntry(kdbxEntry)
        databaseSession.save()
    }

    override suspend fun deleteEntry(id: String) {
        val currentRecycled = recycledEntryIds.value
        if (id in currentRecycled) {
            // 已在回收站中，物理彻底删除
            recycledEntryIds.value = currentRecycled - id
            val uuid = parseUuidOrNull(id) ?: return
            databaseSession.deleteEntry(uuid)
            databaseSession.save()
        } else {
            // 移入回收站
            recycledEntryIds.value = currentRecycled + id
        }
    }

    override suspend fun restoreEntry(id: String) {
        recycledEntryIds.value = recycledEntryIds.value - id
    }

    override suspend fun emptyRecycleBin() {
        val toDelete = recycledEntryIds.value
        recycledEntryIds.value = emptySet()
        for (id in toDelete) {
            val uuid = parseUuidOrNull(id) ?: continue
            databaseSession.deleteEntry(uuid)
        }
        databaseSession.save()
    }

    override suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?) {
        val uuidSet = entryIds.mapNotNull { parseUuidOrNull(it) }.toSet()
        val targetUuid = targetGroupId?.let { parseUuidOrNull(it) }
        databaseSession.batchMoveEntries(uuidSet, targetUuid)
        databaseSession.save()
    }

    override suspend fun batchDeleteEntries(entryIds: Set<String>) {
        val currentRecycled = recycledEntryIds.value
        val toPermanentDelete = entryIds.filter { it in currentRecycled }
        val toRecycle = entryIds.filter { it !in currentRecycled }

        recycledEntryIds.value = (currentRecycled - toPermanentDelete.toSet()) + toRecycle.toSet()

        if (toPermanentDelete.isNotEmpty()) {
            val uuidSet = toPermanentDelete.mapNotNull { parseUuidOrNull(it) }.toSet()
            databaseSession.batchDeleteEntries(uuidSet)
            databaseSession.save()
        }
    }

    private fun mapKdbxEntryToUi(entry: KdbxEntry, effectiveGroupId: String?): UiVaultEntry {
        val passwordStr = entry.password?.readString().orEmpty()
        val masked = if (passwordStr.isEmpty()) "" else "••••••••••••••••"

        val uiCustomFields = entry.customFields.map { cf ->
            UiCustomField(
                id = "${entry.id.toHexString()}_${cf.key}",
                key = cf.key,
                value = cf.value.readString(),
                isProtected = cf.isProtected
            )
        }

        val uiRevisions = entry.history.map { h ->
            UiEntryRevision(
                id = h.id.toHexString(),
                modifiedAt = formatInstant(h.times.lastModificationTime),
                summary = "历史修订",
                username = h.userName,
                passwordPlain = h.password?.readString().orEmpty(),
                notes = h.notes
            )
        }

        return UiVaultEntry(
            id = entry.id.toHexString(),
            title = entry.title,
            username = entry.userName,
            passwordPlain = passwordStr,
            passwordMasked = masked,
            url = entry.url,
            notes = entry.notes,
            groupId = effectiveGroupId,
            iconName = "key",
            updatedAt = formatInstant(entry.times.lastModificationTime),
            createdAt = formatInstant(entry.times.creationTime),
            customFields = uiCustomFields,
            revisions = uiRevisions
        )
    }

    private fun mapUiEntryToKdbx(entry: UiVaultEntry): KdbxEntry {
        val fields = mutableMapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(entry.title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(entry.username, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(entry.passwordPlain, isProtected = true),
            KdbxConstants.Fields.URL to ProtectedString(entry.url, isProtected = false),
            KdbxConstants.Fields.NOTES to ProtectedString(entry.notes, isProtected = false)
        )

        val customFields = entry.customFields.map { cf ->
            KdbxCustomField(
                key = cf.key,
                value = ProtectedString(cf.value, isProtected = cf.isProtected)
            )
        }

        return KdbxEntry(
            id = parseUuidOrRandom(entry.id),
            parentGroupId = entry.groupId?.let { if (it == RECYCLE_BIN_GROUP_ID) null else parseUuidOrNull(it) },
            fields = fields,
            customFields = customFields
        )
    }

    private fun parseUuidOrRandom(id: String): KdbxUuid {
        return parseUuidOrNull(id) ?: KdbxUuid.random()
    }

    private fun parseUuidOrNull(id: String): KdbxUuid? {
        return try {
            KdbxUuid.fromHexString(id)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatInstant(instant: java.time.Instant): String {
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        return dtf.format(instant.atZone(ZoneId.systemDefault()))
    }

    companion object {
        const val RECYCLE_BIN_GROUP_ID = "group_recycle_bin"

        val RECYCLE_BIN_GROUP = VaultGroup(
            id = RECYCLE_BIN_GROUP_ID,
            name = "回收站",
            iconName = "delete",
            isRecycleBin = true
        )
    }
}
