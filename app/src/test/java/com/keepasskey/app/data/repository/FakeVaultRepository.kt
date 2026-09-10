package com.keepasskey.app.data.repository

import com.keepasskey.app.passkey.DomainMatcher
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
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
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

    override suspend fun changeMasterPassword(newPassword: CharArray): com.keepasskey.core.result.KdbxResult<Unit> {
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
        preset: String
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
            encryptionPreset = preset
        )
        val current = databasesFlow.value.map { it.copy(isActive = false) }.toMutableList()
        current.add(0, newDb)
        databasesFlow.value = current
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun removeDatabase(id: String): com.keepasskey.core.result.KdbxResult<Unit> {
        databasesFlow.value = databasesFlow.value.filter { it.id != id }
        return com.keepasskey.core.result.KdbxResult.Success(Unit)
    }

    override suspend fun importExternalDatabase(name: String, path: String, syncType: String): com.keepasskey.core.result.KdbxResult<Unit> {
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
            entry.copy(customFields = entry.customFields.map { cf ->
                if (cf.isProtected) cf.copy(value = "") else cf
            })
        }
    }

    override fun getEntry(id: String): Flow<UiVaultEntry?> {
        return entriesFlow.map { list -> list.find { it.id == id } }
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
    override suspend fun resolveFieldReferences(entryId: String, rawText: String): String? = rawText

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
            algorithm = entry.totpAlgorithm
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
        extraKdbxEntries.value = extraKdbxEntries.value + newEntry
        return newEntry
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

    override suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray? = null

    override fun isSessionReadOnly(): Boolean = false

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

    override suspend fun exportKeyFileBytes(): com.keepasskey.core.result.KdbxResult<ByteArray> =
        com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("Fake 仓储不支持密钥文件导出"),
            "测试 Fake 不支持导出"
        )

    override suspend fun installEntryTemplates(): com.keepasskey.core.result.KdbxResult<Unit> =
        com.keepasskey.core.result.KdbxResult.Success(Unit)
}
