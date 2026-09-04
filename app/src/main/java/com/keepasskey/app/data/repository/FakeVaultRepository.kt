package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阶段 1 内存实现，提供群组文件夹、凭据、多数据库管理与回收站的假数据和响应式状态更新。
 * 当阶段 2 真实 KDBX 数据库就绪后，可通过 Hilt 绑定无缝替换。
 */
@Singleton
class FakeVaultRepository @Inject constructor() : VaultRepository {

    private val databasesFlow = MutableStateFlow(initialMockDatabases)
    private val groupsFlow = MutableStateFlow(initialMockGroups)
    private val entriesFlow = MutableStateFlow(initialMockEntries)

    override fun getDatabases(): Flow<List<VaultDatabaseInfo>> = databasesFlow.asStateFlow()

    override suspend fun selectDatabase(id: String) {
        val current = databasesFlow.value.map { db ->
            db.copy(isActive = (db.id == id))
        }
        databasesFlow.value = current
    }

    override suspend fun createDatabase(
        name: String,
        masterPassword: String,
        keyFile: Boolean,
        preset: String
    ) {
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
    }

    override suspend fun removeDatabase(id: String) {
        databasesFlow.value = databasesFlow.value.filter { it.id != id }
    }

    override suspend fun importExternalDatabase(name: String, path: String, syncType: String) {
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
    }

    override fun getGroups(): Flow<List<VaultGroup>> = groupsFlow.asStateFlow()

    override suspend fun saveGroup(group: VaultGroup) {
        val current = groupsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            current[index] = group
        } else {
            current.add(0, group)
        }
        groupsFlow.value = current
    }

    override suspend fun deleteGroup(id: String) {
        // 删除文件夹及其下属条目或移入回收站
        groupsFlow.value = groupsFlow.value.filter { it.id != id }
        entriesFlow.value = entriesFlow.value.map { entry ->
            if (entry.groupId == id) entry.copy(groupId = "group_recycle_bin") else entry
        }
    }

    override fun getEntries(): Flow<List<UiVaultEntry>> = entriesFlow.asStateFlow()

    override fun getEntry(id: String): Flow<UiVaultEntry?> {
        return entriesFlow.map { list -> list.find { it.id == id } }
    }

    override suspend fun saveEntry(entry: UiVaultEntry) {
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
                passwordPlain = old.passwordPlain,
                notes = old.notes
            )
            val updatedRevisions = listOf(rev) + old.revisions
            current[index] = entry.copy(revisions = updatedRevisions)
        } else {
            current.add(0, entry)
        }
        entriesFlow.value = current
    }

    override suspend fun deleteEntry(id: String) {
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
    }

    override suspend fun restoreEntry(id: String) {
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(groupId = null)
            entriesFlow.value = current
        }
    }

    override suspend fun emptyRecycleBin() {
        entriesFlow.value = entriesFlow.value.filter { it.groupId != "group_recycle_bin" }
    }

    override suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?) {
        val current = entriesFlow.value.map { entry ->
            if (entry.id in entryIds) entry.copy(groupId = targetGroupId) else entry
        }
        entriesFlow.value = current
    }

    override suspend fun batchDeleteEntries(entryIds: Set<String>) {
        val current = entriesFlow.value.map { entry ->
            if (entry.id in entryIds) entry.copy(groupId = "group_recycle_bin") else entry
        }
        entriesFlow.value = current
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
                passwordPlain = "G8#wK9!mP2\$zL5@xV",
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
                        passwordPlain = "PrevPass2026@#",
                        notes = "初次设置工作空间邮箱"
                    )
                )
            ),
            UiVaultEntry(
                id = "2",
                title = "GitHub Enterprise",
                username = "octocat-dev",
                passwordPlain = "vP9#wL2@xZ7&kM4\$qR",
                url = "https://github.com/login",
                isPasskey = false,
                totpCode = "849 201",
                totpRemainingSeconds = 16,
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
                passwordPlain = "tL5#9vX2\$kP8@mQ",
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
                passwordPlain = "aW7#kL9\$vP3@mX8!zQ",
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
                passwordPlain = "rEdIs#99!Temp",
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
}
