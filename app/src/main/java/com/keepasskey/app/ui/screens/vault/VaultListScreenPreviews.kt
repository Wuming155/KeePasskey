package com.keepasskey.app.ui.screens.vault

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
                databaseName = "Preview Vault.kdbx",
                currentGroups = com.keepasskey.app.ui.preview.PreviewGroups,
                entries = com.keepasskey.app.ui.preview.PreviewEntries,
                totalEntriesCount = 4,
                sortOption = VaultSortOption.NAME_ASC,
                lastSyncTimeText = "预览同步时间 10:25",
                decorations = com.keepasskey.app.ui.preview.PreviewDecorations
            ),
            snackbarHostState = remember { SnackbarHostState() },
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
