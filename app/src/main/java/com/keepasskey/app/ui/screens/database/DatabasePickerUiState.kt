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
 * 「打开已有 KDBX 密码库」对话框的提交载荷（ISSUE-P2-399）。
 *
 * 本地库只带路径；云端库携带完整连接凭据（[com.keepasskey.app.sync.CloudVaultImportRequest]，
 * 凭据 `CharArray` 为借用语义：对话框传副本后即交出，由导入链路用毕擦除）。
 */
sealed interface OpenVaultSubmission {

    /** 本地库：SAF 选择器选定的 uri 或文件路径 */
    data class Local(val name: String, val path: String) : OpenVaultSubmission

    /** 云端库：WebDAV / S3 完整凭据，由 [com.keepasskey.app.sync.CloudVaultImporter] 消费 */
    data class Cloud(val request: com.keepasskey.app.sync.CloudVaultImportRequest) : OpenVaultSubmission
}

/**
 * 密码库选择与管理页面 UI 状态
 */
data class DatabasePickerUiState(
    val databases: List<VaultDatabaseInfo> = emptyList(),
    /**
     * ISSUE-P2-354 AC①：建库进行中。曾是「仅预览置值、无渲染点」的死字段，
     * 现由 `DatabasePickerViewModel.isCreatingFlow` 驱动：`DatabasePickerScreen`
     * 据此渲染进度并禁用入口，建库向导据此进入 busy（禁提交 / 禁关闭）。
     */
    val isLoading: Boolean = false,
    val showCreateDialog: Boolean = false,
    val showOpenSourceDialog: Boolean = false,
    val userMessage: UiMessage? = null
)
