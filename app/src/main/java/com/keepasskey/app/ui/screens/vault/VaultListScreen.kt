package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 有状态主密码库列表路由（Route），遵循谷歌 MVVM 架构
 */
@Composable
fun VaultListScreen(
    currentTheme: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeToggle: () -> Unit = {},
    onEntryClick: (String) -> Unit,
    onAddEntryClick: (String?) -> Unit,
    onLockClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VaultListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearUserMessage()
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
        onEntryClick = onEntryClick,
        onCopyPassword = viewModel::copyPassword,
        onCopyUsername = viewModel::copyUsername,
        onAddEntryClick = { onAddEntryClick(uiState.currentGroupId) },
        onCreateGroup = viewModel::createGroup,
        modifier = modifier
    )
}

/**
 * 无状态主密码库展示组件
 */
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
    onCopyPassword: (UiVaultEntry) -> Unit,
    onCopyUsername: (UiVaultEntry) -> Unit,
    onAddEntryClick: () -> Unit,
    onCreateGroup: (name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortDialog by remember { mutableStateOf(false) }
    var showCreateTypeSheet by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = {
                            Text(
                                text = "搜索凭据、通行密钥或文件夹...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清空搜索",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )
                },
                actions = {
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "排序",
                            tint = if (uiState.sortOption != VaultSortOption.DEFAULT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateTypeSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CapsuleShape,
                icon = { Icon(Icons.Default.Add, contentDescription = "新建") },
                text = { Text("新建", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. 面包屑路径导航（进入子文件夹时展示）
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
                            IconButton(
                                onClick = onNavigateUp,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回上一级",
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
                                        text = "根目录",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { onNavigateToBreadcrumb(null) }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
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
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. 当前非默认排序状态轻量提示
            if (uiState.sortOption != VaultSortOption.DEFAULT) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "当前排序：${uiState.sortOption.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "恢复默认",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onSortOptionSelect(VaultSortOption.DEFAULT) }
                                .padding(4.dp)
                        )
                    }
                }
            }

            // 3. 文件夹列表（默认不显示“文件夹”标签，文件夹始终排在条目之前）
            items(uiState.currentGroups, key = { "group_${it.id}" }) { group ->
                KeePassGroupRow(
                    group = group,
                    onClick = { onGroupClick(group.id) }
                )
            }

            // 4. 凭据列表（默认不显示“凭据”标签）
            items(uiState.entries, key = { it.id }) { entry ->
                KeePassEntryRow(
                    entry = entry,
                    onClick = { onEntryClick(entry.id) },
                    onCopyPassword = { onCopyPassword(entry) },
                    onCopyUsername = { onCopyUsername(entry) }
                )
            }

            // 5. 空状态
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
                                text = if (uiState.searchQuery.isNotBlank()) "未找到匹配凭据或文件夹" else "当前文件夹为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    // 屏幕中央弹出的模态排序窗口
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = {
                Text(
                    text = "排序方式",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VaultSortOption.entries.forEach { option ->
                        val isSelected = uiState.sortOption == option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onSortOptionSelect(option)
                                    showSortDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onSortOptionSelect(option)
                                    showSortDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 底部浮层：选择新建类型（条目 vs 群组/文件夹）
    if (showCreateTypeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCreateTypeSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "新建",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (uiState.breadcrumbs.isNotEmpty())
                            "当前位置：${uiState.breadcrumbs.last().name}"
                        else
                            "当前位置：根目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 选项 1：新建条目
                Card(
                    onClick = {
                        showCreateTypeSheet = false
                        onAddEntryClick()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "条目",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "账号密码、Passkey 通行密钥、安全笔记等凭据",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 选项 2：新建群组
                Card(
                    onClick = {
                        showCreateTypeSheet = false
                        showCreateGroupDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "群组 (文件夹)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "创建分类文件夹，层级化整理与管理密码条目",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 新建群组 (文件夹) 弹窗
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            parentGroupName = uiState.breadcrumbs.lastOrNull()?.name,
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name ->
                onCreateGroup(name)
                showCreateGroupDialog = false
            }
        )
    }
}

/**
 * 新建群组 (文件夹) 弹窗
 */
@Composable
private fun CreateGroupDialog(
    parentGroupName: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "新建群组 (文件夹)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (parentGroupName != null) "位置：$parentGroupName" else "位置：根目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("群组名称") },
                placeholder = { Text("例如：工作、金融、社交、服务器...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onConfirm(groupName.trim())
                    }
                },
                enabled = groupName.isNotBlank(),
                shape = CapsuleShape
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 经典 KeePassDX / KeePass2Android 文件夹条目组件（纯净无多余标签）
 */
@Composable
private fun KeePassGroupRow(
    group: VaultGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "进入文件夹",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * 经典 KeePassDX / KeePass2Android 紧凑条目样式（纯净无多余标签）
 */
@Composable
private fun KeePassEntryRow(
    entry: UiVaultEntry,
    onClick: () -> Unit,
    onCopyPassword: () -> Unit,
    onCopyUsername: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：经典分类图标容器
            val (icon, iconTint, containerColor) = when {
                entry.isPasskey -> Triple(Icons.Default.Key, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                entry.category == EntryCategory.LOGIN -> Triple(Icons.Default.Language, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
                entry.category == EntryCategory.NOTE -> Triple(Icons.Default.Description, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
                entry.category == EntryCategory.CARD -> Triple(Icons.Default.CreditCard, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                else -> Triple(Icons.Default.Description, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 中间信息区
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.isPasskey) {
                        Spacer(modifier = Modifier.width(6.dp))
                        PasskeyBadge()
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (entry.username.isNotBlank()) entry.username else "无用户名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (entry.totpCode != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.totpCode,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TotpMiniGauge(remainingSeconds = entry.totpRemainingSeconds)
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 右侧双快捷按钮：复制用户名与复制密码（KeePassDX / KeePass2Android 标志性功能）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.username.isNotBlank()) {
                    IconButton(
                        onClick = onCopyUsername,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOutline,
                            contentDescription = "复制用户名",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onCopyPassword,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制密码",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Preview(name = "浅色模式", showBackground = true)
@Preview(name = "深色模式", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun VaultListContentPreview() {
    KeePasskeyTheme {
        VaultListContent(
            uiState = VaultListUiState(
                currentGroups = FakeVaultRepository.initialMockGroups.take(3),
                entries = FakeVaultRepository.initialMockEntries,
                totalEntriesCount = FakeVaultRepository.initialMockEntries.size
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onSearchQueryChange = {},
            onSortOptionSelect = {},
            onGroupClick = {},
            onNavigateUp = {},
            onNavigateToBreadcrumb = {},
            onEntryClick = {},
            onCopyPassword = {},
            onCopyUsername = {},
            onAddEntryClick = {},
            onCreateGroup = {}
        )
    }
}
