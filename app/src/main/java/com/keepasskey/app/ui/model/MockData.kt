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
    val createdAt: String = "2026-08-01 09:00"
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
    val category: EntryCategory = EntryCategory.LOGIN,
    val strengthBits: Int = 112,
    val notes: String = "",
    val updatedAt: String = "2026-09-04 10:25",
    val createdAt: String = "2026-08-01 09:00",
    val orderIndex: Int = 0,
    val groupId: String? = null
)

enum class EntryCategory(val label: String) {
    ALL("全部"),
    LOGIN("登录凭据"),
    PASSKEY("通行密钥"),
    NOTE("安全笔记"),
    CARD("信用卡")
}

