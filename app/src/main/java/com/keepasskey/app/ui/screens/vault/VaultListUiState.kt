package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 密码库排序选项（支持默认顺序、按名称、按日期、按修改日期、按创建日期）
 */
enum class VaultSortOption(val label: String) {
    DEFAULT("默认顺序"),
    NAME("按名称排序"),
    DATE("按日期排序"),
    MODIFIED_DATE("按修改日期排序"),
    CREATED_DATE("按创建日期排序")
}

/**
 * 主密码库列表页面的不可变 UI 状态
 */
data class VaultListUiState(
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val sortOption: VaultSortOption = VaultSortOption.DEFAULT,
    val currentGroupId: String? = null,
    val isInsideRecycleBin: Boolean = false,
    val breadcrumbs: List<VaultGroup> = emptyList(),
    val currentGroups: List<VaultGroup> = emptyList(),
    val entries: List<UiVaultEntry> = emptyList(),
    val totalEntriesCount: Int = 0,
    val databaseName: String = "keepasskey.kdbx",
    val userMessage: String? = null,
    // 列表视图显示偏好（来自设置仓库，设置页外观项可调）
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true
)
