package com.keepasskey.app.data.repository

import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.model.VaultRemovalKind
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxKdfStrengthAssessment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.Arrays

/**
 * 阶段 1 内存实现，提供群组文件夹、凭据、多数据库管理与回收站的假数据和响应式状态更新。
 * 仅供 JVM 单元测试使用，禁止迁回生产 main source set 或绑定生产 DI。
 */
class FakeVaultRepository(
    initialDatabases: List<VaultDatabaseInfo> = initialMockDatabases,
    // ISSUE-P1-04：置 true 时 unlockActiveDatabase 恒返回「凭据错误」失败，
    // 供节流/清零路径单测驱动认证失败分支（默认 false，不影响既有用例）
    private val forceInvalidCredentials: Boolean = false
) : VaultRepository {

    private val databasesFlow = MutableStateFlow(initialDatabases)
    private val groupsFlow = MutableStateFlow(initialMockGroups)
    private val entriesFlow = MutableStateFlow(initialMockEntries)

    // M1 整改：密码明文不再进入 UiVaultEntry/StateFlow，改为按条目 id 独立存储的按需解密仓
    private val passwordStore = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 最近一次 `saveEntry` 收到的 TOTP 配置原文（条目 id → 原文；仅测试观测点）。
     * 供「扫码落库存原始 otpauth URI」类断言使用——Fake 不走真实 KDBX 映射，
     * 不留观测点则无法区分「存了原文」与「根本没传」。
     */
    var lastSavedTotpByEntry: Map<String, String> = emptyMap()
        private set

    /**
     * 最近一次 passkey 写库实际收到的自定义字段（条目 id → 字段表；仅测试观测点，
     * `ISSUE-P3-337` AC④ 加）。
     *
     * 为什么必须存 [KdbxCustomField] 而不是 `Map<String, String>`：AC④② 要逐键核验
     * **保护位**（明文四键 / 受保护四键的划分），String 形态会把 `isProtected` 丢掉，
     * 于是「逐键受保护」的断言会因观测点丢位而空转通过（真假绿路径）。
     */
    var lastSavedPasskeyByEntry: Map<String, List<KdbxCustomField>> = emptyMap()
        private set

    protected fun recordPasskeyFields(entryId: String, fields: List<KdbxCustomField>) {
        lastSavedPasskeyByEntry = lastSavedPasskeyByEntry + (entryId to fields)
    }

    /**
     * ISSUE-P3-04：最近一次解锁调用实际收到的密钥文件字节（克隆副本，null 表示未携带）。
     * 供「有 KeyFile / 无 KeyFile 走不同复合密钥通道」的透传断言使用。
     */
    var lastUnlockKeyFileData: ByteArray? = null
        private set

    override fun getDatabases(): Flow<List<VaultDatabaseInfo>> = databasesFlow.asStateFlow()

    override suspend fun selectDatabase(id: String) {
        val current = databasesFlow.value.map { db ->
            db.copy(isActive = (db.id == id))
        }
        databasesFlow.value = current
    }

    override suspend fun unlockActiveDatabase(
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        readOnly: Boolean
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        // ISSUE-P3-04：记录实际收到的密钥文件因子（克隆语义，断言用）
        lastUnlockKeyFileData = keyFileData?.copyOf()
        // ISSUE-P1-04：强制凭据错误——驱动 ViewModel 认证失败分支（节流计数 + 无条件清零）
        if (forceInvalidCredentials) {
            return com.keepasskey.core.result.KdbxResult.Failure(
                com.keepasskey.database.exception.KdbxInvalidCredentialsException("主密码错误"),
                "主密码错误"
            )
        }
        // P1-10 语义：仅密钥文件（空密码 + 密钥文件）亦为合法复合密钥
        return if (passwordChars.isNotEmpty() || keyFileData != null) {
            com.keepasskey.core.result.KdbxResult.Success(Unit)
        } else {
            com.keepasskey.core.result.KdbxResult.Failure(IllegalArgumentException("密码为空"), "密码不能为空")
        }
    }

    override suspend fun changeMasterPassword(
        newPassword: CharArray,
        keyFileIntent: ChangeKeyFileIntent
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun changeKeyFileOnly(keyFileIntent: ChangeKeyFileIntent): com.keepasskey.core.result.KdbxResult<Unit> {
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun lockDatabase() {
        // 假数据仓库内存模拟无操作
    }

    override fun isLocked(): Boolean = false

    override suspend fun createDatabase(
        name: String,
        masterPassword: CharArray,
        keyFile: Boolean,
        preset: CreateVaultPreset
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val fileName = if (name.endsWith(".kdbx")) name else "$name.kdbx"
        val newDb = VaultDatabaseInfo(
            id = "db_${System.currentTimeMillis()}",
            name = fileName,
            path = "/storage/emulated/0/Documents/$fileName",
            isRemote = false,
            syncType = "本地存储",
            lastOpenedAt = "刚刚",
            fileSizeFormatted = "32 KB",
            isActive = true,
            encryptionPreset = preset.label
        )
        val current = databasesFlow.value.map { it.copy(isActive = false) }.toMutableList()
        current.add(0, newDb)
        databasesFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    /**
     * `ISSUE-P1-241`：最近一次移除动作的**真实对象**。
     *
     * 仅作用例观测点，不参与任何行为——用于锁定「界面文案所用判据 == 下行给数据层的删除开关」
     * 这一条不变式（两者若各判一次就正是本项要根治的形态）。
     */
    var lastRemovalKind: VaultRemovalKind? = null
        private set

    override suspend fun removeDatabase(id: String, kind: VaultRemovalKind): com.keepasskey.core.result.KdbxResult<Unit> {
        lastRemovalKind = kind
        databasesFlow.value = databasesFlow.value.filter { it.id != id }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    /**
     * ISSUE-P2-399：最近一次 `importExternalDatabase` 实际收到的 (name, path, syncType)
     * 三元组（仅测试观测点）。供「云端导入成功后按本地路径登记且保持云端标签」类断言使用。
     */
    var lastImport: Triple<String, String, String>? = null
        private set

    override suspend fun importExternalDatabase(name: String, path: String, syncType: String): com.keepasskey.core.result.KdbxResult<Unit> {
        lastImport = Triple(name, path, syncType)
        val newDb = VaultDatabaseInfo(
            id = "db_${System.currentTimeMillis()}",
            name = name,
            path = path,
            isRemote = syncType != "本地设备存储",
            syncType = syncType,
            lastOpenedAt = "刚刚",
            fileSizeFormatted = "186 KB",
            isActive = true,
            encryptionPreset = "ChaCha20 + Argon2id"
        )
        val current = databasesFlow.value.map { it.copy(isActive = false) }.toMutableList()
        current.add(0, newDb)
        databasesFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    /**
     * ISSUE-P2-87：可注入的工作因子评估结果。
     *
     * 默认 null = **未评估**（与生产实现「来源不可读 / 头部不可解析」同一语义），
     * 故既有用例不受影响；需要驱动「低于本应用建库默认强度」提示的用例显式赋值。
     */
    var kdfStrengthAssessment: KdbxKdfStrengthAssessment? = null

    override suspend fun assessKdfStrength(path: String): KdbxKdfStrengthAssessment? =
        kdfStrengthAssessment

    override fun getGroups(): Flow<List<VaultGroup>> = groupsFlow.asStateFlow()

    override suspend fun saveGroup(group: VaultGroup): com.keepasskey.core.result.KdbxResult<Unit> {
        val current = groupsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            current[index] = group
        } else {
            current.add(0, group)
        }
        groupsFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun deleteGroup(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        // 删除文件夹及其下属条目或移入回收站
        groupsFlow.value = groupsFlow.value.filter { it.id != id }
        entriesFlow.value = entriesFlow.value.map { entry ->
            if (entry.groupId == id) entry.copy(groupId = "group_recycle_bin") else entry
        }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override fun getEntries(): Flow<List<UiVaultEntry>> = entriesFlow.map { list ->
        // F2 整改：与真实仓库投影语义一致——受保护自定义字段明文不进投影
        list.map { entry ->
            entry.copy(
                // ISSUE-P2-341：与生产同口径——本项目的"已删除"是**组归属**而非条目位，
                // 故按所在组是否为回收站推导 isRecycled（移入 / 还原四处写点无需逐个改，自动一致）。
                isRecycled = entry.groupId == RECYCLE_BIN_GROUP_ID,
                customFields = entry.customFields.map { cf ->
                    if (cf.isProtected) cf.copy(value = "") else cf
                }
            )
        }
    }

    override fun getEntry(id: String): Flow<UiVaultEntry?> {
        // ISSUE-P3-359 AC④：与生产 `entryFlow` 同口径（ISSUE-P2-341）——单条投影同样按组归属
        // 推导 isRecycled；此前只有 getEntries 派生，详情侧「软删后复查是否入桶」会误判为硬删
        return entriesFlow.map { list ->
            val found = list.find { it.id == id }
            found?.copy(
                isRecycled = found.groupId == RECYCLE_BIN_GROUP_ID
            )
        }
    }

    override suspend fun saveEntry(
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        passwordChars?.let { pwd ->
            passwordStore.value = passwordStore.value + (entry.id to String(pwd))
        }
        // TOTP 配置原文观测点（在擦除契约清零之前取样；测试专用明文，与 passwordStore 同口径）
        totpSecretChars?.let { totp ->
            lastSavedTotpByEntry = lastSavedTotpByEntry + (entry.id to String(totp))
        }
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            // 自动保留一份历史修订版本
            val old = current[index]
            val rev = UiEntryRevision(
                id = "rev_${System.currentTimeMillis()}",
                modifiedAt = "2026-09-04 10:30",
                summary = "修订密码与凭据内容",
                username = old.username,
                notes = old.notes
            )
            val updatedRevisions = listOf(rev) + old.revisions
            // F2 整改：掩码投影中受保护字段为空值，视为未修改并回填既有值；
            // TASK-10：用户显式编辑的受保护字段（protectedFieldChars）以提交的明文覆盖
            val mergedFields = entry.customFields.map { cf ->
                val submitted = protectedFieldChars[cf.id]
                when {
                    submitted != null -> cf.copy(value = String(submitted))
                    cf.isProtected && cf.value.isEmpty() -> {
                        val existing = old.customFields.firstOrNull { it.key == cf.key }
                        if (existing != null) existing else cf
                    }
                    else -> cf
                }
            }
            current[index] = entry.copy(customFields = mergedFields, revisions = updatedRevisions)
        } else {
            current.add(0, entry)
        }
        entriesFlow.value = current
        // 擦除契约：与 RealVaultRepository 同一契约——任何结果路径用毕清零传入副本
        passwordChars?.fill('0')
        totpSecretChars?.fill('0')
        protectedFieldChars.values.forEach { it.fill('0') }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun setEntryFavorite(
        entryId: String,
        favorite: Boolean
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        // TASK-34：与 RealVaultRepository 同语义——收藏状态落进条目投影
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == entryId }
        if (index >= 0) {
            current[index] = current[index].copy(isFavorite = favorite)
            entriesFlow.value = current
        }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    // ISSUE-P3-442 Fake 语义：字段级写 URL（与 RealVaultRepository 同语义——只动 url 一个字段，
    // 其余投影字段（含 overrideUrl / 自定义字段）原样保留）
    override suspend fun updateEntryUrl(
        entryId: String,
        url: String
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == entryId }
        if (index < 0) {
            return com.keepasskey.core.result.KdbxResult.Failure(
                IllegalArgumentException("条目不存在"), "条目不存在"
            )
        }
        current[index] = current[index].copy(url = url)
        entriesFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    // TASK-16 Fake 语义：克隆 = 同字段复制 + 新 id + 清历史修订
    override suspend fun duplicateEntry(id: String): com.keepasskey.core.result.KdbxResult<String> {
        val source = entriesFlow.value.firstOrNull { it.id == id }
            ?: return com.keepasskey.core.result.KdbxResult.Failure(
                IllegalArgumentException("条目不存在"), "条目不存在"
            )
        val cloneId = "dup_${System.currentTimeMillis()}"
        entriesFlow.value = entriesFlow.value + source.copy(id = cloneId, revisions = emptyList())
        return com.keepasskey.core.result.KdbxResult.Success(cloneId)
    }

    // TASK-15 Fake 语义：内存图标池 + 引用上传
    private val customIconPool = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    override suspend fun addCustomIcon(pngBytes: ByteArray): com.keepasskey.core.result.KdbxResult<String> {
        val existing = customIconPool.value.entries.firstOrNull { it.value.contentEquals(pngBytes) }
        val id = existing?.key ?: "icon_${System.currentTimeMillis()}"
        if (existing == null) {
            customIconPool.value = customIconPool.value + (id to pngBytes.copyOf())
        }
        return com.keepasskey.core.result.KdbxResult.Success(id)
    }

    override suspend fun getCustomIconBytes(): Map<String, ByteArray> = customIconPool.value

    // TASK-17 Fake 语义：不模拟字段引用，原样返回
    override suspend fun resolveFieldReferences(
        entryId: String,
        rawText: String,
        consumerField: com.keepasskey.database.fieldref.FieldReferenceEngine.RefField
    ): String? = rawText

    override suspend fun deleteEntry(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val entry = current[index]
            if (entry.groupId == "group_recycle_bin") {
                // 已在回收站中，则彻底物理删除
                current.removeAt(index)
            } else {
                // 移入回收站
                current[index] = entry.copy(groupId = "group_recycle_bin")
            }
            entriesFlow.value = current
        }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun restoreEntry(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(groupId = null)
            entriesFlow.value = current
        }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun emptyRecycleBin(): com.keepasskey.core.result.KdbxResult<Unit> {
        entriesFlow.value = entriesFlow.value.filter { it.groupId != "group_recycle_bin" }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?): com.keepasskey.core.result.KdbxResult<Unit> {
        val current = entriesFlow.value.map { entry ->
            if (entry.id in entryIds) entry.copy(groupId = targetGroupId) else entry
        }
        entriesFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun batchDeleteEntries(entryIds: Set<String>): com.keepasskey.core.result.KdbxResult<Unit> {
        val current = entriesFlow.value.map { entry ->
            if (entry.id in entryIds) entry.copy(groupId = "group_recycle_bin") else entry
        }
        entriesFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    private val extraKdbxEntries = MutableStateFlow<List<KdbxEntry>>(emptyList())

    override suspend fun getKdbxEntries(): List<KdbxEntry> {
        val converted = entriesFlow.value.map { ui ->
            val fields = mutableMapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(ui.title, isProtected = false),
                KdbxConstants.Fields.USER_NAME to ProtectedString(ui.username, isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(passwordStore.value[ui.id] ?: "", isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString(ui.url, isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString(ui.notes, isProtected = false)
            )
            val customFields = ui.customFields.map {
                KdbxCustomField(it.key, ProtectedString(it.value, isProtected = it.isProtected))
            }.toMutableList()

            if (ui.isPasskey && ui.passkeyRpId != null) {
                if (customFields.none { it.key == PasskeyData.FIELD_RP_ID }) {
                    customFields.add(KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString(ui.passkeyRpId, isProtected = false)))
                }
                if (customFields.none { it.key == PasskeyData.FIELD_CREDENTIAL_ID }) {
                    customFields.add(KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("fake_cred_${ui.id}", isProtected = false)))
                }
                if (customFields.none { it.key == PasskeyData.FIELD_PRIVATE_KEY }) {
                    customFields.add(KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("fake_priv_key", isProtected = true)))
                }
            }

            KdbxEntry(
                id = try { KdbxUuid.fromHexString(ui.id) } catch (_: Exception) { KdbxUuid.random() },
                fields = fields,
                customFields = customFields
            )
        }
        return converted + extraKdbxEntries.value
    }

    /** ISSUE-P3-148：单条查询（Fake 无树结构，按同 id 语义在既有快照上取首条） */
    override suspend fun getKdbxEntry(entryId: String): KdbxEntry? =
        getKdbxEntries().firstOrNull { it.id.toHexString() == entryId }

    /**
     * ISSUE-P2-341：可用条目＝排除回收站子树。Fake 的 `KdbxEntry` 转换不带组归属，
     * 故按 UI 侧 `isRecycled`（由 `groupId` 推导）过滤同一批 id，语义与生产一致。
     */
    override suspend fun getUsableKdbxEntries(): List<KdbxEntry> {
        val recycledIds = entriesFlow.value.filter { it.isRecycled }.map { it.id }.toSet()
        return getKdbxEntries().filterNot { it.id.toHexString() in recycledIds }
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

    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryPasswordChars(entryId)")
    )
    override suspend fun getEntryPassword(entryId: String): String? = passwordStore.value[entryId]

    override suspend fun getEntryPasswordChars(entryId: String): CharArray? =
        passwordStore.value[entryId]?.toCharArray()

    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryRevisionPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryRevisionPasswordChars(entryId, revisionId)")
    )
    override suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String? =
        passwordStore.value[entryId]

    override suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray? =
        passwordStore.value[entryId]?.toCharArray()

    override suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? =
        entriesFlow.value.firstOrNull { it.id == entryId }
            ?.customFields?.firstOrNull { it.key == fieldKey }?.value?.toCharArray()

    override suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot? {
        val entry = entriesFlow.value.firstOrNull { it.id == entryId } ?: return null
        val code = entry.totpCode?.replace(" ", "") ?: return null
        return EntryTotpSnapshot(
            code = code,
            periodSeconds = entry.totpPeriod,
            digits = entry.totpDigits,
            algorithm = entry.totpAlgorithm,
            isHotp = entry.isHotp
        )
    }

    /**
     * ISSUE-P2-90：批量通道的测试替身实现——语义与逐条调用一致（条目不存在 / 无码即缺席）。
     * 替身不模拟生产侧的缓存与索引（那是真实实现的内部优化），故此处逐条委托即可。
     */
    override suspend fun calculateEntryTotps(entryIds: List<String>): Map<String, EntryTotpSnapshot> =
        entryIds.mapNotNull { id -> calculateEntryTotp(id)?.let { id to it } }.toMap()

    /**
     * ISSUE-P3-49：测试替身**不支持** HOTP 计数器写回——明确 fail-closed，
     * 绝不谎报「已出码」（与生产实现同一诚实语义）。
     */
    override suspend fun advanceEntryHotpCounter(entryId: String): KdbxResult<EntryTotpSnapshot> =
        KdbxResult.Failure(UnsupportedOperationException("fake repository does not support HOTP advance"))

    override suspend fun saveNewPasskeyEntry(
        data: PasskeyData,
        boundPackage: String?,
        parentGroupId: KdbxUuid?
    ): KdbxEntry {
        val title = "${data.userName}@${data.relyingPartyId}"
        val url = if (boundPackage.isNullOrBlank()) "https://${data.relyingPartyId}" else "android://$boundPackage"
        val fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(data.userName, isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)
        )
        val newEntry = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = parentGroupId,
            fields = fields,
            customFields = data.toCustomFields()
        )
        lastSavedPasskeyByEntry = lastSavedPasskeyByEntry +
            (newEntry.id.toHexString() to newEntry.customFields)
        extraKdbxEntries.value = extraKdbxEntries.value + newEntry
        return newEntry
    }

    /**
     * 测试替身：按 **entryId** 整体替换既有条目的 Passkey schema 字段
     * （与生产 `PasskeyEntryCoordinator.replacePasskeyOnEntry` 同语义：非 passkey 字段全保留、
     * 全部 schema 键换新、未命中不写入并返回 null）。
     *
     * 两支存储都要认：编辑页用例里的条目是**早就在库里**的普通条目（经 [saveEntry] 落在
     * [entriesFlow] 的 UI 投影侧），而顶栏新建落在 [extraKdbxEntries]。生产实现只在 KDBX 树上
     * 按 id 定位，两侧走的是同一替换语义，故替身不得只认一支——否则 Q1 的「挂当前条目」
     * 在单测里永远走 null 分支，替换语义就成了没测过的空话。
     */
    override suspend fun replacePasskeyOnEntry(entryId: String, data: PasskeyData): KdbxEntry? {
        val current = extraKdbxEntries.value
        val index = current.indexOfFirst { it.id.toHexString() == entryId }
        if (index >= 0) {
            val preserved = current[index].customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
            val updated = current[index].copy(customFields = preserved + data.toCustomFields())
            lastSavedPasskeyByEntry = lastSavedPasskeyByEntry + (entryId to updated.customFields)
            extraKdbxEntries.value = current.toMutableList().also { it[index] = updated }
            return updated
        }
        val uiEntries = entriesFlow.value
        val uiIndex = uiEntries.indexOfFirst { it.id == entryId }
        if (uiIndex < 0) return null
        val ui = uiEntries[uiIndex]
        val preservedUi = ui.customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
        val incomingFields = data.toCustomFields()
        val incoming = incomingFields.map { cf ->
            UiCustomField(
                id = "${entryId}_${cf.key}",
                key = cf.key,
                value = cf.value.readString(),
                isProtected = cf.isProtected
            )
        }
        val updatedUi = ui.copy(customFields = preservedUi + incoming, isPasskey = true)
        lastSavedPasskeyByEntry = lastSavedPasskeyByEntry + (entryId to incomingFields)
        entriesFlow.value = uiEntries.toMutableList().also { it[uiIndex] = updatedUi }
        return KdbxEntry(
            id = try {
                KdbxUuid.fromHexString(entryId)
            } catch (_: Exception) {
                KdbxUuid.random()
            },
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(updatedUi.title, isProtected = false)
            ),
            customFields = preservedUi.map {
                KdbxCustomField(it.key, ProtectedString(it.value, isProtected = it.isProtected))
            } + incomingFields
        )
    }

    /**
     * 测试替身：**解除绑定**——摘掉既有条目的全部 Passkey schema 字段（`ISSUE-P3-342`，
     * 与生产 `PasskeyEntryCoordinator.clearPasskeyOnEntry` 同语义：非凭据字段全保留、
     * 未命中返回 null 且无写入、本就没有凭据字段时幂等）。
     *
     * 与 [replacePasskeyOnEntry] 同样**两支存储都要认**（`extraKdbxEntries` 与 `entriesFlow`），
     * 否则编辑页用例里的解除永远走 null 分支。
     */
    override suspend fun clearPasskeyOnEntry(entryId: String): KdbxEntry? {
        val current = extraKdbxEntries.value
        val index = current.indexOfFirst { it.id.toHexString() == entryId }
        if (index >= 0) {
            val target = current[index]
            val preserved = target.customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
            if (preserved.size == target.customFields.size) {
                return target
            }
            val updated = target.copy(customFields = preserved)
            lastSavedPasskeyByEntry = lastSavedPasskeyByEntry - entryId
            extraKdbxEntries.value = current.toMutableList().also { it[index] = updated }
            return updated
        }
        val uiEntries = entriesFlow.value
        val uiIndex = uiEntries.indexOfFirst { it.id == entryId }
        if (uiIndex < 0) return null
        val ui = uiEntries[uiIndex]
        val preservedUi = ui.customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
        if (preservedUi.size == ui.customFields.size) {
            return null
        }
        entriesFlow.value = uiEntries.toMutableList().also {
            it[uiIndex] = ui.copy(customFields = preservedUi, isPasskey = false, passkeyRpId = null)
        }
        lastSavedPasskeyByEntry = lastSavedPasskeyByEntry - entryId
        // 与 replacePasskeyOnEntry 同形：UI 侧命中也要回传一条落树后的 KdbxEntry，
        // 否则调用方按 null 判"未命中"，解除成功的分支在单测里永远走不到。
        return KdbxEntry(
            id = try {
                KdbxUuid.fromHexString(entryId)
            } catch (_: Exception) {
                KdbxUuid.random()
            },
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(ui.title, isProtected = false)
            ),
            customFields = preservedUi.map {
                KdbxCustomField(it.key, ProtectedString(it.value, isProtected = it.isProtected))
            }
        )
    }

    /**
     * 测试替身：同 rpId + 用户名命中既有条目时**原地替换**其 Passkey schema 字段，
     * 否则与 [saveNewPasskeyEntry] 同语义新建（与生产 [RealVaultRepository] 契约一致）。
     */
    override suspend fun saveOrReplacePasskeyEntry(data: PasskeyData, boundPackage: String?): KdbxEntry {
        val cleanTarget = DomainMatcher.extractDomain(data.relyingPartyId)
        val current = extraKdbxEntries.value
        val index = current.indexOfFirst { entry ->
            val passkey = PasskeyData.fromCustomFields(entry.customFields) ?: return@indexOfFirst false
            passkey.userName == data.userName && DomainMatcher.isDomainMatch(passkey.relyingPartyId, cleanTarget)
        }
        if (index < 0) return saveNewPasskeyEntry(data, boundPackage)

        val preserved = current[index].customFields.filterNot { PasskeyData.isPasskeyFieldKey(it.key) }
        val updated = current[index].copy(customFields = preserved + data.toCustomFields())
        lastSavedPasskeyByEntry = lastSavedPasskeyByEntry +
            (updated.id.toHexString() to updated.customFields)
        extraKdbxEntries.value = current.toMutableList().also { it[index] = updated }
        return updated
    }

    /** 测试替身：`excludeCredentials` 查重的单趟扫描语义（与生产实现一致） */
    override suspend fun findExistingPasskeyCredentialIds(credentialIds: Set<String>): Set<String> {
        if (credentialIds.isEmpty()) return emptySet()
        return getKdbxEntries()
            .mapNotNull { PasskeyData.fromCustomFields(it.customFields)?.credentialId }
            .filter { it in credentialIds }
            .toSet()
    }

    /**
     * ISSUE-P3-27：原子递增并回传**实际落库值**。
     *
     * 替身刻意**不写死返回值**：按 [PasskeyData.readSignCount] + [PasskeyData.nextSignCount]
     * 的真实饱和语义递增，使「断言路径必须使用回传值」这一回归锁在替身上同样成立
     * （若写死常量，回归用例会退化为空转，无法证伪）。
     * 条目不存在时返回 null 且不写入（与 [RealVaultRepository] 的契约一致）。
     */
    override suspend fun incrementPasskeySignCount(entryId: String): Int? {
        val current = extraKdbxEntries.value.toMutableList()
        val index = current.indexOfFirst { it.id.toHexString() == entryId }
        if (index < 0) return null

        val entry = current[index]
        val hasField = entry.customFields.any { it.key == PasskeyData.FIELD_SIGN_COUNT }
        val next = PasskeyData.nextSignCount(PasskeyData.readSignCount(entry.customFields))
        val written = KdbxCustomField(
            PasskeyData.FIELD_SIGN_COUNT,
            ProtectedString(next.toString(), isProtected = false)
        )
        val updated = if (hasField) {
            entry.customFields.map { cf ->
                if (cf.key == PasskeyData.FIELD_SIGN_COUNT) written else cf
            }
        } else {
            entry.customFields + written
        }
        current[index] = entry.copy(customFields = updated)
        extraKdbxEntries.value = current
        return next
    }

    override suspend fun patchPasskeySignCount(entryId: String, newCount: Int) {
        val current = extraKdbxEntries.value.toMutableList()
        val index = current.indexOfFirst { it.id.toHexString() == entryId }
        if (index >= 0) {
            val entry = current[index]
            val updated = entry.customFields.map { cf ->
                if (cf.key == PasskeyData.FIELD_SIGN_COUNT) {
                    KdbxCustomField(cf.key, ProtectedString(newCount.toString(), isProtected = false))
                } else {
                    cf
                }
            }
            current[index] = entry.copy(customFields = updated)
            extraKdbxEntries.value = current
        }
    }

    override suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    ): com.keepasskey.core.result.KdbxResult<Unit> {
        try {
            val domain = webDomain?.takeIf { it.isNotBlank() }
            val pwdString = String(passwordChars)
            val currentList = entriesFlow.value.toMutableList()
            val existingIndex = currentList.indexOfFirst {
                val matchDomain = domain != null && it.url.isNotBlank() && DomainMatcher.isDomainMatch(it.url, domain)
                // L1 整改：与 RealVaultRepository 一致，仅走严格包名边界匹配
                val matchPackage = it.url.isNotBlank() && DomainMatcher.isPackageMatch(it.url, packageName)
                (matchDomain || matchPackage) && (it.username == username || it.username.isEmpty())
            }

            if (existingIndex >= 0) {
                val old = currentList[existingIndex]
                currentList[existingIndex] = old.copy(
                    passwordMasked = "••••••••••••••••",
                    username = if (old.username.isEmpty()) username else old.username
                )
                passwordStore.value = passwordStore.value + (old.id to pwdString)
            } else {
                val titleDomain = domain ?: packageName
                val title = if (username.isNotBlank()) "$username@$titleDomain" else titleDomain
                val url = if (domain != null) "https://$domain" else "android://$packageName"
                val newEntry = UiVaultEntry(
                    id = "auto_${System.currentTimeMillis()}",
                    title = title,
                    username = username,
                    passwordMasked = "••••••••••••••••",
                    url = url,
                    category = EntryCategory.LOGIN,
                    notes = "Auto-saved from $packageName"
                )
                passwordStore.value = passwordStore.value + (newEntry.id to pwdString)
                currentList.add(newEntry)
            }
            entriesFlow.value = currentList
            return com.keepasskey.core.result.KdbxResult.Success(Unit)
        } finally {
            Arrays.fill(passwordChars, '0')
        }
    }

    companion object {
        /** ISSUE-P2-341：Fake 侧回收站组的唯一字面量（`isRecycled` 推导与可用条目过滤共用）。 */
        const val RECYCLE_BIN_GROUP_ID = "group_recycle_bin"

        val initialMockDatabases = listOf(
            VaultDatabaseInfo(
                id = "db_personal",
                name = "personal-vault.kdbx",
                path = "/storage/emulated/0/Documents/personal-vault.kdbx",
                isRemote = true,
                syncType = "WebDAV (Nextcloud)",
                lastOpenedAt = "今天 10:25",
                fileSizeFormatted = "142 KB",
                isActive = true,
                encryptionPreset = "ChaCha20 + Argon2id"
            ),
            VaultDatabaseInfo(
                id = "db_work",
                name = "company-secrets.kdbx",
                path = "/storage/emulated/0/Documents/company-secrets.kdbx",
                isRemote = true,
                syncType = "S3 (Cloudflare R2)",
                lastOpenedAt = "昨天 18:40",
                fileSizeFormatted = "328 KB",
                isActive = false,
                encryptionPreset = "AES-256 + Argon2id"
            ),
            VaultDatabaseInfo(
                id = "db_offline",
                name = "offline-backup.kdbx",
                path = "/storage/emulated/0/Download/offline-backup.kdbx",
                isRemote = false,
                syncType = "本地离线存储",
                lastOpenedAt = "2026-08-25",
                fileSizeFormatted = "88 KB",
                isActive = false,
                encryptionPreset = "Twofish + AES-KDF"
            )
        )

        val initialMockGroups = listOf(
            VaultGroup(
                id = "group_work",
                name = "工作与生产力",
                parentId = null,
                iconName = "work",
                orderIndex = 1,
                updatedAt = "2026-09-04 10:00",
                createdAt = "2026-08-01 09:00"
            ),
            VaultGroup(
                id = "group_dev",
                name = "研发与基础设施",
                parentId = "group_work",
                iconName = "code",
                orderIndex = 2,
                updatedAt = "2026-09-03 16:30",
                createdAt = "2026-08-02 11:00"
            ),
            VaultGroup(
                id = "group_finance",
                name = "金融与支付",
                parentId = null,
                iconName = "account_balance",
                orderIndex = 3,
                updatedAt = "2026-09-02 14:00",
                createdAt = "2026-08-05 10:30"
            ),
            VaultGroup(
                id = "group_social",
                name = "社交与通讯",
                parentId = null,
                iconName = "forum",
                orderIndex = 4,
                updatedAt = "2026-08-28 18:20",
                createdAt = "2026-08-08 15:00"
            ),
            VaultGroup(
                id = "group_passkeys",
                name = "通行密钥专区 (Passkey)",
                parentId = null,
                iconName = "vpn_key",
                orderIndex = 5,
                updatedAt = "2026-08-25 09:10",
                createdAt = "2026-08-10 08:30"
            ),
            VaultGroup(
                id = "group_recycle_bin",
                name = "回收站",
                parentId = null,
                iconName = "delete",
                orderIndex = 99,
                updatedAt = "2026-09-04 10:20",
                createdAt = "2026-08-01 09:00",
                isRecycleBin = true
            )
        )

        val initialMockEntries = listOf(
            UiVaultEntry(
                id = "1",
                title = "Google Workspace",
                username = "alex.developer@gmail.com",
                url = "https://accounts.google.com",
                isPasskey = true,
                passkeyRpId = "google.com",
                category = EntryCategory.PASSKEY,
                strengthBits = 128,
                notes = "主工作邮箱，已绑定硬件 YubiKey 与 Passkey",
                updatedAt = "2026-09-04 09:15",
                createdAt = "2026-08-01 09:30",
                orderIndex = 1,
                groupId = "group_work",
                iconName = "public",
                customFields = listOf(
                    UiCustomField(id = "f1", key = "PIN 备用码", value = "891204", isProtected = true),
                    UiCustomField(id = "f2", key = "应急联系邮箱", value = "emergency@alex.dev", isProtected = false)
                ),
                attachments = listOf(
                    UiAttachment(
                        id = "att1",
                        fileName = "yubikey_backup_cert.pfx",
                        fileSizeFormatted = "18.4 KB",
                        mimeType = "application/x-pkcs12",
                        addedAt = "2026-08-15"
                    )
                ),
                revisions = listOf(
                    UiEntryRevision(
                        id = "rev1",
                        modifiedAt = "2026-08-20 14:10",
                        summary = "密码重置与安全增强",
                        username = "alex.developer@gmail.com",
                        notes = "初次设置工作空间邮箱"
                    )
                )
            ),
            UiVaultEntry(
                id = "2",
                title = "GitHub Enterprise",
                username = "octocat-dev",
                url = "https://github.com/login",
                isPasskey = false,
                totpCode = "849 201",
                totpRemainingSeconds = 16,
                totpPeriod = 30,
                totpDigits = 6,
                totpAlgorithm = "SHA1",
                category = EntryCategory.LOGIN,
                strengthBits = 118,
                notes = "代码仓库，必须配合 TOTP 验证码使用",
                updatedAt = "2026-09-03 18:30",
                createdAt = "2026-08-02 14:15",
                orderIndex = 2,
                groupId = "group_dev",
                iconName = "code",
                customFields = listOf(
                    UiCustomField(id = "f3", key = "Personal Access Token", value = "ghp_92fKa892JkLmNvP12089xZaB", isProtected = true)
                ),
                attachments = listOf(
                    UiAttachment(
                        id = "att2",
                        fileName = "github_recovery_codes.txt",
                        fileSizeFormatted = "2.1 KB",
                        mimeType = "text/plain",
                        addedAt = "2026-08-20"
                    )
                )
            ),
            UiVaultEntry(
                id = "3",
                title = "Twitter / X",
                username = "@tech_lead",
                url = "https://twitter.com",
                isPasskey = false,
                category = EntryCategory.LOGIN,
                strengthBits = 96,
                notes = "个人社交账号",
                updatedAt = "2026-09-01 12:00",
                createdAt = "2026-08-08 16:00",
                orderIndex = 3,
                groupId = "group_social",
                iconName = "forum"
            ),
            UiVaultEntry(
                id = "4",
                title = "AWS IAM Console",
                username = "admin-root",
                url = "https://aws.amazon.com/console",
                isPasskey = true,
                passkeyRpId = "aws.amazon.com",
                totpCode = "512 098",
                totpRemainingSeconds = 24,
                category = EntryCategory.PASSKEY,
                strengthBits = 132,
                notes = "生产云环境根账号，最高特权",
                updatedAt = "2026-08-20 17:00",
                createdAt = "2026-08-03 10:00",
                orderIndex = 4,
                groupId = "group_dev",
                iconName = "cloud"
            ),
            UiVaultEntry(
                id = "5",
                title = "招商银行经典白金信用卡",
                username = "ZHANG SAN",
                url = "https://www.cmbchina.com",
                category = EntryCategory.CARD,
                cardNumberMasked = "**** **** **** 8826",
                cardHolder = "ZHANG SAN",
                cardExpiry = "08/29",
                cardCvv = "639",
                notes = "主刷卡，账单日每月 7 号，还款日每月 25 号",
                updatedAt = "2026-09-01 12:00",
                createdAt = "2026-08-05 10:00",
                orderIndex = 5,
                groupId = "group_finance",
                iconName = "credit_card"
            ),
            UiVaultEntry(
                id = "6",
                title = "机房应急恢复指南与服务器密码",
                username = "Server Room Memo",
                url = "",
                category = EntryCategory.NOTE,
                notes = "机柜 A-12-04 物理钥匙存放于保险柜 #2\n门禁 PIN: 991204\n主备路由网段: 10.0.100.1/24\n紧急技术联系人: 138-0000-0000",
                updatedAt = "2026-09-02 15:30",
                createdAt = "2026-08-06 09:00",
                orderIndex = 6,
                groupId = "group_dev",
                iconName = "description"
            ),
            UiVaultEntry(
                id = "entry_recycled_1",
                title = "Legacy Redis Cache Server",
                username = "redis-cluster-admin",
                url = "redis://192.168.1.120:6379",
                isPasskey = false,
                category = EntryCategory.LOGIN,
                strengthBits = 85,
                notes = "已下线的旧版测试集群",
                updatedAt = "2026-08-10 11:20",
                createdAt = "2026-07-20 09:00",
                orderIndex = 5,
                groupId = "group_recycle_bin",
                iconName = "dns"
            )
        )
    }

    override suspend fun getEntryTotpSecretChars(entryId: String): CharArray? = null

    override suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot? = null

    override suspend fun getAttachmentData(entryId: String, refIndex: Int): ByteArray? = null

    /**
     * ISSUE-P3-337 AC③：置 true 驱动「只读会话」分支（顶栏扫码与编辑页导入的硬拒绝路径）。
     * 默认 false，不影响任何既有用例。
     */
    var sessionReadOnly: Boolean = false

    override fun isSessionReadOnly(): Boolean = sessionReadOnly

    // TASK-13 新契约：Fake 仓储不支持导出（如实失败），模板安装幂等成功
    override suspend fun exportKdbxBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("Fake 仓储不支持 KDBX 导出"),
            "测试 Fake 不支持导出"
        )

    override suspend fun exportVaultXmlBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("Fake 仓储不支持 XML 导出"),
            "测试 Fake 不支持导出"
        )

    override suspend fun exportVaultCsvBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("Fake 仓储不支持 CSV 导出"),
            "测试 Fake 不支持导出"
        )

    override suspend fun exportKeyFileBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("Fake 仓储不支持密钥文件导出"),
            "测试 Fake 不支持导出"
        )

    override suspend fun installEntryTemplates(): com.keepasskey.core.result.KdbxResult<Unit> =
        com.keepasskey.core.result.KdbxResult.Success(Unit)
}
