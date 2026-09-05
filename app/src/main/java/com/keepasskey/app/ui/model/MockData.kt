package com.keepasskey.app.ui.model

/**
 * 界面展现层群组/文件夹实体（参考 KeePassDX Group 模型）
 */
data class VaultGroup(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val iconName: String = "folder",
    val orderIndex: Int = 0,
    val updatedAt: String = "2026-09-04 10:00",
    val createdAt: String = "2026-08-01 09:00",
    val isRecycleBin: Boolean = false
)

/**
 * 自定义字段数据实体（支持受密码保护掩码展示）。
 *
 * F2 整改（CWE-316）：受保护字段（isProtected=true，如 Passkey 私钥/TOTP 种子/恢复码）的
 * 明文**不随条目投影下发**——[value] 在仓库投影层对受保护字段恒为空串，仅在用户显式
 * 查看/编辑时经 VaultRepository.getEntryProtectedField 按需单条解密。
 */
data class UiCustomField(
    val id: String,
    val key: String,
    val value: String,
    val isProtected: Boolean = false,
    val isVisible: Boolean = false
)

/**
 * 条目嵌入附件实体
 */
data class UiAttachment(
    val id: String,
    val fileName: String,
    val fileSizeFormatted: String,
    val mimeType: String = "application/octet-stream",
    val addedAt: String = "2026-09-04"
)

/**
 * 条目历史修改快照版本。
 * M1 整改：不再携带密码明文（passwordPlain），历史密码须经仓库按需单条解密。
 */
data class UiEntryRevision(
    val id: String,
    val modifiedAt: String,
    val summary: String,
    val username: String,
    val notes: String = ""
)

/**
 * 密码库数据库信息实体（支持多库管理与切换）
 */
data class VaultDatabaseInfo(
    val id: String,
    val name: String,
    val path: String,
    val isRemote: Boolean = false,
    val syncType: String = "WebDAV",
    val lastOpenedAt: String = "今天 10:25",
    val fileSizeFormatted: String = "142 KB",
    val isActive: Boolean = false,
    val encryptionPreset: String = "ChaCha20 + Argon2id"
)

/**
 * 界面预览与交互使用的凭据数据实体。
 *
 * M1 整改（CWE-316）：不再携带密码明文（passwordPlain）——列表/详情快照整体驻留 StateFlow，
 * 明文驻留会使堆转储可读全库密码；密码仅在用户显式查看/复制时经
 * [com.keepasskey.app.data.repository.VaultRepository.getEntryPassword] 按需单条解密。
 * F2 整改：同样不再携带 TOTP 种子（totpSecret），验证码经仓库 calculateEntryTotp 按需即时计算，
 * 受保护自定义字段明文亦不在投影层下发。
 */
data class UiVaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val passwordMasked: String = "••••••••••••••••",
    val url: String,
    val isPasskey: Boolean = false,
    val passkeyRpId: String? = null,
    val totpCode: String? = null,
    val totpRemainingSeconds: Int = 30,
    val totpPeriod: Int = 30,
    val totpDigits: Int = 6,
    val totpAlgorithm: String = "SHA1",
    val category: EntryCategory = EntryCategory.LOGIN,
    val strengthBits: Int = 112,
    val notes: String = "",
    val updatedAt: String = "2026-09-04 10:25",
    val createdAt: String = "2026-08-01 09:00",
    val orderIndex: Int = 0,
    val groupId: String? = null,
    val iconName: String = "key",
    val cardNumberMasked: String? = null,
    val cardHolder: String? = null,
    val cardExpiry: String? = null,
    val cardCvv: String? = null,
    val customFields: List<UiCustomField> = emptyList(),
    val attachments: List<UiAttachment> = emptyList(),
    val revisions: List<UiEntryRevision> = emptyList()
)

enum class EntryCategory(val label: String) {
    ALL("全部"),
    LOGIN("密码凭据"),
    PASSKEY("通行密钥"),
    CARD("银行卡"),
    NOTE("安全便签")
}
