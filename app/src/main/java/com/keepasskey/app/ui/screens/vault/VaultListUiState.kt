package com.keepasskey.app.ui.screens.vault

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 排序方向：升序 / 降序
 */
enum class SortOrder(@StringRes val labelRes: Int) {
    ASCENDING(R.string.sort_order_ascending),
    DESCENDING(R.string.sort_order_descending)
}

/**
 * 密码库排序字段选项
 */
enum class VaultSortOption(@StringRes val labelRes: Int) {
    DEFAULT(R.string.sort_option_default),
    NAME_ASC(R.string.sort_option_name_asc),
    NAME_DESC(R.string.sort_option_name_desc),
    MODIFIED_DESC(R.string.sort_option_modified_desc),
    MODIFIED_ASC(R.string.sort_option_modified_asc),
    CREATED_DESC(R.string.sort_option_created_desc),
    CREATED_ASC(R.string.sort_option_created_asc)
}

/**
 * 云端同步状态指示
 */
enum class VaultSyncStatus(@StringRes val labelRes: Int) {
    SYNCED(R.string.sync_status_synced),
    SYNCING(R.string.sync_status_syncing),
    OFFLINE(R.string.sync_status_offline),
    CONFLICT(R.string.sync_status_conflict)
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
    val allGroups: List<VaultGroup> = emptyList(),
    val entries: List<UiVaultEntry> = emptyList(),
    val totalEntriesCount: Int = 0,
    val databaseName: String = "",
    val syncStatus: VaultSyncStatus = VaultSyncStatus.SYNCED,
    val isSyncing: Boolean = false,
    // 上次同步完成时间的展示文案（如下拉指示区显示「今天 10:25」）；空串表示本会话尚未同步
    val lastSyncTimeText: String = "",
    val isLocked: Boolean = false,
    val isBatchMode: Boolean = false,
    val selectedEntryIds: Set<String> = emptySet(),
    val userMessage: UiMessage? = null,
    // H2 整改：存在待解决的同步冲突会话时为 true，驱动「去解决冲突」入口
    val hasPendingConflict: Boolean = false,
    // H4-只读整改：当前会话以只读模式打开时为 true，禁用新增/编辑/删除入口
    val isReadOnly: Boolean = false,
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true,
    val showUrlInList: Boolean = true,
    val hideFabOnScroll: Boolean = false,
    // ISSUE-P3-02：条目展示装饰（自定义图标投影 + Notes/URL 字段引用展开文案）。
    // 图标解码与引用解析均在状态层完成，Composable 只做纯绘制（禁止在 UI 内做 IO/解码）。
    val decorations: EntryDecorations = EntryDecorations.EMPTY
)
