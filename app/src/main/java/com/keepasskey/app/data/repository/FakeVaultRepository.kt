package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阶段 1 内存实现，提供群组文件夹与凭据的初始假数据和响应式状态更新。
 * 当阶段 2 真实 KDBX 数据库就绪后，可通过 Hilt 绑定无缝替换。
 */
@Singleton
class FakeVaultRepository @Inject constructor() : VaultRepository {

    private val groupsFlow = MutableStateFlow(initialMockGroups)
    private val entriesFlow = MutableStateFlow(initialMockEntries)

    override fun getGroups(): Flow<List<VaultGroup>> {
        return groupsFlow.asStateFlow()
    }

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
        groupsFlow.value = groupsFlow.value.filter { it.id != id }
    }

    override fun getEntries(): Flow<List<UiVaultEntry>> {
        return entriesFlow.asStateFlow()
    }

    override fun getEntry(id: String): Flow<UiVaultEntry?> {
        return entriesFlow.map { list -> list.find { it.id == id } }
    }

    override suspend fun saveEntry(entry: UiVaultEntry) {
        val current = entriesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            current[index] = entry
        } else {
            current.add(0, entry)
        }
        entriesFlow.value = current
    }

    override suspend fun deleteEntry(id: String) {
        entriesFlow.value = entriesFlow.value.filter { it.id != id }
    }

    companion object {
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
                groupId = "group_work"
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
                groupId = "group_dev"
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
                groupId = "group_social"
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
                groupId = "group_dev"
            ),
            UiVaultEntry(
                id = "5",
                title = "Server SSH 恢复密钥",
                username = "root@192.168.1.100",
                passwordPlain = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG...",
                url = "ssh://192.168.1.100",
                category = EntryCategory.NOTE,
                strengthBits = 256,
                notes = "主集群备份节点 SSH Key",
                updatedAt = "2026-07-11 08:30",
                createdAt = "2026-07-10 12:00",
                orderIndex = 5,
                groupId = "group_dev"
            ),
            UiVaultEntry(
                id = "6",
                title = "招商银行企业网银",
                username = "cmb_corp_admin",
                passwordPlain = "CmB!98#vL2\$xQ",
                url = "https://cmbchina.com",
                totpCode = "638 190",
                totpRemainingSeconds = 28,
                category = EntryCategory.LOGIN,
                strengthBits = 120,
                notes = "财务专用网银账户",
                updatedAt = "2026-08-15 11:20",
                createdAt = "2026-08-05 11:00",
                orderIndex = 6,
                groupId = "group_finance"
            ),
            UiVaultEntry(
                id = "7",
                title = "PayPal Business",
                username = "finance@company.com",
                passwordPlain = "pP!28#vK9@zL5\$",
                url = "https://paypal.com",
                isPasskey = true,
                passkeyRpId = "paypal.com",
                category = EntryCategory.PASSKEY,
                strengthBits = 128,
                notes = "跨境支付商户中心",
                updatedAt = "2026-08-10 15:40",
                createdAt = "2026-08-06 09:30",
                orderIndex = 7,
                groupId = "group_finance"
            ),
            UiVaultEntry(
                id = "8",
                title = "Telegram Secure",
                username = "+1 (555) 019-2831",
                passwordPlain = "Tg#99!vL2@mQ8\$",
                url = "https://telegram.org",
                isPasskey = true,
                passkeyRpId = "telegram.org",
                category = EntryCategory.PASSKEY,
                strengthBits = 124,
                notes = "端到端加密通讯",
                updatedAt = "2026-08-01 19:10",
                createdAt = "2026-07-25 18:00",
                orderIndex = 8,
                groupId = "group_social"
            ),
            UiVaultEntry(
                id = "9",
                title = "Microsoft Entra ID",
                username = "admin@corp.ms",
                passwordPlain = "Ms#Entra!99\$wP",
                url = "https://login.microsoftonline.com",
                isPasskey = true,
                passkeyRpId = "login.microsoftonline.com",
                category = EntryCategory.PASSKEY,
                strengthBits = 130,
                notes = "企业组织单点登录",
                updatedAt = "2026-07-28 10:50",
                createdAt = "2026-07-20 14:00",
                orderIndex = 9,
                groupId = "group_passkeys"
            ),
            UiVaultEntry(
                id = "10",
                title = "Cloudflare 边缘中枢",
                username = "devops@company.com",
                passwordPlain = "Cf#Edge!88\$kL",
                url = "https://dash.cloudflare.com",
                isPasskey = true,
                passkeyRpId = "cloudflare.com",
                category = EntryCategory.PASSKEY,
                strengthBits = 135,
                notes = "全球 DNS 与 WAF 防护管控",
                updatedAt = "2026-07-20 16:30",
                createdAt = "2026-07-15 10:00",
                orderIndex = 10,
                groupId = "group_passkeys"
            ),
            UiVaultEntry(
                id = "11",
                title = "Wi-Fi 办公室访客网络",
                username = "Office-Guest",
                passwordPlain = "Ke3p@ssK3y!2026",
                url = "wifi://Office-Guest",
                category = EntryCategory.NOTE,
                strengthBits = 112,
                notes = "5Ghz 频段访客独立隔离 SSID，访客专用",
                updatedAt = "2026-07-01 10:00",
                createdAt = "2026-07-01 09:00",
                orderIndex = 11,
                groupId = null
            )
        )
    }
}
