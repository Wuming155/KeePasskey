package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultDatabaseInfo

/**
 * 来源类型：本地存储、WebDAV 云端、S3 兼容对象存储
 *
 * P3-23 说明：[label] 是经 `importExternalDatabase(syncType = ...)` 落库并随数据库卡片
 * 回显的持久化标签（DatabasePickerViewModel 消费），值需保持稳定，不资源化。
 * ISSUE-P3-330（整改中新增发现）：原 descRes（各来源说明）声明后从未被渲染，已连同
 * 字符串一并移除——对话框来源行实际渲染 picker_chip_*，不得再加回死声明。
 */
enum class OpenVaultSourceType(
    val label: String
) {
    LOCAL("本地设备存储"),
    WEBDAV("WebDAV 云存储"),
    S3_COMPATIBLE("兼容 S3 对象存储")
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
