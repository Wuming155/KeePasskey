package com.keepasskey.app.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 详情页顶栏与自定义图标删除入口（ISSUE-P3-02 从 EntryDetailScreen 拆出，
 * 使 Screen 单文件保持在行数阈值内）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailTopBar(
    uiState: EntryDetailUiState,
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDuplicateEntry: () -> Unit,
    onToggleAutofillBlock: () -> Unit,
    onEditClick: () -> Unit,
    onRequestDeleteCustomIcon: () -> Unit,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    var showOverflowMenu by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = { Text(stringResource(R.string.detail_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
        },
        actions = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (uiState.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.cd_favorite),
                    tint = if (uiState.isFavorite) securityColors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // TASK-16：条目克隆（只读会话隐藏）
            if (!uiState.isReadOnly) {
                IconButton(onClick = onDuplicateEntry) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_duplicate),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // TASK-44：为本应用禁用自动填充（仅条目绑定了 Android 应用时呈现；
            // 未绑定应用的条目无明确屏蔽对象，入口不出现，避免成为无意义开关）
            if (uiState.autofillBoundPackage != null) {
                IconButton(onClick = onToggleAutofillBlock) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = stringResource(R.string.cd_autofill_block),
                        tint = if (uiState.isAutofillBlockedForApp) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            // ISSUE-P3-02：自定义图标删除入口——仅「条目确实绑定了自定义图标」且非只读会话时呈现；
            // 图标是库级共享资源，删除会连带回退全部引用条目，故点击后仍须经确认弹窗
            if (!uiState.isReadOnly && uiState.entry?.customIconId != null) {
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.vault_icon_delete_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.vault_icon_delete_action),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                onRequestDeleteCustomIcon()
                            }
                        )
                    }
                }
            }
            // H4-只读整改：只读会话隐藏编辑入口
            if (!uiState.isReadOnly) {
                IconButton(onClick = onEditClick) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.cd_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * ISSUE-P3-02：自定义图标删除确认弹窗。
 * 文案明确告知「库级共享资源 → 引用条目回退默认图标且不可撤销」，防误删。
 */
@Composable
internal fun DeleteCustomIconDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_icon_delete_title)) },
        text = { Text(stringResource(R.string.vault_icon_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.btn_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
}
