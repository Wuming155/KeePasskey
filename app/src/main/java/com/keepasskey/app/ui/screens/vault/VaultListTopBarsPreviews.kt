package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 列表页两个顶栏的 IDE 预览（`ISSUE-P3-644` 自 `VaultListTopBars.kt` 按需拆出：
 * 该文件加只读分支与只读态预览后越过 tier2（400 行）阈值，预览按
 * `VaultListScreenPreviews.kt` / `EntryDetailScreenPreviews.kt` 同型先例落独立文件；
 * 预览内容与注记逐条保留，语义零变化）。
 *
 * IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI。
 */
@Preview(name = "列表搜索顶栏 - 浅色", showBackground = true)
@Preview(name = "列表搜索顶栏 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListSearchTopBarPreview() {
    KeePasskeyTheme {
        VaultListSearchTopBar(
            searchQuery = "预览",
            onSearchQueryChange = {},
            isInsideRecycleBin = false,
            sortOption = VaultSortOption.NAME_ASC,
            onSortClick = {},
            onLockClick = {},
            onEmptyRecycleBinClick = {},
            autoActivateSearch = false,
            onAutoActivateSearchConsumed = {},
            onKillApp = {}
        )
    }
}

/**
 * `ISSUE-P3-340`：补 `autoActivateSearch = true` 那一态（从搜索入口进来了、搜索框须自动聚焦并弹键盘）。
 * 该态与默认态的差别在**焦点与光标**，只在真机点过一次才看得见；预览虽不模拟键盘，
 * 但至少把「该态有没有把标题挤掉」这一层暴露给导出图。
 */
@Preview(name = "列表搜索顶栏-自动聚焦 - 浅色", showBackground = true)
@Preview(name = "列表搜索顶栏-自动聚焦 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListSearchTopBarAutoFocusPreview() {
    KeePasskeyTheme {
        VaultListSearchTopBar(
            searchQuery = "",
            onSearchQueryChange = {},
            isInsideRecycleBin = false,
            sortOption = VaultSortOption.NAME_ASC,
            onSortClick = {},
            onLockClick = {},
            onEmptyRecycleBinClick = {},
            autoActivateSearch = true,
            onAutoActivateSearchConsumed = {},
            onKillApp = {}
        )
    }
}

/**
 * ISSUE-P3-644：只读会话顶栏——溢出菜单内**不出现**「选择」批量入口。
 * 该差异只在展开菜单后可见，故与默认态并排留档（同 `KeePassGroupRowReadOnlyPreview` 口径）。
 */
@Preview(name = "列表搜索顶栏-只读 - 浅色", showBackground = true)
@Preview(name = "列表搜索顶栏-只读 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListSearchTopBarReadOnlyPreview() {
    KeePasskeyTheme {
        VaultListSearchTopBar(
            searchQuery = "",
            onSearchQueryChange = {},
            isInsideRecycleBin = false,
            sortOption = VaultSortOption.DEFAULT,
            onSortClick = {},
            onLockClick = {},
            onEmptyRecycleBinClick = {},
            onSelectEntriesClick = {},
            isReadOnly = true,
            onKillApp = {}
        )
    }
}

/**
 * ISSUE-P3-644：只读会话的批量顶栏——三个批量写按钮置灰 + 原因文案。
 * 置灰与说明同屏可见，正是本项要守的视觉状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "批量顶栏-只读 - 浅色", showBackground = true)
@Preview(name = "批量顶栏-只读 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListBatchModeTopBarReadOnlyPreview() {
    KeePasskeyTheme {
        VaultListBatchModeTopBar(
            selectedCount = 3,
            onClearBatch = {},
            onSelectAllBatch = {},
            onBatchMoveClick = {},
            onBatchDelete = {},
            isReadOnly = true
        )
    }
}
