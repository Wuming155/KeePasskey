package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultDatabaseInfo

/**
 * 来源类型：本地存储、WebDAV 云端、S3 兼容对象存储
 */
enum class OpenVaultSourceType(val label: String, val desc: String) {
    LOCAL("本地设备存储", "通过系统文件选择器打开设备存储或 SD 卡中的 .kdbx 文件"),
    WEBDAV("WebDAV 云存储", "从 Nextcloud / 坚果云 / 群晖 NAS / ownCloud 导入"),
    S3_COMPATIBLE("兼容 S3 对象存储", "从 Cloudflare R2 / AWS S3 / MinIO / 阿里云 OSS 导入")
}

/**
 * 密码库选择与管理页面 UI 状态
 */
data class DatabasePickerUiState(
    val databases: List<VaultDatabaseInfo> = emptyList(),
    val isLoading: Boolean = false,
    val showCreateDialog: Boolean = false,
    val showOpenSourceDialog: Boolean = false,
    val userMessage: UiMessage? = null
)
