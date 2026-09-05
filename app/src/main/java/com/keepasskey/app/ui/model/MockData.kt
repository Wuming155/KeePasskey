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
 * 自定义字段数据实体（支持受密码保护掩码展示）
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
 * 条目历史修改快照版本
 */
data class UiEntryRevision(
    val id: String,
    val modifiedAt: String,
    val summary: String,
    val username: String,
    val passwordPlain: String,
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
 * 界面预览与交互使用的凭据数据实体
 */
data class UiVaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val passwordMasked: String = "••••••••••••••••",
    val passwordPlain: String = "k9#mP!2\$zQ8&vL5@wR",
    val url: String,
    val isPasskey: Boolean = false,
    val passkeyRpId: String? = null,
    val totpCode: String? = null,
    val totpRemainingSeconds: Int = 30,
    val totpSecret: String? = null,
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
