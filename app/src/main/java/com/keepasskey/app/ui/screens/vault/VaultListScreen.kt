package com.keepasskey.app.ui.screens.vault

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.IconPickerDialog
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.CapsuleShape
import kotlinx.coroutines.launch

/** 下拉指示组件随手势下移的最大距离 */
private val INDICATOR_TRAVEL_Y = 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    currentTheme: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeToggle: () -> Unit = {},
    onEntryClick: (String) -> Unit,
    onAddEntryClick: (String?) -> Unit,
    onLockClick: () -> Unit = {},
    onNavigateToConflictResolver: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VaultListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
    modifier: Modifier = Modifier
) {
    var showSortDialog by remember { mutableStateOf(false) }
    var showCreateTypeDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showEmptyRecycleBinDialog by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

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
                // 批量选择操作顶栏
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.vault_batch_selected_count, uiState.selectedEntryIds.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClearBatch) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_cancel_batch))
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectAllBatch) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.cd_select_all))
                        }
                        IconButton(onClick = { showBatchMoveDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.cd_batch_move))
                        }
                        IconButton(onClick = onBatchDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_batch_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            } else {
                TopAppBar(
                    title = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (uiState.searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.vault_search_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = uiState.searchQuery,
                                        onValueChange = onSearchQueryChange,
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onSearchQueryChange("") },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = stringResource(R.string.cd_clear_search),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        if (uiState.isInsideRecycleBin) {
                            IconButton(onClick = { showEmptyRecycleBinDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = stringResource(R.string.vault_empty_recycle_bin),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.cd_sort),
                                    tint = if (uiState.sortOption != VaultSortOption.DEFAULT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onLockClick) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.cd_lock),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        floatingActionButton = {
            val showFab = !uiState.isBatchMode && !uiState.isInsideRecycleBin && (!uiState.hideFabOnScroll || !listState.isScrollInProgress)
            AnimatedVisibility(
                visible = showFab,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
            ) {
                // H4-只读整改：只读会话隐藏新建入口
                if (!uiState.isReadOnly) {
                    ExtendedFloatingActionButton(
                        onClick = { showCreateTypeDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = CapsuleShape,
                        icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create)) },
                        text = { Text(stringResource(R.string.btn_create), fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
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
            // H2 整改：存在待解决冲突会话时展示「去解决冲突」横幅——
            // 此前冲突解决页是死路由，用户收到冲突提示后无任何入口
            if (uiState.hasPendingConflict) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToConflictResolver)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.sync_feedback_conflict),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.vault_conflict_action),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 1. 面包屑路径导航
            if (uiState.breadcrumbs.isNotEmpty() && uiState.searchQuery.isBlank()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onNavigateUp) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_navigate_up),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    Text(
                                        text = stringResource(R.string.vault_root_dir),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { onNavigateToBreadcrumb(null) }
                                            .padding(horizontal = 8.dp, vertical = 8.dp)
                                    )
                                }
                                items(uiState.breadcrumbs) { grp ->
                                    Text(
                                        text = "/",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.padding(horizontal = 2.dp)
                                    )
                                    val isCurrent = grp.id == uiState.currentGroupId
                                    Text(
                                        text = grp.name,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { onNavigateToBreadcrumb(grp.id) }
                                            .padding(horizontal = 8.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 回收站模式警示
            if (uiState.isInsideRecycleBin) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.vault_recycle_bin_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // 3. 当前非默认排序状态轻量提示
            if (uiState.sortOption != VaultSortOption.DEFAULT && !uiState.isInsideRecycleBin) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.vault_current_sort, stringResource(uiState.sortOption.labelRes)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.vault_reset_sort),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onSortOptionSelect(VaultSortOption.DEFAULT) }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            // 4. 文件夹列表
            items(uiState.currentGroups, key = { "group_${it.id}" }) { group ->
                KeePassGroupRow(
                    group = group,
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
                    onClick = { onEntryClick(entry.id) },
                    onLongClick = { onEntryLongClick(entry.id) },
                    onCopyPassword = { onCopyPassword(entry) },
                    onCopyUsername = { onCopyUsername(entry) },
                    onRestore = { onRestoreEntry(entry.id) },
                    onPurge = { onPurgeEntry(entry.id) }
                )
            }

            // 6. 空状态
            if (uiState.currentGroups.isEmpty() && uiState.entries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (uiState.searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (uiState.searchQuery.isNotBlank()) stringResource(R.string.vault_empty_title)
                                else stringResource(R.string.vault_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
        }
    }

    // 排序选择对话框
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text(stringResource(R.string.cd_sort)) },
            text = {
                Column {
                    VaultSortOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onSortOptionSelect(option)
                                    showSortDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.sortOption == option,
                                onClick = {
                                    onSortOptionSelect(option)
                                    showSortDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = stringResource(option.labelRes), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    // 新建分类选择对话框
    if (showCreateTypeDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTypeDialog = false },
            title = { Text(stringResource(R.string.cd_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            showCreateTypeDialog = false
                            onAddEntryClick()
                        },
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.vault_new_entry), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                                Text(stringResource(R.string.vault_new_entry_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            showCreateTypeDialog = false
                            showCreateGroupDialog = true
                        },
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.vault_new_folder), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                                Text(stringResource(R.string.vault_new_folder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateTypeDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 批量移动文件夹选择对话框
    if (showBatchMoveDialog) {
        AlertDialog(
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text(stringResource(R.string.vault_batch_move_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            onBatchMove(null)
                            showBatchMoveDialog = false
                        }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.vault_move_to_root), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    uiState.allGroups.filter { !it.isRecycleBin }.forEach { grp ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                onBatchMove(grp.id)
                                showBatchMoveDialog = false
                            }
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(getVaultIcon(grp.iconName), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(grp.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchMoveDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
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
        var newName by remember { mutableStateOf(grp.name) }
        AlertDialog(
            onDismissRequest = { groupToRename = null },
            title = { Text(stringResource(R.string.vault_folder_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.vault_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRenameGroup(grp, newName)
                            groupToRename = null
                        }
                    },
                    shape = CapsuleShape
                ) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { groupToRename = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // 更换文件夹图标对话框
    groupToChangeIcon?.let { grp ->
        IconPickerDialog(
            selectedIconName = grp.iconName,
            onSelectIcon = { newIcon ->
                onChangeGroupIcon(grp, newIcon)
                groupToChangeIcon = null
            },
            onDismiss = { groupToChangeIcon = null }
        )
    }

    // 删除文件夹确认对话框
    groupToDelete?.let { grp ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text(stringResource(R.string.vault_folder_delete)) },
            text = { Text(stringResource(R.string.vault_folder_delete_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(grp.id)
                        groupToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // 清空回收站确认对话框
    if (showEmptyRecycleBinDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyRecycleBinDialog = false },
            title = { Text(stringResource(R.string.vault_empty_recycle_bin)) },
            text = { Text(stringResource(R.string.vault_empty_recycle_bin_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        onEmptyRecycleBin()
                        showEmptyRecycleBinDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.btn_empty)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyRecycleBinDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

/**
 * 下拉刷新指示组件：随下拉进度淡入下移，在指示区展示上次同步时间；同步进行中切换为进度环
 */
@Composable
private fun LastSyncPullIndicator(
    distanceFraction: Float,
    isSyncing: Boolean,
    lastSyncTimeText: String,
    modifier: Modifier = Modifier
) {
    if (distanceFraction <= 0f && !isSyncing) return
    val progress = distanceFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // 下拉过程中保持手势跟手，仅在指示区范围内生效
                alpha = if (isSyncing) 1f else progress
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = CapsuleShape,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            shadowElevation = if (isSyncing) 3.dp else 0.dp,
            modifier = Modifier
                .padding(top = 10.dp)
                .graphicsLayer {
                    translationY = INDICATOR_TRAVEL_Y.toPx() * progress
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.vault_sync_verifying),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = stringResource(R.string.vault_last_sync_time, lastSyncTimeText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateGroupDialog(
    parentGroupName: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("folder") }
    var showIconPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.vault_new_folder),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (parentGroupName != null) stringResource(R.string.vault_create_location_with, parentGroupName)
                    else stringResource(R.string.vault_create_location_root),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.vault_folder_name)) },
                    placeholder = { Text(stringResource(R.string.vault_folder_name_hint)) },
                    leadingIcon = {
                        IconButton(onClick = { showIconPicker = true }) {
                            Icon(
                                imageVector = getVaultIcon(selectedIcon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onConfirm(groupName.trim(), selectedIcon)
                    }
                },
                enabled = groupName.isNotBlank(),
                shape = CapsuleShape
            ) { Text(stringResource(R.string.btn_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )

    if (showIconPicker) {
        IconPickerDialog(
            selectedIconName = selectedIcon,
            onSelectIcon = {
                selectedIcon = it
                showIconPicker = false
            },
            onDismiss = { showIconPicker = false }
        )
    }
}
