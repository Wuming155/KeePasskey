package com.keepasskey.app.ui.screens.vault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    currentTheme: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeToggle: () -> Unit = {},
    onEntryClick: (String) -> Unit,
    onAddEntryClick: (String?) -> Unit,
    onLockClick: () -> Unit = {},
    onNavigateToConflictResolver: () -> Unit = {},
    /**
     * ISSUE-P3-17：`showKillAppOption` 开启且宿主可终止时非空——非空才呈现「彻底退出应用」入口。
     * 动作本体由 host（KeePasskeyApp）持有 Activity 上下文执行，本页只负责呈现与上行。
     */
    onKillApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: VaultListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // ISSUE-P3-17：页面每次进入组合时刷新进阶显示偏好快照
    // （ExtendedSettingsStore 只有同步快照 API，设置页改动返回本页即生效）
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    // 优雅的返回键处理：
    // 1. 处于批量选择模式时：取消批量选择
    // 2. 搜索框有输入内容时：清空搜索
    // 3. 处于子分组目录时：返回上一级目录
    // 4. 处于根目录时：不拦截，交由系统默认退出/返回
    BackHandler(
        enabled = uiState.isBatchMode ||
                uiState.searchQuery.isNotEmpty() ||
                uiState.currentGroupId != null
    ) {
        when {
            uiState.isBatchMode -> viewModel.clearBatchSelection()
            uiState.searchQuery.isNotEmpty() -> viewModel.onSearchQueryChange("")
            uiState.currentGroupId != null -> viewModel.navigateUp()
        }
    }

    VaultListContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSortOptionSelect = viewModel::setSortOption,
        onGroupClick = viewModel::enterGroup,
        onNavigateUp = viewModel::navigateUp,
        onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
        onEntryClick = { entryId ->
            if (uiState.isBatchMode) {
                viewModel.toggleEntrySelection(entryId)
            } else {
                onEntryClick(entryId)
            }
        },
        onEntryLongClick = { entryId ->
            if (!uiState.isBatchMode) {
                viewModel.startBatchMode(entryId)
            } else {
                viewModel.toggleEntrySelection(entryId)
            }
        },
        onCopyPassword = viewModel::copyPassword,
        onCopyUsername = viewModel::copyUsername,
        onAddEntryClick = { onAddEntryClick(uiState.currentGroupId) },
        onCreateGroup = viewModel::createGroup,
        onRenameGroup = viewModel::renameGroup,
        onChangeGroupIcon = viewModel::changeGroupIcon,
        onDeleteGroup = viewModel::deleteGroup,
        onRestoreEntry = viewModel::restoreEntry,
        onPurgeEntry = viewModel::purgeEntry,
        onEmptyRecycleBin = viewModel::emptyRecycleBin,
        onTriggerSync = viewModel::triggerPullRefresh,
        onNavigateToConflictResolver = onNavigateToConflictResolver,
        onLockClick = onLockClick,
        onSelectAllBatch = viewModel::selectAllEntries,
        onClearBatch = viewModel::clearBatchSelection,
        onBatchDelete = viewModel::batchDeleteSelected,
        onBatchMove = viewModel::batchMoveSelected,
        onKillApp = onKillApp,
        onAutoActivateSearchConsumed = viewModel::consumeAutoActivateSearch,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListContent(
    uiState: VaultListUiState,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String) -> Unit,
    onSortOptionSelect: (VaultSortOption) -> Unit,
    onGroupClick: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToBreadcrumb: (String?) -> Unit,
    onEntryClick: (String) -> Unit,
    onEntryLongClick: (String) -> Unit,
    onCopyPassword: (UiVaultEntry) -> Unit,
    onCopyUsername: (UiVaultEntry) -> Unit,
    onAddEntryClick: () -> Unit,
    onCreateGroup: (name: String, icon: String) -> Unit,
    onRenameGroup: (VaultGroup, String) -> Unit,
    onChangeGroupIcon: (VaultGroup, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onRestoreEntry: (String) -> Unit,
    onPurgeEntry: (String) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onTriggerSync: () -> Unit,
    onNavigateToConflictResolver: () -> Unit = {},
    onLockClick: () -> Unit = {},
    onSelectAllBatch: () -> Unit,
    onClearBatch: () -> Unit,
    onBatchDelete: () -> Unit,
    onBatchMove: (String?) -> Unit,
    // ISSUE-P3-17：非空才呈现「彻底退出应用」入口（偏好开启且宿主可终止）
    onKillApp: (() -> Unit)? = null,
    // ISSUE-P3-17：自动聚焦搜索栏意图已被消费的回执
    onAutoActivateSearchConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // ISSUE-P3-29：8 个对话框的可见性 / 目标对象由独立状态持有者承接（见 VaultListDialogHost.kt）
    val dialogs = rememberVaultListDialogController()
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    // ISSUE-P3-17：列表密度规格（偏好 → 行高 / 内边距 / 字号的唯一映射点）
    val densitySpec = remember(uiState.listDensity) {
        ListDensityPresenter.specOf(uiState.listDensity)
    }

    // ISSUE-P3-30：搜索态标记（仅用于「子库条目不参与搜索」的提示行；
    // 子库分区本身的可见性判定在状态层完成，见 VaultListUiState.childEntrySectionVisible）
    val isSearching = uiState.searchQuery.isNotBlank()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (uiState.isBatchMode) {
                VaultListBatchModeTopBar(
                    selectedCount = uiState.selectedEntryIds.size,
                    onClearBatch = onClearBatch,
                    onSelectAllBatch = onSelectAllBatch,
                    onBatchMoveClick = { dialogs.showBatchMoveDialog = true },
                    onBatchDelete = onBatchDelete
                )
            } else {
                VaultListSearchTopBar(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    isInsideRecycleBin = uiState.isInsideRecycleBin,
                    sortOption = uiState.sortOption,
                    onSortClick = { dialogs.showSortDialog = true },
                    onLockClick = onLockClick,
                    onEmptyRecycleBinClick = { dialogs.showEmptyRecycleBinDialog = true },
                    // ISSUE-P3-17：进入列表页自动聚焦搜索栏（一次性意图，消费后回执清除）
                    autoActivateSearch = uiState.autoActivateSearch,
                    onAutoActivateSearchConsumed = onAutoActivateSearchConsumed,
                    onKillApp = onKillApp
                )
            }
        },
        floatingActionButton = {
            VaultListFab(
                visible = !uiState.isBatchMode && !uiState.isInsideRecycleBin && (!uiState.hideFabOnScroll || !listState.isScrollInProgress),
                isReadOnly = uiState.isReadOnly,
                onClick = { dialogs.showCreateTypeDialog = true }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = onTriggerSync,
            state = pullRefreshState,
            indicator = {
                LastSyncPullIndicator(
                    distanceFraction = pullRefreshState.distanceFraction,
                    isSyncing = uiState.isSyncing,
                    lastSyncTimeText = uiState.lastSyncTimeText
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.hasPendingConflict) {
                    item { PendingConflictBanner(onClick = onNavigateToConflictResolver) }
                }

                // 1. 面包屑路径导航
                if (uiState.breadcrumbs.isNotEmpty() && uiState.searchQuery.isBlank()) {
                    item {
                        VaultBreadcrumbBar(
                            breadcrumbs = uiState.breadcrumbs,
                            currentGroupId = uiState.currentGroupId,
                            onNavigateUp = onNavigateUp,
                            onNavigateToBreadcrumb = onNavigateToBreadcrumb
                        )
                    }
                }

                // 回收站模式警示
                if (uiState.isInsideRecycleBin) {
                    item { RecycleBinBanner() }
                }

                // 3. 当前非默认排序状态轻量提示
                if (uiState.sortOption != VaultSortOption.DEFAULT && !uiState.isInsideRecycleBin) {
                    item {
                        SortStatusHint(
                            sortOption = uiState.sortOption,
                            onResetSort = { onSortOptionSelect(VaultSortOption.DEFAULT) }
                        )
                    }
                }

                // 3.1 ISSUE-P3-30：搜索态下如实说明子库条目不参与搜索（有已挂载子库时才提示）
                if (isSearching && uiState.mountedChildDatabaseCount > 0) {
                    item(key = CHILD_DB_SEARCH_HINT_KEY) { ChildDatabaseSearchExclusionHint() }
                }

                // 4. 文件夹列表（ISSUE-P3-22：分组图标与条目侧共用同一投影缓存）
                items(uiState.currentGroups, key = { "group_${it.id}" }) { group ->
                    KeePassGroupRow(
                        group = group,
                        icon = uiState.groupIcons[group.id],
                        densitySpec = densitySpec,
                        onClick = { onGroupClick(group.id) },
                        onRename = { dialogs.groupToRename = group },
                        onChangeIcon = { dialogs.groupToChangeIcon = group },
                        onDelete = { dialogs.groupToDelete = group }
                    )
                }

                // 5. 现代化多形态条目列表 (支持长按批量选择、信用卡拟真、安全便签)
                items(uiState.entries, key = { it.id }) { entry ->
                    val isSelected = entry.id in uiState.selectedEntryIds
                    UnifiedVaultEntryRow(
                        entry = entry,
                        isRecycled = uiState.isInsideRecycleBin,
                        isBatchMode = uiState.isBatchMode,
                        isSelected = isSelected,
                        showUsername = uiState.showUsernameInList,
                        showOtp = uiState.showOtpInList,
                        showPasskeyBadge = uiState.showPasskeyBadge,
                        showUrl = uiState.showUrlInList,
                        densitySpec = densitySpec,
                        // ISSUE-P3-17：搜索结果行的完整分组路径（非搜索态 / 开关关闭时为空表）
                        groupPath = uiState.entryGroupPaths[entry.id],
                        onClick = { onEntryClick(entry.id) },
                        onLongClick = { onEntryLongClick(entry.id) },
                        onCopyPassword = { onCopyPassword(entry) },
                        onCopyUsername = { onCopyUsername(entry) },
                        onRestore = { onRestoreEntry(entry.id) },
                        onPurge = { onPurgeEntry(entry.id) },
                        // ISSUE-P3-02：状态层装配的图标投影与引用展开文案（UI 只做纯绘制）
                        decorations = uiState.decorations
                    )
                }

                // 6. ISSUE-P3-30：已解锁子库的只读分区。
                // 与根库条目**并列**渲染（各自独立的 key 命名空间），不混入上面的 items(entries)；
                // 子库行组件不接收任何写回调，故列表分区内不存在编辑 / 删除 / 复制入口。
                if (uiState.childEntrySectionVisible) {
                    uiState.childEntryGroups.forEach { childGroup ->
                        item(key = "child_header_${childGroup.mountId}") {
                            ChildDatabaseSectionHeader(group = childGroup)
                        }
                        items(childGroup.entries, key = { "child_${it.rowKey}" }) { childEntry ->
                            ChildVaultEntryRowView(
                                row = childEntry,
                                showUsername = uiState.showUsernameInList,
                                showUrl = uiState.showUrlInList,
                                densitySpec = densitySpec
                            )
                        }
                    }
                }

                // 7. 空状态（子库分区有内容时不算空）
                if (uiState.currentGroups.isEmpty() && uiState.entries.isEmpty() && !uiState.childEntrySectionVisible) {
                    item { VaultEmptyState(isSearching = uiState.searchQuery.isNotBlank()) }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    // ISSUE-P3-29：8 个对话框统一由 VaultListDialogHost 渲染（编排见该文件）
    VaultListDialogHost(
        controller = dialogs,
        uiState = uiState,
        onSortOptionSelect = onSortOptionSelect,
        onAddEntryClick = onAddEntryClick,
        onCreateGroup = onCreateGroup,
        onRenameGroup = onRenameGroup,
        onChangeGroupIcon = onChangeGroupIcon,
        onDeleteGroup = onDeleteGroup,
        onEmptyRecycleBin = onEmptyRecycleBin,
        onBatchMove = onBatchMove
    )
}

/** ISSUE-P3-30：搜索态子库排除提示行的稳定 key（不同排序 / 筛选下不重建） */
private const val CHILD_DB_SEARCH_HINT_KEY = "child_db_search_exclusion"
