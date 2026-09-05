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
    private val databaseSession: DatabaseSession
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

    override suspend fun unlockActiveDatabase(passwordChars: CharArray): com.keepasskey.core.result.KdbxResult<Unit> {
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

        val result = databaseSession.open(targetFile, passwordChars)
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

    override suspend fun saveGroup(group: VaultGroup) {
        val kdbxGroup = KdbxGroup(
            id = parseUuidOrRandom(group.id),
            parentGroupId = group.parentId?.let { parseUuidOrNull(it) },
            name = group.name,
            iconId = if (group.isRecycleBin) 43 else 48
        )
        databaseSession.saveGroup(kdbxGroup)
        databaseSession.save()
    }

    override suspend fun deleteGroup(id: String) {
        val uuid = parseUuidOrNull(id) ?: return
        val db = databaseSession.databaseFlow.first() ?: return
        if (uuid == db.recycleBinUuid) return
        databaseSession.deleteGroup(uuid)
        databaseSession.save()
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

    override suspend fun saveEntry(entry: UiVaultEntry, passwordChars: CharArray?) {
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

            val pendingNewEntry = existing.copy(
                parentGroupId = targetParentId,
                fields = mergedFields,
                customFields = mergedCustomFields
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
            val kdbxEntry = mapUiEntryToKdbx(entry, passwordChars)
            databaseSession.saveEntry(kdbxEntry)
        }
        databaseSession.save()
    }

    override suspend fun deleteEntry(id: String) {
        val uuid = parseUuidOrNull(id) ?: return
        val db = databaseSession.databaseFlow.first() ?: return
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid } ?: return

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
        databaseSession.save()
    }

    override suspend fun restoreEntry(id: String) {
        val uuid = parseUuidOrNull(id) ?: return
        val db = databaseSession.databaseFlow.first() ?: return
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid } ?: return

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
        databaseSession.save()
    }

    override suspend fun emptyRecycleBin() {
        val db = databaseSession.databaseFlow.first() ?: return
        val binUuid = db.recycleBinUuid
        val binGroup = db.rootGroup.allGroups().firstOrNull {
            (binUuid != null && it.id == binUuid) || it.name == RECYCLE_BIN_NAME || it.name.equals("Recycle Bin", ignoreCase = true)
        } ?: return

        val entriesToDelete = binGroup.allEntries()
        if (entriesToDelete.isEmpty()) return

        val entryIds = entriesToDelete.map { it.id }.toSet()
        databaseSession.batchDeleteEntries(entryIds)
        databaseSession.updateDatabaseMeta { cur ->
            val tombstones = entryIds.map { DeletedObject(it, Instant.now()) }
            cur.copy(deletedObjects = cur.deletedObjects + tombstones)
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
        val db = databaseSession.databaseFlow.first() ?: return
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

        databaseSession.save()
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
            revisions = uiRevisions
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

    private fun mapIconIdToName(iconId: Int): String {
        return when (iconId) {
            0, 48 -> "key"
            1 -> "web"
            2 -> "email"
            43 -> "delete"
            49 -> "folder"
            else -> "key"
        }
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

    private fun mapUiEntryToKdbx(entry: UiVaultEntry, passwordChars: CharArray?): KdbxEntry {
        val fields = mutableMapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(entry.title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(entry.username, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(passwordChars ?: CharArray(0), isProtected = true),
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
            parentGroupId = entry.groupId?.let { parseUuidOrNull(it) },
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
        databaseSession.save()
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
        databaseSession.save()
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
            databaseSession.save()
        } finally {
            java.util.Arrays.fill(passwordChars, '0')
        }
    }

    companion object {
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
