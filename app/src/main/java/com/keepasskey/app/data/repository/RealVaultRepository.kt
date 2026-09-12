package com.keepasskey.app.data.repository

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxResult
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于真实 KDBX 数据库引擎与 DatabaseSession 的生产级数据仓库。
 * 遵循推荐架构规范：屏蔽底层加解密与 XML 序列化细节，向上层 UI 提供反应式状态流与 CRUD 契约。
 *
 * ISSUE-P3-31 批次 B：本类收敛为**编排层**——具体职责按内聚边界下沉到同包协调器
 * （[VaultDatabaseCatalog] 已知库注册 / [VaultLifecycleCoordinator] 库生命周期 /
 * [VaultEntryWriteCoordinator] 条目写入 / [VaultEntrySecretReader] 敏感值读取 /
 * [VaultGroupCoordinator] 分组投影与保存 / [VaultExportCoordinator] 导出与模板 /
 * [RecycleBinCoordinator] 回收站 / [EntryDuplicateCoordinator] 克隆 /
 * [CustomIconCoordinator] 自定义图标 / [PasskeyEntryCoordinator] Passkey 条目），
 * 本类仅保留状态流持有、会话生命周期与委托分发；拆分是**纯结构性**的，不含行为变更。
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

    private val databasesFlow = MutableStateFlow<List<VaultDatabaseInfo>>(emptyList())

    // TASK-21 拆分：投影映射器与领域协调器（回收站/Passkey/模板），仓库仅保留 CRUD 编排
    private val entryMapper = VaultEntryMapper(strings)
    private val recycleBin = RecycleBinCoordinator(strings, databaseSession) { persistSession() }
    private val entryDuplicator = EntryDuplicateCoordinator(strings, databaseSession) { persistSession() }
    private val customIcons = CustomIconCoordinator(strings, databaseSession) { persistSession() }
    private val passkeyEntries = PasskeyEntryCoordinator(databaseSession, debugLog) { persistSession() }
    // ISSUE-P3-31 批次 B：下列协调器自本类拆出，落盘统一回灌 [persistSession]
    private val catalog = VaultDatabaseCatalog(context, strings, databaseSession)
    private val lifecycle = VaultLifecycleCoordinator(
        context = context,
        strings = strings,
        databaseSession = databaseSession,
        catalog = catalog,
        refresh = ::refreshDatabases,
        selectDatabase = ::selectDatabase
    )
    private val entryWriter = VaultEntryWriteCoordinator(strings, databaseSession, entryMapper) { persistSession() }
    private val secretReader = VaultEntrySecretReader(databaseSession, entryMapper)
    private val groups = VaultGroupCoordinator(databaseSession, entryMapper) { persistSession() }
    private val exporter = VaultExportCoordinator(context, strings, databaseSession) { persistSession() }

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

    private suspend fun refreshDatabases() {
        databasesFlow.value = catalog.buildDatabaseList()
    }

    override fun getDatabases(): Flow<List<VaultDatabaseInfo>> = databasesFlow.asStateFlow()

    override suspend fun selectDatabase(id: String) {
        val current = databasesFlow.value
        val target = current.firstOrNull { it.id == id || it.path == id || it.name == id }
        if (target != null) {
            catalog.saveActiveDbId(target.id)
            databasesFlow.value = current.map { db ->
                db.copy(isActive = db.id == target.id)
            }
        }
    }

    override suspend fun unlockActiveDatabase(
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        readOnly: Boolean
    ): KdbxResult<Unit> = lifecycle.unlockActiveDatabase(
        candidates = databasesFlow.value,
        passwordChars = passwordChars,
        keyFileData = keyFileData,
        readOnly = readOnly
    )

    override suspend fun changeMasterPassword(newPassword: CharArray): KdbxResult<Unit> {
        val result = databaseSession.changeCredentials(newPassword)
        if (result is KdbxResult.Success) {
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
    ): KdbxResult<Unit> = createDatabaseWithKeyFile(
        name = name,
        masterPassword = masterPassword,
        // ISSUE-P3-21：历史 Boolean 入口如实映射——true 即「生成并绑定附属密钥文件」，
        // 不再是被静默忽略的假开关
        keyFileFactor = if (keyFile) CreateKeyFileFactor.Generate else CreateKeyFileFactor.None,
        preset = preset
    )

    override suspend fun createDatabaseWithKeyFile(
        name: String,
        masterPassword: CharArray,
        keyFileFactor: CreateKeyFileFactor,
        preset: String
    ): KdbxResult<Unit> = lifecycle.createDatabaseWithKeyFile(
        name = name,
        masterPassword = masterPassword,
        keyFileFactor = keyFileFactor,
        preset = preset
    )

    override suspend fun removeDatabase(id: String): KdbxResult<Unit> = lifecycle.removeDatabase(id)

    override suspend fun importExternalDatabase(
        name: String,
        path: String,
        syncType: String
    ): KdbxResult<Unit> = lifecycle.importExternalDatabase(
        name = name,
        path = path,
        syncType = syncType
    )

    override fun getGroups(): Flow<List<VaultGroup>> = groups.groupsFlow()

    override suspend fun saveGroup(group: VaultGroup): KdbxResult<Unit> = groups.saveGroup(group)

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
    ): KdbxResult<Unit> {
        // 擦除契约（加解密审查 2026-09）：任何结果路径（成功/失败/异常）用毕清零传入副本，
        // 与 saveAutofillCredential / FakeVaultRepository 同一契约。
        // TASK-10：TOTP 种子与受保护自定义字段明文副本同样纳入擦除契约
        try {
            return entryWriter.saveEntryInternal(entry, passwordChars, totpSecretChars, protectedFieldChars)
        } finally {
            passwordChars?.fill('0')
            totpSecretChars?.fill('0')
            protectedFieldChars.values.forEach { it.fill('0') }
        }
    }

    override suspend fun setEntryFavorite(entryId: String, favorite: Boolean): KdbxResult<Unit> =
        entryWriter.setEntryFavorite(entryId, favorite)

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

    override suspend fun getKdbxEntries(): List<KdbxEntry> {
        val db = databaseSession.databaseFlow.first() ?: return emptyList()
        return db.rootGroup.allEntries()
    }

    override suspend fun findEntriesForRpId(rpId: String): List<KdbxEntry> =
        passkeyEntries.findEntriesForRpId(rpId)

    override suspend fun findPasskeyByCredentialId(credentialId: String): KdbxEntry? =
        passkeyEntries.findPasskeyByCredentialId(credentialId)

    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryPasswordChars(entryId)")
    )
    override suspend fun getEntryPassword(entryId: String): String? =
        secretReader.getEntryPassword(entryId)

    override suspend fun getEntryPasswordChars(entryId: String): CharArray? =
        secretReader.getEntryPasswordChars(entryId)

    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryRevisionPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryRevisionPasswordChars(entryId, revisionId)")
    )
    override suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String? =
        secretReader.getEntryRevisionPassword(entryId, revisionId)

    override suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray? =
        secretReader.getEntryRevisionPasswordChars(entryId, revisionId)

    override suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot? =
        secretReader.getEntryRevisionSnapshot(entryId, revisionId)

    override suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? =
        secretReader.getEntryProtectedFieldChars(entryId, fieldKey)

    override suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot? =
        secretReader.calculateEntryTotp(entryId)

    /**
     * ISSUE-P3-49：推进 HOTP 计数器并回传本次所出之码。
     *
     * 顺序严格为「先算码 → 计数器 +1 落库成功 → 返回该码」：任何一步失败都 fail-closed，
     * 绝不返回一个未推进的码（否则同一计数器会被重复使用）。计数器推进经
     * [VaultEntryWriteCoordinator.updateEntryOtpConfig]，**不产生历史修订**。
     */
    override suspend fun advanceEntryHotpCounter(entryId: String): KdbxResult<EntryTotpSnapshot> {
        val snapshot = secretReader.calculateEntryTotp(entryId)
            ?: return KdbxResult.Failure(
                IllegalStateException("entry missing or no OTP configured"),
                strings.get(R.string.repo_hotp_not_applicable)
            )
        if (!snapshot.isHotp) {
            return KdbxResult.Failure(
                IllegalStateException("not an HOTP entry"),
                strings.get(R.string.repo_hotp_not_applicable)
            )
        }
        val raw = secretReader.getEntryTotpSecretChars(entryId)
            ?: return KdbxResult.Failure(
                IllegalStateException("HOTP config unreadable"),
                strings.get(R.string.repo_hotp_not_applicable)
            )
        val next = try {
            com.keepasskey.core.otp.HotpCounterSupport.incrementCounter(raw)
        } finally {
            raw.fill('0')
        } ?: return KdbxResult.Failure(
            IllegalStateException("invalid HOTP counter"),
            strings.get(R.string.repo_hotp_not_applicable)
        )
        val writeResult = try {
            entryWriter.updateEntryOtpConfig(entryId, next)
        } finally {
            next.fill('0')
        }
        return when (writeResult) {
            is KdbxResult.Success -> KdbxResult.Success(snapshot)
            is KdbxResult.Failure -> KdbxResult.Failure(writeResult.error, writeResult.userMessage)
        }
    }

    override suspend fun getEntryTotpSecretChars(entryId: String): CharArray? =
        secretReader.getEntryTotpSecretChars(entryId)

    override suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray? =
        secretReader.getAttachmentData(entryId, fileName)

    override fun isSessionReadOnly(): Boolean = databaseSession.isReadOnly

    override suspend fun saveNewPasskeyEntry(data: PasskeyData, boundPackage: String?): KdbxEntry =
        passkeyEntries.saveNewPasskeyEntry(data, boundPackage)

    override suspend fun patchPasskeySignCount(entryId: String, newCount: Int) =
        passkeyEntries.patchPasskeySignCount(entryId, newCount)

    /**
     * ISSUE-P3-27 子项 2：原子递增并回传**实际落库**的签名计数器。
     * 委托 [PasskeyEntryCoordinator.incrementPasskeySignCount]（递增与落库同处一个受控变换），
     * 使断言路径能取得唯一值而不必用锁外快照自算。
     */
    override suspend fun incrementPasskeySignCount(entryId: String): Int? =
        passkeyEntries.incrementPasskeySignCount(entryId)

    override suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    ): KdbxResult<Unit> = entryWriter.saveAutofillCredential(
        packageName = packageName,
        webDomain = webDomain,
        username = username,
        passwordChars = passwordChars
    )

    // ================= TASK-13 整改：设置页导出/模板真实化 =================

    override suspend fun exportKdbxBytes(): KdbxResult<ByteArray> = exporter.exportKdbxBytes()

    override suspend fun exportVaultXmlBytes(): KdbxResult<ByteArray> = exporter.exportVaultXmlBytes()

    override suspend fun exportVaultCsvBytes(): KdbxResult<ByteArray> = exporter.exportVaultCsvBytes()

    override suspend fun exportKeyFileBytes(): KdbxResult<ByteArray> = exporter.exportKeyFileBytes()

    override suspend fun installEntryTemplates(): KdbxResult<Unit> = exporter.installEntryTemplates()

    /**
     * H3 整改：会话落盘的唯一出口——save() 失败必须原样向上传播，
     * 禁止磁盘写失败被静默吞掉导致 UI 谎报保存成功、锁库后修改永久丢失。
     */
    private suspend fun persistSession(): KdbxResult<Unit> {
        val result = databaseSession.save()
        if (result is KdbxResult.Failure) {
            debugLog.error(TAG, "数据库保存失败: ${result.message}")
        }
        return result
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
