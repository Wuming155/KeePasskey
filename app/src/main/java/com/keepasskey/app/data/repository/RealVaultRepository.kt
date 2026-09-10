package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.R
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
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
    private val debugLog: com.keepasskey.app.data.logger.DebugLogBuffer,
    // TASK-21：用户可见消息经 StringsProvider 资源解析（P3-23；单测注入假实现）
    private val strings: com.keepasskey.app.ui.model.StringsProvider
) : VaultRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // TASK-21 拆分：投影映射器与领域协调器（回收站/Passkey/模板），仓库仅保留 CRUD 编排
    private val entryMapper = VaultEntryMapper(strings)
    private val recycleBin = RecycleBinCoordinator(strings, databaseSession) { persistSession() }
    private val entryDuplicator = EntryDuplicateCoordinator(strings, databaseSession) { persistSession() }
    private val customIcons = CustomIconCoordinator(strings, databaseSession) { persistSession() }
    private val passkeyEntries = PasskeyEntryCoordinator(databaseSession, debugLog) { persistSession() }

    private val databasesFlow = MutableStateFlow<List<VaultDatabaseInfo>>(emptyList())

    init {
        // TASK-42 整改（P2-31）：构造期不再同步扫盘——@Singleton 构造发生在主线程，
        // listFiles 磁盘 IO 移至协程 + Dispatchers.IO；databasesFlow 初始为空列表，
        // 扫描完成后由 Flow 自然推送更新，观察者语义不变
        repositoryScope.launch { refreshDatabases() }
        // 观察会话，当数据库发生变化时同步刷新
        repositoryScope.launch {
            databaseSession.databaseFlow.collect {
                refreshDatabases()
            }
        }
    }

    private data class KnownDatabaseEntry(
        val id: String,
        val name: String,
        val path: String,
        val isRemote: Boolean,
        val syncType: String,
        val lastOpenedAt: String
    )

    private fun loadKnownDatabases(): List<KnownDatabaseEntry> {
        return try {
            val sp = context.getSharedPreferences("keepasskey_vault_meta", Context.MODE_PRIVATE) ?: return emptyList()
            val raw = sp.getString("known_databases_v1", null) ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            raw.split("\u0002").filter { it.isNotBlank() }.mapNotNull { record ->
                val fields = record.split("\u0001")
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

    private fun saveKnownDatabases(entries: List<KnownDatabaseEntry>) {
        try {
            val sp = context.getSharedPreferences("keepasskey_vault_meta", Context.MODE_PRIVATE) ?: return
            val encoded = entries.joinToString("\u0002") { entry ->
                listOf(
                    entry.id,
                    entry.name,
                    entry.path,
                    entry.isRemote.toString(),
                    entry.syncType,
                    entry.lastOpenedAt
                ).joinToString("\u0001")
            }
            sp.edit().putString("known_databases_v1", encoded).apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadActiveDbId(): String? {
        return try {
            context.getSharedPreferences("keepasskey_vault_meta", Context.MODE_PRIVATE)
                ?.getString("active_database_id", null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveActiveDbId(id: String?) {
        try {
            val sp = context.getSharedPreferences("keepasskey_vault_meta", Context.MODE_PRIVATE) ?: return
            if (id == null) {
                sp.edit().remove("active_database_id").apply()
            } else {
                sp.edit().putString("active_database_id", id).apply()
            }
        } catch (_: Throwable) {
        }
    }

    private suspend fun refreshDatabases() {
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

        val finalList = if (combined.isEmpty()) {
            emptyList()
        } else {
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
            if (list.none { it.isActive }) {
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
        databasesFlow.value = finalList
    }

    override fun getDatabases(): Flow<List<VaultDatabaseInfo>> = databasesFlow.asStateFlow()

    override suspend fun selectDatabase(id: String) {
        val current = databasesFlow.value
        val target = current.firstOrNull { it.id == id || it.path == id || it.name == id }
        if (target != null) {
            saveActiveDbId(target.id)
            databasesFlow.value = current.map { db ->
                db.copy(isActive = db.id == target.id)
            }
        }
    }

    override suspend fun unlockActiveDatabase(
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        readOnly: Boolean
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val activeDb = databasesFlow.value.firstOrNull { it.isActive }
            ?: databasesFlow.value.firstOrNull()
            ?: return KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                strings.get(R.string.repo_no_active_database)
            )

        val result = if (activeDb.path.startsWith("content://")) {
            val uri = Uri.parse(activeDb.path)
            databaseSession.openStream(
                pathIdentifier = activeDb.path,
                inputStreamProvider = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法打开数据库文件流: ${activeDb.name}")
                },
                saveWriter = { bytes ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                            os.write(bytes)
                            os.flush()
                        } ?: throw IOException("无法写入目标数据库文件: ${activeDb.name}")
                    }
                },
                passwordChars = passwordChars,
                keyFileData = keyFileData,
                readOnly = readOnly
            )
        } else {
            val targetFile = File(activeDb.path)
            if (!targetFile.exists()) {
                if (keyFileData != null) {
                    return KdbxResult.Failure(
                        IllegalArgumentException("数据库文件不存在: ${targetFile.absolutePath}"),
                        strings.get(R.string.repo_file_missing_with_keyfile)
                    )
                }
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
            databaseSession.open(targetFile, passwordChars, keyFileData, readOnly)
        }

        if (result is com.keepasskey.core.result.KdbxResult.Success) {
            refreshDatabases()
        }
        return result
    }

    override suspend fun changeMasterPassword(newPassword: CharArray): com.keepasskey.core.result.KdbxResult<Unit> {
        val result = databaseSession.changeCredentials(newPassword)
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
        masterPassword: CharArray,
        keyFile: Boolean,
        preset: String
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val filesDir = context.filesDir ?: return KdbxResult.Failure(
            IllegalStateException("No filesDir"),
            strings.get(R.string.repo_files_dir_unavailable)
        )
        val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
        val targetFile = File(filesDir, fileName)

        // H2 整改：主密码全程 CharArray（原实现接收 String 参数不可变驻留）；
        // 数组为借用语义，session.create 内部克隆缓存，调用方负责最终擦除
        val useArgon2 = !preset.contains("AES-KDF", ignoreCase = true)
        val result = databaseSession.create(
            file = targetFile,
            name = name.removeSuffix(".kdbx"),
            passwordChars = masterPassword,
            useArgon2 = useArgon2
        )
        if (result is KdbxResult.Success) {
            selectDatabase(fileName)
        }
        refreshDatabases()
        return result
    }

    override suspend fun removeDatabase(id: String): KdbxResult<Unit> {
        return try {
            val known = loadKnownDatabases()
            val matchExternal = known.find { it.id == id || it.path == id || it.name == id }
            if (matchExternal != null) {
                saveKnownDatabases(known.filter { it.id != matchExternal.id && it.path != matchExternal.path })
            }

            val filesDir = context.filesDir
            if (filesDir != null) {
                val targetFile = File(filesDir, id)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
            }

            if (databaseSession.currentFile?.name == id || databaseSession.currentPathIdentifier == id) {
                databaseSession.close()
            }
            if (loadActiveDbId() == id) {
                saveActiveDbId(null)
            }
            refreshDatabases()
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            KdbxResult.Failure(t, strings.get(R.string.repo_remove_failed, t.message ?: ""))
        }
    }

    override suspend fun importExternalDatabase(
        name: String,
        path: String,
        syncType: String
    ): KdbxResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                    // 部分外部 Provider 不支持持久化授权，容错继续
                }
            }
            val sanitizedName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
            val entry = KnownDatabaseEntry(
                id = path,
                name = sanitizedName,
                path = path,
                isRemote = syncType != "本地设备存储" && syncType != "系统文件选择器",
                syncType = syncType,
                lastOpenedAt = ""
            )
            val updated = loadKnownDatabases().filter { it.path != path && it.id != entry.id } + entry
            saveKnownDatabases(updated)
            saveActiveDbId(entry.id)
            refreshDatabases()
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            KdbxResult.Failure(t, strings.get(R.string.repo_import_failed, t.message ?: ""))
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
                        updatedAt = entryMapper.formatInstant(kdbxGroup.times.lastModificationTime),
                        createdAt = entryMapper.formatInstant(kdbxGroup.times.creationTime),
                        isRecycleBin = isRecycle
                    )
                }
                uiGroups
            }
        }
    }

    override suspend fun saveGroup(group: VaultGroup): com.keepasskey.core.result.KdbxResult<Unit> {
        val targetId = parseKdbxUuidOrRandom(group.id)
        // P0-1 灾难性缺陷修复：重命名/改图标路径曾以仅含 4 个字段的新建 KdbxGroup 直接
        // 覆盖既有分组（entries/subgroups 均为默认空列表），导致其全部子条目与子分组被清空。
        // 现先按 UUID 从会话中查找既有分组：命中则基于 existing.copy(...) 仅更新名称/图标/父组，
        // 子条目与子分组原样保留；未命中（真正的新建分组）才构造空分组。
        val existing = databaseSession.databaseFlow.first()
            ?.rootGroup
            ?.findGroup(targetId)
        val kdbxGroup = if (existing != null) {
            existing.copy(
                name = group.name,
                // 断点7 整改：分组图标按名称映射到 KDBX 标准图标 ID（回收站强制 43 TrashBin）；
                // 未知名回退既有图标，避免重命名时把已设置的图标意外重置为默认文件夹
                iconId = if (group.isRecycleBin) ICON_TRASH_BIN
                else entryMapper.mapIconNameToId(group.iconName, fallbackId = existing.iconId),
                // parentId 缺失时保留既有父组，防止空 parentId 把嵌套分组意外改挂到根组
                parentGroupId = group.parentId?.let { parseKdbxUuidOrNull(it) } ?: existing.parentGroupId
            )
        } else {
            KdbxGroup(
                id = targetId,
                parentGroupId = group.parentId?.let { parseKdbxUuidOrNull(it) },
                name = group.name,
                iconId = if (group.isRecycleBin) ICON_TRASH_BIN else entryMapper.mapIconNameToId(group.iconName, fallbackId = ICON_FOLDER)
            )
        }
        databaseSession.saveGroup(kdbxGroup)
        return persistSession()
    }

    override suspend fun deleteGroup(id: String): KdbxResult<Unit> = recycleBin.deleteGroup(id)

    override fun getEntries(): Flow<List<UiVaultEntry>> {
        return databaseSession.databaseFlow.map { db ->
            if (db == null) {
                emptyList()
            } else {
                db.rootGroup.allEntries().map { kdbxEntry ->
                    entryMapper.mapKdbxEntryToUi(kdbxEntry, db)
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
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        // 擦除契约（加解密审查 2026-09）：任何结果路径（成功/失败/异常）用毕清零传入副本，
        // 与 saveAutofillCredential / FakeVaultRepository 同一契约。
        // TASK-10：TOTP 种子与受保护自定义字段明文副本同样纳入擦除契约
        try {
            return saveEntryInternal(entry, passwordChars, totpSecretChars, protectedFieldChars)
        } finally {
            passwordChars?.fill('0')
            totpSecretChars?.fill('0')
            protectedFieldChars.values.forEach { it.fill('0') }
        }
    }

    private suspend fun saveEntryInternal(
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val db = databaseSession.databaseFlow.first()
        val targetUuid = parseKdbxUuidOrNull(entry.id)
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
                // TASK-10：TOTP 配置以 CharArray 显式提交（null=未修改保留既有；空数组=清除），
                // 明文仅在 ProtectedString 密封瞬间物化，中间副本即时擦除
                if (totpSecretChars != null) {
                    val trimmedTotp = totpSecretChars.trimmedCopy()
                    if (trimmedTotp.isEmpty()) {
                        remove(KdbxConstants.Fields.OTP)
                    } else {
                        put(KdbxConstants.Fields.OTP, ProtectedString(trimmedTotp, isProtected = false))
                    }
                    trimmedTotp.fill('0')
                }
            }

            val uiCustomList = entry.customFields.map { cf ->
                // TASK-10：受保护自定义字段编辑态明文以 CharArray 显式提交（键为字段编辑 id），
                // 仅用户显式编辑过的字段出现在 protectedFieldChars 中。
                // 擦除语义：submittedChars 归 saveEntry 的 finally 契约擦除；回填路径的
                // readChars 独占副本在密封后立即擦除
                val submittedChars = if (cf.isProtected) protectedFieldChars[cf.id] else null
                // F2 整改：UI 投影中受保护字段的明文恒为空（按需解密），未编辑的受保护字段
                // 回写时「空值」视为未修改，回填既有条目的真实值——防止详情页回滚等携带掩码
                // 投影的保存路径清空受保护字段
                val existingField = existing.customFields.firstOrNull { it.key == cf.key }
                when {
                    submittedChars != null ->
                        KdbxCustomField(cf.key, ProtectedString(submittedChars, isProtected = true))
                    cf.isProtected && cf.value.isEmpty() -> {
                        val backfillChars = existingField?.value?.readChars()
                        try {
                            KdbxCustomField(cf.key, ProtectedString(backfillChars ?: CharArray(0), isProtected = true))
                        } finally {
                            backfillChars?.fill('0')
                        }
                    }
                    else ->
                        KdbxCustomField(cf.key, ProtectedString(cf.value, isProtected = cf.isProtected))
                }
            }
            val uiKeys = uiCustomList.map { it.key }.toSet()
            // 保留既有条目中未在 UI 覆盖的系统字段（例如 Passkey 属性等）
            val preservedCustom = existing.customFields.filter { ef -> ef.key !in uiKeys }
            val mergedCustomFields = uiCustomList + preservedCustom

            val targetParentId = entry.groupId?.let { parseKdbxUuidOrNull(it) } ?: existing.parentGroupId
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
            val newIconId = entryMapper.mapIconNameToId(entry.iconName, fallbackId = existing.iconId)

            // KP2A 能力补齐：tags / overrideUrl / AutoType 序列
            val mergedAutoType = entryMapper.mergeAutoType(existing.autoType, entry.autoTypeSequence)

            val pendingNewEntry = existing.copy(
                parentGroupId = targetParentId,
                fields = mergedFields,
                customFields = mergedCustomFields,
                attachments = mergedAttachments,
                iconId = newIconId,
                // TASK-15：自定义图标引用以 UI 选择为准（null=清除引用，回退标准图标）
                customIconId = entry.customIconId?.let { parseKdbxUuidOrNull(it) },
                tags = entry.tags,
                overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() },
                autoType = mergedAutoType
            )

            // P3-4 整改：历史修剪遵从库级 Meta 配置（historyMaxItems / historyMaxSize），
            // 缺失时回退官方默认值，不再写死 10 条上限
            val finalEntry = HistoryManager.recordHistorySnapshot(
                currentEntry = existing,
                newEntry = pendingNewEntry,
                maxHistoryItems = db?.historyMaxItems ?: HistoryManager.DEFAULT_MAX_HISTORY_ITEMS,
                maxHistorySize = db?.historyMaxSize ?: HistoryManager.DEFAULT_MAX_HISTORY_SIZE
            )

            if (isParentChanged) {
                databaseSession.deleteEntry(existing.id)
            }
            databaseSession.saveEntry(finalEntry)
        } else {
            // 新建条目
            val kdbxEntry = entryMapper.mapUiEntryToKdbx(entry, passwordChars, totpSecretChars, protectedFieldChars)
            databaseSession.saveEntry(kdbxEntry)
        }
        return persistSession()
    }

    override suspend fun setEntryFavorite(
        entryId: String,
        favorite: Boolean
    ): KdbxResult<Unit> {
        // TASK-34 整改：收藏状态持久化至 KDBX 条目 customData（随库文件同步），
        // 直接改内存树并落盘——不经 HistoryManager，收藏切换不产生历史修订
        val uuid = parseKdbxUuidOrNull(entryId)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
                strings.get(R.string.repo_entry_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
                strings.get(R.string.repo_entry_not_found)
            )

        val updated = entry.copy(
            customData = if (favorite) {
                entry.customData + (FAVORITE_CUSTOM_DATA_KEY to "true")
            } else {
                entry.customData - FAVORITE_CUSTOM_DATA_KEY
            },
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.saveEntry(updated)
        return persistSession()
    }

    override suspend fun deleteEntry(id: String): KdbxResult<Unit> = recycleBin.deleteEntry(id)

    // TASK-16：条目克隆（全字段保真 + 新 UUID + 清历史），委托独立协调器
    override suspend fun duplicateEntry(id: String): KdbxResult<String> = entryDuplicator.duplicateEntry(id)

    // TASK-15：自定义图标上传（PNG 校验/去重/落库 Meta）与图标池快照
    override suspend fun addCustomIcon(pngBytes: ByteArray): KdbxResult<String> =
        customIcons.addCustomIcon(pngBytes)

    override suspend fun getCustomIconBytes(): Map<String, ByteArray> =
        databaseSession.databaseFlow.first()
            ?.let { customIcons.snapshotIconBytes(it) }
            ?: emptyMap()

    // TASK-17：{REF:...} 字段引用解析（仅消费点调用，投影层不展开）
    override suspend fun resolveFieldReferences(entryId: String, rawText: String): String? {
        if (!com.keepasskey.database.fieldref.FieldReferenceEngine.containsReference(rawText)) {
            return rawText
        }
        val uuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        // 条目存在性校验：引用解析仅对库内真实条目开放
        if (currentDb.rootGroup.allEntries().none { it.id == uuid }) return null
        return com.keepasskey.database.fieldref.FieldReferenceEngine.resolve(rawText, currentDb.rootGroup)
    }

    override suspend fun restoreEntry(id: String): KdbxResult<Unit> = recycleBin.restoreEntry(id)

    override suspend fun emptyRecycleBin(): KdbxResult<Unit> = recycleBin.emptyRecycleBin()

    override suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?): KdbxResult<Unit> {
        val uuidSet = entryIds.mapNotNull { parseKdbxUuidOrNull(it) }.toSet()
        val targetUuid = targetGroupId?.let { parseKdbxUuidOrNull(it) }
        databaseSession.batchMoveEntries(uuidSet, targetUuid)
        return persistSession()
    }

    override suspend fun batchDeleteEntries(entryIds: Set<String>): KdbxResult<Unit> =
        recycleBin.batchDeleteEntries(entryIds)

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

    override suspend fun getKdbxEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return db.rootGroup.allEntries()
    }

    override suspend fun findEntriesForRpId(rpId: String): List<KdbxEntry> =
        passkeyEntries.findEntriesForRpId(rpId)

    override suspend fun findPasskeyByCredentialId(credentialId: String): KdbxEntry? =
        passkeyEntries.findPasskeyByCredentialId(credentialId)

    /**
     * ISSUE-P2-15 兼容读取通道：把受保护值读成 String 并保证中间 CharArray 副本即时清零。
     *
     * 返回值本身仍是不可擦除的 String（UI 投影模型与待下线 String 接口的既有约束），
     * 但相对直接调用 `ProtectedString.readString()`，本通道不再让明文副本静默等待 GC。
     * 新代码应优先使用对应的 CharArray 借用通道。
     */
    private fun readErasableString(value: ProtectedString?): String? {
        val chars = value?.readChars() ?: return null
        return try {
            String(chars)
        } finally {
            chars.fill('0')
        }
    }

    /** ISSUE-P2-15：返回受保护值的 CharArray 独占副本（或 null），清零责任随借用契约移交调用方 */
    private fun readErasableChars(value: ProtectedString?): CharArray? = value?.readChars()

    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryPasswordChars(entryId)")
    )
    override suspend fun getEntryPassword(entryId: String): String? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // ISSUE-P2-15：不再直接 readString()，经 CharArray 独占副本中转并即时清零
        return readErasableString(entry?.password)
    }

    override suspend fun getEntryPasswordChars(entryId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // readChars() 返回独占副本（内部中间量已清零），清零责任随契约移交调用方
        return entry?.password?.readChars()
    }

    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryRevisionPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryRevisionPasswordChars(entryId, revisionId)")
    )
    override suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // ISSUE-P2-15：不再直接 readString()，经 CharArray 独占副本中转并即时清零
        return readErasableString(entry?.history?.firstOrNull { it.id == revisionUuid }?.password)
    }

    override suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid }
        // M2 整改：回滚路径全程 CharArray（readChars 返回独占副本，内部中间量已清零）
        return entry?.history?.firstOrNull { it.id == revisionUuid }?.password?.readChars()
    }

    override suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val revisionUuid = parseKdbxUuidOrNull(revisionId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        val revision = entry.history.firstOrNull { it.id == revisionUuid } ?: return null
        // 断点8 整改：整修订快照投影 + 受保护字段解密回填（仅驻留回滚会话），
        // 使回滚保存时 title/url/自定义字段/TOTP/密码全字段真实还原
        val projection = entryMapper.mapKdbxEntryToUi(revision, currentDb)
        // ISSUE-P2-15：受保护字段经 CharArray 独占副本中转（用毕清零）；UiCustomField.value 仍为
        // String（UI 投影模型已知约束），此处物化的 String 属投影边界、不可擦，见 ISSUE-P2-15 备注
        val decryptedFields = projection.customFields.map { cf ->
            if (cf.isProtected) {
                cf.copy(
                    value = readErasableString(
                        revision.customFields.firstOrNull { it.key == cf.key }?.value
                    ).orEmpty()
                )
            } else {
                cf
            }
        }
        // ISSUE-P2-15：TOTP 原文以 CharArray 独占副本返回，不再物化不可擦 String
        val totpRawChars = readErasableChars(revision.fields[KdbxConstants.Fields.OTP])
            ?: readErasableChars(
                revision.customFields.firstOrNull {
                    it.key.equals(KdbxConstants.Fields.OTP, ignoreCase = true) ||
                        it.key.startsWith(VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX, ignoreCase = true)
                }?.value
            )
            ?: CharArray(0)
        return EntryRevisionSnapshot(
            entry = projection.copy(customFields = decryptedFields),
            totpSecretChars = totpRawChars
        )
    }

    override suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // TASK-10：编辑态 CharArray 化——readChars 返回独占副本，调用方按借用语义用毕清零
        return entry.customFields.firstOrNull { it.key == fieldKey }?.value?.readChars()
    }

    override suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // 种子仅在数据层内瞬时解析并参与计算，绝不随结果外泄
        val config = entryMapper.parseTotpConfig(entry) ?: return null
        return try {
            val code = entryMapper.computeTotpCode(config) ?: return null
            EntryTotpSnapshot(
                code = code,
                periodSeconds = config.period,
                digits = config.digits,
                algorithm = config.algorithm
            )
        } finally {
            // ISSUE-P2-12：解析配置持有的 Base32 种子字节用毕即擦（成功/失败路径一致）
            config.secret.fill(0)
        }
    }

    override suspend fun getEntryTotpSecretChars(entryId: String): CharArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        // 断点4 整改：与 parseTotpConfig 同源读取（otp 字段优先，回退 TOTP 开头的自定义字段）。
        // TASK-10：返回配置原文独占 CharArray 副本（otpauth:// URI 或 Base32 种子）供编辑页回填，
        // 调用方按借用语义用毕清零
        return entry.fields[KdbxConstants.Fields.OTP]?.readChars()
            ?: entry.customFields.firstOrNull {
                it.key.equals(KdbxConstants.Fields.OTP, ignoreCase = true) ||
                    it.key.startsWith(VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX, ignoreCase = true)
            }?.value?.readChars()
    }

    override suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray? {
        val targetUuid = parseKdbxUuidOrNull(entryId) ?: return null
        val currentDb = databaseSession.databaseFlow.first() ?: return null
        val entry = currentDb.rootGroup.allEntries().firstOrNull { it.id == targetUuid } ?: return null
        val binaryPool = currentDb.binaries.map { it.data }
        val attachment = entry.attachments.firstOrNull { it.name == fileName } ?: return null
        val bytes = attachment.resolveData(binaryPool)
        return if (bytes.isEmpty()) null else bytes.copyOf()
    }

    override fun isSessionReadOnly(): Boolean = databaseSession.isReadOnly

    override suspend fun saveNewPasskeyEntry(data: PasskeyData, boundPackage: String?): KdbxEntry =
        passkeyEntries.saveNewPasskeyEntry(data, boundPackage)

    override suspend fun patchPasskeySignCount(entryId: String, newCount: Int) =
        passkeyEntries.patchPasskeySignCount(entryId, newCount)

    override suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    ): com.keepasskey.core.result.KdbxResult<Unit> {
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
                // 双通道防重（2026-09 共存审查）：Autofill SaveInfo 与 Credential Manager
                // 保存双通道均收敛于本方法。当目标条目与新凭据内容完全一致（同用户名、同密码，
                // 经 ProtectedString HMAC 等值标签常时比较，不解密、不物化明文）时，保存为
                // 幂等操作：直接返回成功并跳过落库——防止用户在两个保存弹窗各确认一次导致
                // history 修订翻倍与同步脏标记污染。
                val passwordUnchanged = matchedEntry.password == pwdProtected
                val usernameUnchanged = matchedEntry.userName.isNotEmpty() || username.isBlank()
                if (passwordUnchanged && usernameUnchanged) {
                    return com.keepasskey.core.result.KdbxResult.Success(Unit)
                }
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
            return persistSession()
        } finally {
            java.util.Arrays.fill(passwordChars, '0')
        }
    }

    // ================= TASK-13 整改：设置页导出/模板真实化 =================

    override suspend fun exportKdbxBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        databaseSession.exportToBytes()

    override suspend fun exportVaultXmlBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        withContext(Dispatchers.Default) {
            val db = databaseSession.databaseFlow.first()
                ?: return@withContext com.keepasskey.core.result.KdbxResult.Failure(
                    IllegalStateException("活动数据库为空"),
                    strings.get(R.string.repo_no_active_db_for_export)
                )
            try {
                com.keepasskey.core.result.KdbxResult.Success(
                    com.keepasskey.database.xml.KeePassXmlExporter.export(db)
                )
            } catch (t: Throwable) {
                com.keepasskey.core.result.KdbxResult.Failure(
                    t, strings.get(R.string.repo_export_xml_failed, t.message ?: "")
                )
            }
        }

    override suspend fun exportKeyFileBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> {
        val bytes = databaseSession.exportKeyFileBytes()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(
                IllegalStateException("会话未绑定密钥文件"),
                strings.get(R.string.repo_no_keyfile_to_export)
            )
        return com.keepasskey.core.result.KdbxResult.Success(bytes)
    }

    override suspend fun installEntryTemplates(): com.keepasskey.core.result.KdbxResult<Unit> {
        // 幂等保护：已存在同名模板分组时不再重复安装
        val currentDb = databaseSession.databaseFlow.first()
            ?: return com.keepasskey.core.result.KdbxResult.Failure(
                IllegalStateException("活动数据库为空"),
                strings.get(R.string.repo_no_active_db_for_export)
            )
        if (currentDb.rootGroup.subgroups.any { it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME }) {
            return com.keepasskey.core.result.KdbxResult.Failure(
                IllegalStateException("模板分组已存在"),
                context.getString(
                    R.string.repo_templates_already_installed,
                    VaultTemplateFactory.TEMPLATE_GROUP_NAME
                )
            )
        }
        // saveGroup 仅更新内存树（置 DIRTY），由 persistSession 统一序列化落盘并上传播结果
        databaseSession.saveGroup(VaultTemplateFactory.buildTemplateGroup())
        return persistSession()
    }

    companion object {
        private const val TAG = "RealVaultRepository"

        /** TASK-34：条目收藏标记的 customData 键（随 KDBX 条目持久化与同步） */
        const val FAVORITE_CUSTOM_DATA_KEY = "KeePasskey.Favorite"

        /**
         * 本地占位默认库展示名（潜在建库文件名，写入磁盘路径属持久化数据，
         * 故保持中文常量不本地化，见 refreshDatabases 注释）
         */
        const val DEFAULT_VAULT_DISPLAY_NAME = "默认密码库.kdbx"

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
