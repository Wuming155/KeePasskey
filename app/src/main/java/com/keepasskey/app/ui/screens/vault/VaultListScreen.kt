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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showSortDialog by remember { mutableStateOf(false) }
    var showCreateTypeDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showEmptyRecycleBinDialog by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    // ISSUE-P3-17：列表密度规格（偏好 → 行高 / 内边距 / 字号的唯一映射点）
    val densitySpec = remember(uiState.listDensity) {
        ListDensityPresenter.specOf(uiState.listDensity)
    }

    // 文件夹上下文操作状态
    var groupToRename by remember { mutableStateOf<VaultGroup?>(null) }
    var groupToChangeIcon by remember { mutableStateOf<VaultGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<VaultGroup?>(null) }

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
                    onBatchMoveClick = { showBatchMoveDialog = true },
                    onBatchDelete = onBatchDelete
                )
            } else {
                VaultListSearchTopBar(
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    isInsideRecycleBin = uiState.isInsideRecycleBin,
                    sortOption = uiState.sortOption,
                    onSortClick = { showSortDialog = true },
                    onLockClick = onLockClick,
                    onEmptyRecycleBinClick = { showEmptyRecycleBinDialog = true },
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
                onClick = { showCreateTypeDialog = true }
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

                // 4. 文件夹列表（ISSUE-P3-22：分组图标与条目侧共用同一投影缓存）
                items(uiState.currentGroups, key = { "group_${it.id}" }) { group ->
                    KeePassGroupRow(
                        group = group,
                        icon = uiState.groupIcons[group.id],
                        densitySpec = densitySpec,
                        onClick = { onGroupClick(group.id) },
                        onRename = { groupToRename = group },
                        onChangeIcon = { groupToChangeIcon = group },
                        onDelete = { groupToDelete = group }
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

                // 6. 空状态
                if (uiState.currentGroups.isEmpty() && uiState.entries.isEmpty()) {
                    item { VaultEmptyState(isSearching = uiState.searchQuery.isNotBlank()) }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    // 排序选择对话框
    if (showSortDialog) {
        VaultSortDialog(
            currentOption = uiState.sortOption,
            onSelect = { option ->
                onSortOptionSelect(option)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }

    // 新建分类选择对话框
    if (showCreateTypeDialog) {
        VaultCreateTypeDialog(
            onDismiss = { showCreateTypeDialog = false },
            onAddEntry = {
                showCreateTypeDialog = false
                onAddEntryClick()
            },
            onCreateFolder = {
                showCreateTypeDialog = false
                showCreateGroupDialog = true
            }
        )
    }

    // 批量移动文件夹选择对话框
    if (showBatchMoveDialog) {
        VaultBatchMoveDialog(
            allGroups = uiState.allGroups,
            onDismiss = { showBatchMoveDialog = false },
            onMove = { targetGroupId ->
                onBatchMove(targetGroupId)
                showBatchMoveDialog = false
            }
        )
    }

    // 新建群组对话框
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            parentGroupName = uiState.breadcrumbs.lastOrNull()?.name,
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name, icon ->
                onCreateGroup(name, icon)
                showCreateGroupDialog = false
            }
        )
    }

    // 重命名文件夹对话框
    groupToRename?.let { grp ->
        VaultRenameGroupDialog(
            group = grp,
            onDismiss = { groupToRename = null },
            onConfirm = { newName ->
                onRenameGroup(grp, newName)
                groupToRename = null
            }
        )
    }

    // 更换文件夹图标对话框
    groupToChangeIcon?.let { grp ->
        VaultChangeGroupIconDialog(
            group = grp,
            onSelectIcon = { newIcon ->
                onChangeGroupIcon(grp, newIcon)
                groupToChangeIcon = null
            },
            onDismiss = { groupToChangeIcon = null }
        )
    }

    // 删除文件夹确认对话框
    groupToDelete?.let { grp ->
        VaultDeleteGroupDialog(
            group = grp,
            onDismiss = { groupToDelete = null },
            onConfirm = {
                onDeleteGroup(grp.id)
                groupToDelete = null
            }
        )
    }

    // 清空回收站确认对话框
    if (showEmptyRecycleBinDialog) {
        VaultEmptyRecycleBinDialog(
            onDismiss = { showEmptyRecycleBinDialog = false },
            onConfirm = {
                onEmptyRecycleBin()
                showEmptyRecycleBinDialog = false
            }
        )
    }
}
