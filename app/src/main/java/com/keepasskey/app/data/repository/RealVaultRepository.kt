package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.otp.OtpEngine
import com.keepasskey.core.otp.ParsedTotpConfig
import com.keepasskey.core.otp.TotpKeyUriParser
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
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
    private val databaseSession: DatabaseSession,
    private val debugLog: com.keepasskey.app.data.logger.DebugLogBuffer
) : VaultRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val databasesFlow = MutableStateFlow<List<VaultDatabaseInfo>>(emptyList())

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

    override suspend fun unlockActiveDatabase(
        passwordChars: CharArray,
        readOnly: Boolean
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val filesDir = context.filesDir ?: return com.keepasskey.core.result.KdbxResult.Failure(
            IllegalStateException("No filesDir"),
            "内部存储目录不可用"
        )
        val activeDb = databasesFlow.value.firstOrNull { it.isActive }
            ?: databasesFlow.value.firstOrNull()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                "请先选择或创建密码库"
            )

        val targetFile = File(activeDb.path)
        if (!targetFile.exists()) {
            // 文件尚不存在时初始化创建
            val createResult = databaseSession.create(
                file = targetFile,
                name = activeDb.name.removeSuffix(".kdbx"),
                passwordChars = passwordChars,
                useArgon2 = true
            )
            refreshDatabases()
            return createResult
        }

        val result = databaseSession.open(targetFile, passwordChars, readOnly = readOnly)
        if (result is com.keepasskey.core.result.KdbxResult.Success) {
            refreshDatabases()
        }
        return result
    }

    override suspend fun lockDatabase() {
        databaseSession.lock()
        refreshDatabases()
    }

    override fun isLocked(): Boolean {
        return databaseSession.state.value != DatabaseSession.SessionState.OPENED
    }

    override suspend fun createDatabase(
        name: String,
        masterPassword: String,
        keyFile: Boolean,
        preset: String
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val filesDir = context.filesDir ?: return com.keepasskey.core.result.KdbxResult.Failure(
            IllegalStateException("No filesDir"),
            "内部存储目录不可用"
        )
        val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
        val targetFile = File(filesDir, fileName)

        val useArgon2 = !preset.contains("AES-KDF", ignoreCase = true)
        val passwordChars = masterPassword.toCharArray()
        try {
            val result = databaseSession.create(
                file = targetFile,
                name = name.removeSuffix(".kdbx"),
                passwordChars = passwordChars,
                useArgon2 = useArgon2
            )
            refreshDatabases()
            return result
        } finally {
            passwordChars.fill('0')
        }
    }

    override suspend fun removeDatabase(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        val filesDir = context.filesDir
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("No filesDir"), "内部存储目录不可用")
        val targetFile = File(filesDir, id)
        return try {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (databaseSession.currentFile?.name == id) {
                databaseSession.close()
            }
            refreshDatabases()
            com.keepasskey.core.result.KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            com.keepasskey.core.result.KdbxResult.Failure(t, "移除密码库失败: ${t.message}")
        }
    }

    override suspend fun importExternalDatabase(
        name: String,
        path: String,
        syncType: String
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val externalFile = File(path)
        val filesDir = context.filesDir
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("No filesDir"), "内部存储目录不可用")
        val destFile = File(filesDir, externalFile.name)
        return try {
            if (externalFile.exists() && externalFile.absolutePath != destFile.absolutePath) {
                externalFile.copyTo(destFile, overwrite = true)
            }
            refreshDatabases()
            com.keepasskey.core.result.KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            com.keepasskey.core.result.KdbxResult.Failure(t, "导入数据库失败: ${t.message}")
        }
    }

    override fun getGroups(): Flow<List<VaultGroup>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                listOf(RECYCLE_BIN_GROUP)
            } else {
                val binUuid = db.recycleBinUuid
                val allKdbxGroups = db.rootGroup.allGroups()
                val uiGroups = allKdbxGroups.map { kdbxGroup ->
                    val isRecycle = (binUuid != null && kdbxGroup.id == binUuid) ||
                            kdbxGroup.name == RECYCLE_BIN_NAME ||
                            kdbxGroup.name.equals("Recycle Bin", ignoreCase = true)
                    VaultGroup(
                        id = kdbxGroup.id.toHexString(),
                        name = kdbxGroup.name,
                        parentId = kdbxGroup.parentGroupId?.toHexString(),
                        iconName = if (isRecycle || kdbxGroup.iconId == 43) "delete" else "folder",
                        updatedAt = formatInstant(kdbxGroup.times.lastModificationTime),
                        createdAt = formatInstant(kdbxGroup.times.creationTime),
                        isRecycleBin = isRecycle
                    )
                }
                uiGroups
            }
        }
    }

    override suspend fun saveGroup(group: VaultGroup): com.keepasskey.core.result.KdbxResult<Unit> {
        val kdbxGroup = KdbxGroup(
            id = parseUuidOrRandom(group.id),
            parentGroupId = group.parentId?.let { parseUuidOrNull(it) },
            name = group.name,
            // 断点7 整改：分组图标按名称映射到 KDBX 标准图标 ID（回收站强制 43 TrashBin）
            iconId = if (group.isRecycleBin) ICON_TRASH_BIN else mapIconNameToId(group.iconName, fallbackId = ICON_FOLDER)
        )
        databaseSession.saveGroup(kdbxGroup)
        return persistSession()
    }

    override suspend fun deleteGroup(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        val uuid = parseUuidOrNull(id)
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("无效的分组 ID"), "分组不存在")
        val db = databaseSession.databaseFlow.first()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("数据库未解锁"), "数据库未解锁")
        if (uuid == db.recycleBinUuid) {
            return com.keepasskey.core.result.KdbxResult.Success(Unit)
        }
        val targetGroup = db.rootGroup.allGroups().firstOrNull { it.id == uuid }
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("分组不存在"), "分组不存在")

        val alreadyInsideBin = db.recycleBinUuid?.let { binUuid ->
            var parentId = targetGroup.parentGroupId
            while (parentId != null) {
                if (parentId == binUuid) return@let true
                parentId = db.rootGroup.allGroups().firstOrNull { it.id == parentId }?.parentGroupId
            }
            false
        } ?: false

        if (alreadyInsideBin || !db.recycleBinEnabled) {
            // 已在回收站内（或回收站被禁用）：物理删除整组并记录 DeletedObject 墓碑
            databaseSession.deleteGroup(uuid)
            databaseSession.updateDatabaseMeta { cur ->
                cur.copy(deletedObjects = cur.deletedObjects + DeletedObject(id = uuid, deletionTime = Instant.now()))
            }
        } else {
            // 标准回收站语义：整组（含子内容）移入库内回收站组，不产生墓碑
            val binGroup = getOrCreateRecycleBinGroup()
            val moved = targetGroup.copy(
                parentGroupId = binGroup.id,
                previousParentGroup = targetGroup.parentGroupId,
                times = targetGroup.times.copy(lastModificationTime = Instant.now())
            )
            databaseSession.deleteGroup(uuid)
            databaseSession.saveGroup(moved)
        }
        return persistSession()
    }

    override fun getEntries(): Flow<List<UiVaultEntry>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                emptyList()
            } else {
                db.rootGroup.allEntries().map { kdbxEntry ->
                    mapKdbxEntryToUi(kdbxEntry, db)
                }
            }
        }
    }

    override fun getEntry(id: String): Flow<UiVaultEntry?> {
        return getEntries().map { list -> list.find { it.id == id } }
    }

    override suspend fun saveEntry(
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecret: String?
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
        val targetUuid = parseUuidOrNull(entry.id)
        val existing = if (targetUuid != null && db != null) {
            db.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        } else null

        if (existing != null) {
            // 既有条目做合并更新：保留既有元数据与属性，更新提交字段，接入 HistoryManager
            // M1 整改：密码仅在显式提交（passwordChars 非空）时更新，否则保留既有密码
            val mergedFields = existing.fields.toMutableMap().apply {
                put(KdbxConstants.Fields.TITLE, ProtectedString(entry.title, isProtected = false))
                put(KdbxConstants.Fields.USER_NAME, ProtectedString(entry.username, isProtected = false))
                passwordChars?.let {
                    put(KdbxConstants.Fields.PASSWORD, ProtectedString(it, isProtected = true))
                }
                put(KdbxConstants.Fields.URL, ProtectedString(entry.url, isProtected = false))
                put(KdbxConstants.Fields.NOTES, ProtectedString(entry.notes, isProtected = false))
                // 断点4 整改：TOTP 种子显式提交（null=未修改；空串=清除；非空=写标准 otp 字段）
                if (totpSecret != null) {
                    if (totpSecret.isBlank()) {
                        remove(KdbxConstants.Fields.OTP)
                    } else {
                        put(KdbxConstants.Fields.OTP, ProtectedString(totpSecret.trim(), isProtected = false))
                    }
                }
            }

            val uiCustomList = entry.customFields.map { cf ->
                // F2 整改：UI 投影中受保护字段的明文恒为空（按需解密），回写时「空值」视为未修改，
                // 回填既有条目的真实值——防止详情页回滚等携带掩码投影的保存路径清空受保护字段
                val effectiveValue = if (cf.isProtected && cf.value.isEmpty()) {
                    existing.customFields.firstOrNull { it.key == cf.key }?.value?.readString().orEmpty()
                } else {
                    cf.value
                }
                KdbxCustomField(cf.key, ProtectedString(effectiveValue, isProtected = cf.isProtected))
            }
            val uiKeys = uiCustomList.map { it.key }.toSet()
            // 保留既有条目中未在 UI 覆盖的系统字段（例如 Passkey 属性等）
            val preservedCustom = existing.customFields.filter { ef -> ef.key !in uiKeys }
            val mergedCustomFields = uiCustomList + preservedCustom

            val targetParentId = entry.groupId?.let { parseUuidOrNull(it) } ?: existing.parentGroupId
            val isParentChanged = targetParentId != existing.parentGroupId

            // 断点1-2 整改：附件全链路——UI 侧新附件（data 非空）直接随条目提交，
            // 已落库附件（data 为空）按名称匹配既有引用保留 refIndex；
            // UI 中被移除的附件不再出现在列表里，即自然从条目上删除（二进制池在保存时去重重建）
            val mergedAttachments = entry.attachments.map { ui ->
                if (ui.data != null) {
                    KdbxAttachment(name = ui.fileName, data = ui.data, isProtected = false)
                } else {
                    existing.attachments.firstOrNull { it.name == ui.fileName }
                        ?: KdbxAttachment(name = ui.fileName, data = byteArrayOf())
                }
            }

            // 断点7 整改：图标落盘——把 UI 图标名映射回 KDBX 标准 iconId
            val newIconId = mapIconNameToId(entry.iconName, fallbackId = existing.iconId)

            // KP2A 能力补齐：tags / overrideUrl / AutoType 序列
            val mergedAutoType = mergeAutoType(existing.autoType, entry.autoTypeSequence)

            val pendingNewEntry = existing.copy(
                parentGroupId = targetParentId,
                fields = mergedFields,
                customFields = mergedCustomFields,
                attachments = mergedAttachments,
                iconId = newIconId,
                tags = entry.tags,
                overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() },
                autoType = mergedAutoType
            )

            val maxHistory = db?.historyMaxItems ?: 10
            val finalEntry = HistoryManager.recordHistorySnapshot(
                currentEntry = existing,
                newEntry = pendingNewEntry,
                maxHistoryItems = maxHistory
            )

            if (isParentChanged) {
                databaseSession.deleteEntry(existing.id)
            }
            databaseSession.saveEntry(finalEntry)
        } else {
            // 新建条目
            val kdbxEntry = mapUiEntryToKdbx(entry, passwordChars, totpSecret)
            databaseSession.saveEntry(kdbxEntry)
        }
        return persistSession()
    }

    override suspend fun deleteEntry(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        val uuid = parseUuidOrNull(id)
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("无效的条目 ID"), "条目不存在")
        val db = databaseSession.databaseFlow.first()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("数据库未解锁"), "数据库未解锁")
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("条目不存在"), "条目不存在")

        val binUuid = db.recycleBinUuid
        val isAlreadyInRecycle = (binUuid != null && entry.parentGroupId == binUuid) ||
                (entry.parentGroupId != null && db.rootGroup.allGroups().any {
                    it.id == entry.parentGroupId && (it.name == RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true))
                })

        if (isAlreadyInRecycle || !db.recycleBinEnabled) {
            // 已在回收站内或禁用回收站：物理删除并记录 DeletedObject 墓碑
            databaseSession.deleteEntry(uuid)
            databaseSession.updateDatabaseMeta { cur ->
                val tombstone = DeletedObject(id = uuid, deletionTime = Instant.now())
                cur.copy(deletedObjects = cur.deletedObjects + tombstone)
            }
        } else {
            // 移入标准库内回收站组
            val binGroup = getOrCreateRecycleBinGroup()
            val moved = entry.copy(
                parentGroupId = binGroup.id,
                previousParentGroup = entry.parentGroupId,
                times = entry.times.copy(lastModificationTime = Instant.now())
            )
            databaseSession.deleteEntry(uuid)
            databaseSession.saveEntry(moved)
        }
        return persistSession()
    }

    override suspend fun restoreEntry(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        val uuid = parseUuidOrNull(id)
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("无效的条目 ID"), "条目不存在")
        val db = databaseSession.databaseFlow.first()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("数据库未解锁"), "数据库未解锁")
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("条目不存在"), "条目不存在")

        val allGroups = db.rootGroup.allGroups()
        val targetParentId = entry.previousParentGroup?.takeIf { prevId -> allGroups.any { it.id == prevId } }
            ?: db.rootGroup.id

        val restored = entry.copy(
            parentGroupId = targetParentId,
            previousParentGroup = null,
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.deleteEntry(uuid)
        databaseSession.saveEntry(restored)
        return persistSession()
    }

    override suspend fun emptyRecycleBin(): com.keepasskey.core.result.KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("数据库未解锁"), "数据库未解锁")
        val binUuid = db.recycleBinUuid
        val binGroup = db.rootGroup.allGroups().firstOrNull {
            (binUuid != null && it.id == binUuid) || it.name == RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        } ?: return com.keepasskey.core.result.KdbxResult.Success(Unit)

        // 断点9 整改：清空回收站必须覆盖其子分组——递归收集子树内全部条目，
        // 子分组本身物理删除并记录墓碑（KeePassDX 语义：回收站清空即整棵清空）
        val entriesToDelete = binGroup.allEntries()
        val subgroupsToDelete = binGroup.allGroups().filter { it.id != binGroup.id }
        if (entriesToDelete.isEmpty() && subgroupsToDelete.isEmpty()) {
            return com.keepasskey.core.result.KdbxResult.Success(Unit)
        }

        val entryIds = entriesToDelete.map { it.id }.toSet()
        if (entryIds.isNotEmpty()) {
            databaseSession.batchDeleteEntries(entryIds)
        }
        for (sub in subgroupsToDelete) {
            databaseSession.deleteGroup(sub.id)
        }
        databaseSession.updateDatabaseMeta { cur ->
            val tombstones = entryIds.map { DeletedObject(it, Instant.now()) } +
                    subgroupsToDelete.map { DeletedObject(it.id, Instant.now()) }
            cur.copy(deletedObjects = cur.deletedObjects + tombstones)
        }
        return persistSession()
    }

    override suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?): com.keepasskey.core.result.KdbxResult<Unit> {
        val uuidSet = entryIds.mapNotNull { parseUuidOrNull(it) }.toSet()
        val targetUuid = targetGroupId?.let { parseUuidOrNull(it) }
        databaseSession.batchMoveEntries(uuidSet, targetUuid)
        return persistSession()
    }

    override suspend fun batchDeleteEntries(entryIds: Set<String>): com.keepasskey.core.result.KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(IllegalStateException("数据库未解锁"), "数据库未解锁")
        val binUuid = db.recycleBinUuid
        val allGroups = db.rootGroup.allGroups()
        val allEntries = db.rootGroup.allEntries().associateBy { it.id.toHexString() }

        val toPermanentDelete = mutableSetOf<KdbxUuid>()
        val toMoveToBin = mutableListOf<KdbxEntry>()

        for (id in entryIds) {
            val entry = allEntries[id] ?: continue
            val isAlreadyInRecycle = (binUuid != null && entry.parentGroupId == binUuid) ||
                    (entry.parentGroupId != null && allGroups.any {
                        it.id == entry.parentGroupId && (it.name == RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true))
                    })

            if (isAlreadyInRecycle || !db.recycleBinEnabled) {
                toPermanentDelete.add(entry.id)
            } else {
                toMoveToBin.add(entry)
            }
        }

        if (toMoveToBin.isNotEmpty()) {
            val binGroup = getOrCreateRecycleBinGroup()
            for (e in toMoveToBin) {
                val moved = e.copy(
                    parentGroupId = binGroup.id,
                    previousParentGroup = e.parentGroupId,
                    times = e.times.copy(lastModificationTime = Instant.now())
                )
                databaseSession.deleteEntry(e.id)
                databaseSession.saveEntry(moved)
            }
        }

        if (toPermanentDelete.isNotEmpty()) {
            databaseSession.batchDeleteEntries(toPermanentDelete)
            databaseSession.updateDatabaseMeta { cur ->
                val tombstones = toPermanentDelete.map { DeletedObject(it, Instant.now()) }
                cur.copy(deletedObjects = cur.deletedObjects + tombstones)
            }
        }

        return persistSession()
    }

    private suspend fun getOrCreateRecycleBinGroup(): KdbxGroup {
        val db = databaseSession.databaseFlow.first()
            ?: throw IllegalStateException("当前数据库未处于已解锁状态")

        if (db.recycleBinUuid != null) {
            val existingBin = db.rootGroup.allGroups().firstOrNull { it.id == db.recycleBinUuid }
            if (existingBin != null) {
                return existingBin
            }
        }

        val candidate = db.rootGroup.subgroups.firstOrNull {
            it.name == RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        } ?: db.rootGroup.allGroups().firstOrNull {
            it.name == RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        }

        if (candidate != null) {
            databaseSession.updateDatabaseMeta {
                it.copy(
                    recycleBinUuid = candidate.id,
                    recycleBinEnabled = true,
                    recycleBinChanged = Instant.now()
                )
            }
            return candidate
        }

        val newBinGroup = KdbxGroup(
            id = KdbxUuid.random(),
            parentGroupId = db.rootGroup.id,
            name = RECYCLE_BIN_NAME,
            iconId = 43
        )
        databaseSession.saveGroup(newBinGroup)
        databaseSession.updateDatabaseMeta {
            it.copy(
                recycleBinUuid = newBinGroup.id,
                recycleBinEnabled = true,
                recycleBinChanged = Instant.now()
            )
        }
        return newBinGroup
    }

    private fun mapKdbxEntryToUi(entry: KdbxEntry, db: KdbxDatabase?): UiVaultEntry {
        // M1 整改：不再将密码明文读入 UI 投影（全库明文驻留 StateFlow / 堆内存），
        // 密码仅在用户显式查看/复制时经 [getEntryPassword] 按需单条解密
        // F2 整改：受保护自定义字段（Passkey 私钥/TOTP 种子/恢复码等）同样不进投影，
        // 仅在用户显式查看/编辑时经 [getEntryProtectedField] 按需单条解密
        val uiCustomFields = entry.customFields.map { cf ->
            UiCustomField(
                id = "${entry.id.toHexString()}_${cf.key}",
                key = cf.key,
                value = if (cf.isProtected) "" else cf.value.readString(),
                isProtected = cf.isProtected
            )
        }

        val uiRevisions = entry.history.map { h ->
            UiEntryRevision(
                id = h.id.toHexString(),
                modifiedAt = formatInstant(h.times.lastModificationTime),
                summary = "历史修订",
                username = h.userName,
                notes = h.notes
            )
        }

        val binaryPool = db?.binaries?.map { it.data } ?: emptyList()
        val uiAttachments = entry.attachments.map { att ->
            val dataBytes = att.resolveData(binaryPool)
            val sizeKb = (dataBytes.size / 1024).coerceAtLeast(if (dataBytes.isNotEmpty()) 1 else 0)
            val sizeFormatted = if (dataBytes.size < 1024) "${dataBytes.size} B" else "$sizeKb KB"
            UiAttachment(
                id = "${entry.id.toHexString()}_${att.name}",
                fileName = att.name,
                fileSizeFormatted = sizeFormatted,
                mimeType = determineMimeType(att.name),
                addedAt = formatInstant(entry.times.creationTime)
            )
        }

        // 解析标准 OTP 或自定义字段中的 TOTP 配置
        val parsedTotp = parseTotpConfig(entry)

        val totpPeriod = parsedTotp?.period ?: 30
        val totpDigits = parsedTotp?.digits ?: 6
        val totpAlgorithm = parsedTotp?.algorithm ?: "SHA1"

        val currentRemaining = OtpEngine.getRemainingSeconds(periodSeconds = totpPeriod)
        // F2 整改：TOTP 种子不进 UiVaultEntry（种子 String 仅在本函数内瞬时存在，随 GC 回收），
        // 列表展示用验证码在此即时计算；验证器页经 [calculateEntryTotp] 按需重算
        val liveTotpCode = parsedTotp?.let { computeTotpCode(it) }

        val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
        val icon = mapIconIdToName(entry.iconId)

        return UiVaultEntry(
            id = entry.id.toHexString(),
            title = entry.title,
            username = entry.userName,
            passwordMasked = if (entry.password == null) "" else "••••••••••••••••",
            url = entry.url,
            isPasskey = passkeyData != null,
            passkeyRpId = passkeyData?.relyingPartyId,
            totpCode = liveTotpCode,
            totpRemainingSeconds = currentRemaining,
            totpPeriod = totpPeriod,
            totpDigits = totpDigits,
            totpAlgorithm = totpAlgorithm,
            notes = entry.notes,
            groupId = entry.parentGroupId?.toHexString(),
            iconName = icon,
            updatedAt = formatInstant(entry.times.lastModificationTime),
            createdAt = formatInstant(entry.times.creationTime),
            customFields = uiCustomFields,
            attachments = uiAttachments,
            revisions = uiRevisions,
            tags = entry.tags,
            autoTypeSequence = entry.autoType?.defaultSequence.orEmpty(),
            overrideUrl = entry.overrideUrl
        )
    }

    /** 解析条目中的 TOTP 配置（标准 otp 字段优先，回退 TOTP 开头的自定义字段） */
    private fun parseTotpConfig(entry: KdbxEntry): ParsedTotpConfig? {
        val otpRaw = entry.fields["otp"]?.readString()
            ?: entry.customFields.firstOrNull {
                it.key.equals("otp", ignoreCase = true) || it.key.startsWith("TOTP", ignoreCase = true)
            }?.value?.readString()
        return TotpKeyUriParser.parse(otpRaw)
    }

    /** 按配置即时计算 TOTP 验证码，配置非法或计算失败返回 null */
    private fun computeTotpCode(config: ParsedTotpConfig): String? {
        return try {
            val algo = when (config.algorithm.uppercase()) {
                "SHA256" -> OtpEngine.HashAlgorithm.SHA256
                "SHA512" -> OtpEngine.HashAlgorithm.SHA512
                else -> OtpEngine.HashAlgorithm.SHA1
            }
            OtpEngine.calculateTotp(
                secretKeyBase32 = config.secret,
                periodSeconds = config.period,
                digits = config.digits,
                algorithm = algo
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * KDBX 标准图标 ID → UI 图标名。
     * ID 以官方 KeePass PwIcon 枚举为准（格式层裁决者）：0=Key 1=World 3=NetworkServer 5=UserCommunication
     * 7=Notepad 13=MultiKeys 19=EMail 20=Configuration 26=Disk 29=TerminalEncrypted 30=Console
     * 32=ProgramIcons 35=WorldComputer 37=Homebanking 43=TrashBin 44=Note 48=Folder 51=LockOpen
     * 52=PaperLocked 58=UserKey 66=Money 67=Certificate 68=BlackBerry。
     * 原实现把 2(Warning) 误译为 email，本轮已纠正。
     */
    private fun mapIconIdToName(iconId: Int): String {
        return when (iconId) {
            0, 58 -> "key"
            1, 8, 16 -> "public"
            3 -> "wifi"
            5 -> "forum"
            4, 27, 47, 48, 49, 50 -> "folder"
            7, 22, 41, 44 -> "description"
            13 -> "vpn_key"
            19, 25, 40 -> "email"
            20, 34 -> "dns"
            26, 36 -> "database"
            29, 30, 33 -> "terminal"
            32 -> "code"
            35 -> "cloud"
            37, 66 -> "credit_card"
            43 -> "delete"
            51 -> "lock"
            52 -> "security"
            67 -> "work"
            68 -> "phone"
            else -> "key"
        }
    }

    /** UI 图标名 → KDBX 标准 iconId（与 [mapIconIdToName] 互为反演；未知名回退 [fallbackId]） */
    private fun mapIconNameToId(iconName: String, fallbackId: Int): Int {
        return when (iconName) {
            "key" -> 0
            "public" -> 1
            "wifi" -> 3
            "forum" -> 5
            "folder" -> 48
            "description" -> 44
            "vpn_key" -> 13
            "email" -> 19
            "dns" -> 20
            "database" -> 26
            "terminal" -> 29
            "code" -> 32
            "cloud" -> 35
            "credit_card" -> 37
            "lock" -> 51
            "security" -> 52
            "work" -> 67
            "phone" -> 68
            "account_balance" -> 66
            "delete" -> ICON_TRASH_BIN
            else -> fallbackId
        }
    }

    /**
     * 合并 AutoType 序列（KP2A 能力补齐）。
     * UI 仅编辑条目级默认序列；既有 associations/混淆配置原样保留；
     * 归约到全默认值时置 null，避免为空配置生成冗余节点。
     */
    private fun mergeAutoType(existing: com.keepasskey.core.model.KdbxAutoType?, uiSequence: String): com.keepasskey.core.model.KdbxAutoType? {
        val base = existing ?: return if (uiSequence.isBlank()) null else com.keepasskey.core.model.KdbxAutoType(defaultSequence = uiSequence.trim())
        val newSequence = uiSequence.trim()
        if (newSequence == base.defaultSequence) return base
        val merged = base.copy(defaultSequence = newSequence)
        return if (merged.enabled && merged.defaultSequence.isEmpty() &&
            merged.dataTransferObfuscation == 0 && merged.associations.isEmpty()
        ) null else merged
    }

    /**
     * H3 整改：会话落盘的唯一出口——save() 失败必须原样向上传播，
     * 禁止磁盘写失败被静默吞掉导致 UI 谎报保存成功、锁库后修改永久丢失。
     */
    private suspend fun persistSession(): com.keepasskey.core.result.KdbxResult<Unit> {
        val result = databaseSession.save()
        if (result is com.keepasskey.core.result.KdbxResult.Failure) {
            debugLog.error(TAG, "数据库保存失败: ${result.message}")
        }
        return result
    }

    private fun determineMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun mapUiEntryToKdbx(entry: UiVaultEntry, passwordChars: CharArray?, totpSecret: String?): KdbxEntry {
        val fields = mutableMapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(entry.title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(entry.username, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(passwordChars ?: CharArray(0), isProtected = true),
            KdbxConstants.Fields.URL to ProtectedString(entry.url, isProtected = false),
            KdbxConstants.Fields.NOTES to ProtectedString(entry.notes, isProtected = false)
        )
        if (!totpSecret.isNullOrBlank()) {
            fields[KdbxConstants.Fields.OTP] = ProtectedString(totpSecret.trim(), isProtected = false)
        }

        val customFields = entry.customFields.map { cf ->
            KdbxCustomField(
                key = cf.key,
                value = ProtectedString(cf.value, isProtected = cf.isProtected)
            )
        }

        // 断点1-2 整改：新建路径同样消费 UI 附件（携带真实字节，保存时经去重器入池）
        val attachments = entry.attachments.mapNotNull { ui ->
            ui.data?.let { KdbxAttachment(name = ui.fileName, data = it, isProtected = false) }
        }

        return KdbxEntry(
            id = parseUuidOrRandom(entry.id),
            parentGroupId = entry.groupId?.let { parseUuidOrNull(it) },
            iconId = mapIconNameToId(entry.iconName, fallbackId = ICON_KEY),
            fields = fields,
            customFields = customFields,
            tags = entry.tags,
            attachments = attachments,
            autoType = mergeAutoType(null, entry.autoTypeSequence),
            overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() }
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

    override suspend fun getKdbxEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return db.rootGroup.allEntries()
    }

    override suspend fun findEntriesForRpId(rpId: String): List<KdbxEntry> {
        val cleanTarget = DomainMatcher.extractDomain(rpId)
        return getKdbxEntries().filter { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            val passkeyMatch = passkey != null && DomainMatcher.isDomainMatch(passkey.relyingPartyId, cleanTarget)
            val urlMatch = entry.url.isNotBlank() && DomainMatcher.isDomainMatch(entry.url, cleanTarget)
            passkeyMatch || urlMatch
        }
    }

    override suspend fun findPasskeyByCredentialId(credentialId: String): KdbxEntry? {
        return getKdbxEntries().firstOrNull { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields)
            passkey?.credentialId == credentialId
        }
    }

    override suspend fun getEntryPassword(entryId: String): String? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        return entry?.password?.readString()
    }

    override suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val revisionUuid = parseUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        return entry?.history?.firstOrNull { it.id == revisionUuid }?.password?.readString()
    }

    override suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val revisionUuid = parseUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        val revision = entry.history.firstOrNull { it.id == revisionUuid } ?: return null
        // 断点8 整改：整修订快照投影 + 受保护字段解密回填（仅驻留回滚会话），
        // 使回滚保存时 title/url/自定义字段/TOTP/密码全字段真实还原
        val projection = mapKdbxEntryToUi(revision, currentDb)
        val decryptedFields = projection.customFields.map { cf ->
            if (cf.isProtected) {
                cf.copy(value = revision.customFields.firstOrNull { it.key == cf.key }?.value?.readString().orEmpty())
            } else {
                cf
            }
        }
        val totpRaw = revision.fields[KdbxConstants.Fields.OTP]?.readString()
            ?: revision.customFields.firstOrNull {
                it.key.equals("otp", ignoreCase = true) || it.key.startsWith("TOTP", ignoreCase = true)
            }?.value?.readString().orEmpty()
        return EntryRevisionSnapshot(
            entry = projection.copy(customFields = decryptedFields),
            totpSecret = totpRaw
        )
    }

    override suspend fun getEntryProtectedField(entryId: String, fieldKey: String): String? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        return entry.customFields.firstOrNull { it.key == fieldKey }?.value?.readString()
    }

    override suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // 种子仅在数据层内瞬时解析并参与计算，绝不随结果外泄
        val config = parseTotpConfig(entry) ?: return null
        val code = computeTotpCode(config) ?: return null
        return EntryTotpSnapshot(
            code = code,
            periodSeconds = config.period,
            digits = config.digits,
            algorithm = config.algorithm
        )
    }

    override suspend fun getEntryTotpSecret(entryId: String): String? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // 断点4 整改：与 parseTotpConfig 同源读取（otp 字段优先，回退 TOTP 开头的自定义字段），
        // 返回配置原文（otpauth:// URI 或 Base32 种子）供编辑页回填
        return entry.fields[KdbxConstants.Fields.OTP]?.readString()
            ?: entry.customFields.firstOrNull {
                it.key.equals("otp", ignoreCase = true) || it.key.startsWith("TOTP", ignoreCase = true)
            }?.value?.readString()
    }

    override suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray? {
        val targetUuid = parseUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        val binaryPool = currentDb.binaries.map { it.data }
        val attachment = entry.attachments.firstOrNull { it.name == fileName } ?: return null
        val bytes = attachment.resolveData(binaryPool)
        return if (bytes.isEmpty()) null else bytes.copyOf()
    }

    override fun isSessionReadOnly(): Boolean = databaseSession.isReadOnly

    override suspend fun saveNewPasskeyEntry(data: PasskeyData, boundPackage: String?): KdbxEntry {
        val title = "${data.userName}@${data.relyingPartyId}"
        val url = if (boundPackage.isNullOrBlank()) "https://${data.relyingPartyId}" else "android://$boundPackage"
        val fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(data.userName, isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)
        )
        val newEntry = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = null,
            fields = fields,
            customFields = data.toCustomFields()
        )
        databaseSession.saveEntry(newEntry)
        val saved = persistSession()
        if (saved is com.keepasskey.core.result.KdbxResult.Failure) {
            debugLog.warn(TAG, "Passkey 条目创建成功但落盘失败: ${saved.message}")
        }
        return newEntry
    }

    override suspend fun patchPasskeySignCount(entryId: String, newCount: Int) {
        val targetUuid = parseUuidOrNull(entryId) ?: return
        val currentDb = databaseSession.databaseFlow.first() ?: return
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return

        var found = false
        val updatedCustomFields = entry.customFields.map { cf ->
            if (cf.key == PasskeyData.FIELD_SIGN_COUNT) {
                found = true
                KdbxCustomField(cf.key, ProtectedString(newCount.toString(), isProtected = false))
            } else {
                cf
            }
        }.toMutableList()

        if (!found) {
            updatedCustomFields.add(KdbxCustomField(PasskeyData.FIELD_SIGN_COUNT, ProtectedString(newCount.toString(), isProtected = false)))
        }

        val updatedEntry = entry.copy(
            customFields = updatedCustomFields,
            times = entry.times.withModified()
        )
        databaseSession.saveEntry(updatedEntry)
        persistSession()
    }

    override suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    ) {
        try {
            val domain = webDomain?.takeIf { it.isNotBlank() }
            val allEntries = getKdbxEntries()

            val matchedEntry = allEntries.firstOrNull { entry ->
                val matchDomain = domain != null && entry.url.isNotBlank() && DomainMatcher.isDomainMatch(entry.url, domain)
                // L1 整改：包名匹配仅走 DomainMatcher 严格点号边界（含 android:// scheme 剥离），
                // 移除 title/notes.contains 启发式
                val matchPackage = entry.url.isNotBlank() && DomainMatcher.isPackageMatch(entry.url, packageName)
                (matchDomain || matchPackage) && (entry.userName == username || entry.userName.isEmpty())
            }

            val pwdProtected = ProtectedString(passwordChars, isProtected = true)
            if (matchedEntry != null) {
                var updated = matchedEntry.withField(KdbxConstants.Fields.PASSWORD, pwdProtected)
                if (updated.userName.isEmpty() && username.isNotBlank()) {
                    updated = updated.withField(KdbxConstants.Fields.USER_NAME, username)
                }
                databaseSession.saveEntry(updated)
            } else {
                val titleDomain = domain ?: packageName
                val title = if (username.isNotBlank()) "$username@$titleDomain" else titleDomain
                val url = if (domain != null) "https://$domain" else "android://$packageName"
                val fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
                    KdbxConstants.Fields.USER_NAME to ProtectedString(username, isProtected = false),
                    KdbxConstants.Fields.PASSWORD to pwdProtected,
                    KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
                    KdbxConstants.Fields.NOTES to ProtectedString("Package: $packageName", isProtected = false)
                )
                val newEntry = KdbxEntry(
                    id = KdbxUuid.random(),
                    parentGroupId = null,
                    fields = fields
                )
                databaseSession.saveEntry(newEntry)
            }
            persistSession()
        } finally {
            java.util.Arrays.fill(passwordChars, '0')
        }
    }

    companion object {
        private const val TAG = "RealVaultRepository"

        /** KDBX 标准 PwIcon ID（官方 KeePass PwEnums.cs 裁决） */
        const val ICON_KEY = 0
        const val ICON_FOLDER = 48
        const val ICON_TRASH_BIN = 43

        const val RECYCLE_BIN_NAME = "回收站"
        const val RECYCLE_BIN_GROUP_ID = "group_recycle_bin"

        val RECYCLE_BIN_GROUP = VaultGroup(
            id = RECYCLE_BIN_GROUP_ID,
            name = RECYCLE_BIN_NAME,
            iconName = "delete",
            isRecycleBin = true
        )
    }
}
