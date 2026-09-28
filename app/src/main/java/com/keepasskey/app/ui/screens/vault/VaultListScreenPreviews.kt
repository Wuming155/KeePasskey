package com.keepasskey.app.ui.screens.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 密码库列表页的 IDE 预览（ISSUE-P3-297 批自 `VaultListScreen.kt` 拆出：
 * 该文件加行后撞 tier1 恒零线，预览按 detail 包 `EntryDetailScreenPreviews.kt`
 * 同型先例落独立文件；纯结构性搬移，预览内容零变化）。
 *
 * IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI。
 */
@Preview(name = "密码库列表 - 浅色", showBackground = true)
@Preview(name = "密码库列表 - 深色", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListContentPreview() {
    KeePasskeyTheme {
        VaultListContent(
            uiState = VaultListUiState().copy(
                isLoading = false,
                databaseName = "Preview Vault.kdbx",
                currentGroups = com.keepasskey.app.ui.preview.PreviewGroups,
                entries = com.keepasskey.app.ui.preview.PreviewEntries,
                totalEntriesCount = 4,
                sortOption = VaultSortOption.NAME_ASC,
                lastSyncTimeText = "预览同步时间 10:25",
                decorations = com.keepasskey.app.ui.preview.PreviewDecorations
            ),
            onSearchQueryChange = {},
            onSortOptionSelect = {},
            onGroupClick = {},
            onNavigateUp = {},
            onNavigateToBreadcrumb = {},
            onEntryClick = {},
            onEntryLongClick = {},
            onCopyPassword = {},
            onCopyUsername = {},
            onCopyTotp = {},
            onAddEntryClick = {},
            onCreateFromTemplate = {},
            onCreateGroup = { _, _ -> },
            onRenameGroup = { _, _ -> },
            onChangeGroupIcon = { _, _ -> },
            onDeleteGroup = {},
            onRestoreEntry = {},
            onPurgeEntry = {},
            onEmptyRecycleBin = {},
            onTriggerSync = {},
            onNavigateToConflictResolver = {},
            onLockClick = {},
            onSelectAllBatch = {},
            onClearBatch = {},
            onBatchDelete = {},
            onBatchMove = {},
            onKillApp = null,
            onAutoActivateSearchConsumed = {}
        )
    }
}

/**
 * ISSUE-P3-352 AC①：搜索无结果空态（专用文案 + 「新建凭据条目 / 清除搜索」双出口）。
 * 只读形态（onCreateEntryFromSearch = null，仅剩清除搜索）由第二个预览覆盖。
 */
@Preview(name = "搜索无结果 - 浅色", showBackground = true)
@Preview(name = "搜索无结果 - 深色", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListSearchEmptyPreview() {
    KeePasskeyTheme {
        VaultListContent(
            uiState = VaultListUiState().copy(
                isLoading = false,
                databaseName = "Preview Vault.kdbx",
                searchQuery = "login.example.com"
            ),
            onSearchQueryChange = {},
            onSortOptionSelect = {},
            onGroupClick = {},
            onNavigateUp = {},
            onNavigateToBreadcrumb = {},
            onEntryClick = {},
            onEntryLongClick = {},
            onCopyPassword = {},
            onCopyUsername = {},
            onAddEntryClick = {},
            onCreateEntryFromSearch = {},
            onClearSearch = {},
            onCreateGroup = { _, _ -> },
            onRenameGroup = { _, _ -> },
            onChangeGroupIcon = { _, _ -> },
            onDeleteGroup = {},
            onRestoreEntry = {},
            onPurgeEntry = {},
            onEmptyRecycleBin = {},
            onTriggerSync = {},
            onSelectAllBatch = {},
            onClearBatch = {},
            onBatchDelete = {},
            onBatchMove = {}
        )
    }
}

/** ISSUE-P3-352 AC①：只读会话的搜索空态——不呈现新建出口，仅保留「清除搜索」。 */
@Preview(name = "搜索无结果（只读）- 浅色", showBackground = true)
@Preview(name = "搜索无结果（只读）- 深色", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListSearchEmptyReadOnlyPreview() {
    KeePasskeyTheme {
        VaultListContent(
            uiState = VaultListUiState().copy(
                isLoading = false,
                databaseName = "Preview Vault.kdbx",
                searchQuery = "login.example.com",
                isReadOnly = true
            ),
            onSearchQueryChange = {},
            onSortOptionSelect = {},
            onGroupClick = {},
            onNavigateUp = {},
            onNavigateToBreadcrumb = {},
            onEntryClick = {},
            onEntryLongClick = {},
            onCopyPassword = {},
            onCopyUsername = {},
            onAddEntryClick = {},
            onCreateEntryFromSearch = null,
            onClearSearch = {},
            onCreateGroup = { _, _ -> },
            onRenameGroup = { _, _ -> },
            onChangeGroupIcon = { _, _ -> },
            onDeleteGroup = {},
            onRestoreEntry = {},
            onPurgeEntry = {},
            onEmptyRecycleBin = {},
            onTriggerSync = {},
            onSelectAllBatch = {},
            onClearBatch = {},
            onBatchDelete = {},
            onBatchMove = {}
        )
    }
}
