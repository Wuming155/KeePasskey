package com.keepasskey.app.ui.screens.database

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultDatabaseInfo

/**
 * 来源类型：本地存储、WebDAV 云端、S3 兼容对象存储
 *
 * P3-23 说明：[label] 是经 `importExternalDatabase(syncType = ...)` 落库并随数据库卡片
 * 回显的持久化标签（DatabasePickerViewModel 消费），值需保持稳定，不资源化；
 * 展示用说明文案资源化为 [descRes]（Compose 层经 stringResource 解析）。
 */
enum class OpenVaultSourceType(
    val label: String,
    @StringRes val descRes: Int
) {
    LOCAL("本地设备存储", R.string.picker_source_local_desc),
    WEBDAV("WebDAV 云存储", R.string.picker_source_webdav_desc),
    S3_COMPATIBLE("兼容 S3 对象存储", R.string.picker_source_s3_desc)
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
