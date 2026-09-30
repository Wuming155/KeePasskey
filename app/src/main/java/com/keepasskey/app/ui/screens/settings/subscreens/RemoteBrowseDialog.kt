package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.sync.RemoteBrowseUiState
import com.keepasskey.sync.model.RemoteListEntry

/**
 * ISSUE-P3-387：远端目录浏览对话框。
 *
 * - 失败态**不伪装空目录**（如实展示错误，仍可手动填路径）；
 * - 分页：[RemoteBrowseUiState.Listing.truncated] 时提供「加载更多」；
 * - 选中 `.kdbx` 文件 → [onSelectFile] 回填远程路径；选中文件夹 → [onNavigate] 下钻；
 * - 非根目录时提供「返回上一级」（仿安卓文件管理器）；
 * - 导航时保留旧列表 + 加载指示，避免整表闪一下清空。
 */
@Composable
fun RemoteBrowseDialog(
    state: RemoteBrowseUiState,
    onSelectFile: (RemoteListEntry) -> Unit,
    onNavigate: (RemoteListEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.sync_browse_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    RemoteBrowseUiState.Idle -> Unit
                    RemoteBrowseUiState.Loading -> {
                        Text(stringResource(R.string.sync_browse_loading))
                    }
                    is RemoteBrowseUiState.Failed -> {
                        Text(
                            text = stringResource(R.string.sync_browse_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is RemoteBrowseUiState.Listing -> {
                        RemoteBrowseListingBody(
                            state = state,
                            onSelectFile = onSelectFile,
                            onNavigate = onNavigate,
                            onNavigateUp = onNavigateUp,
                            onLoadMore = onLoadMore
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.sync_browse_close)) }
        }
    )
}

/** Listing 分支体（自 [RemoteBrowseDialog] 拆出，行数门禁）。 */
@Composable
private fun RemoteBrowseListingBody(
    state: RemoteBrowseUiState.Listing,
    onSelectFile: (RemoteListEntry) -> Unit,
    onNavigate: (RemoteListEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onLoadMore: () -> Unit
) {
    if (state.loading) {
        BrowseLoadingIndicator()
    }
    if (state.lastError != null) {
        Text(
            text = stringResource(R.string.sync_browse_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    RemoteBrowsePathHeader(
        directoryPath = state.directoryPath,
        canGoUp = state.directoryPath.isNotEmpty() && !state.loading,
        onNavigateUp = onNavigateUp
    )
    Text(
        text = stringResource(R.string.sync_browse_select_file_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    RemoteBrowseEntriesList(
        state = state,
        onSelectFile = onSelectFile,
        onNavigate = onNavigate,
        onLoadMore = onLoadMore
    )
}

@Composable
private fun BrowseLoadingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.sync_browse_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun RemoteBrowsePathHeader(
    directoryPath: String,
    canGoUp: Boolean,
    onNavigateUp: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                R.string.sync_browse_current_path,
                directoryPath.ifEmpty { "/" }
            ),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f)
        )
        if (canGoUp) {
            TextButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.sync_browse_cd_back_up),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = stringResource(R.string.sync_browse_back_up),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun RemoteBrowseEntriesList(
    state: RemoteBrowseUiState.Listing,
    onSelectFile: (RemoteListEntry) -> Unit,
    onNavigate: (RemoteListEntry) -> Unit,
    onLoadMore: () -> Unit
) {
    if (state.accumulated.isEmpty() && !state.loading) {
        Text(stringResource(R.string.sync_browse_empty))
        return
    }
    if (state.accumulated.isEmpty()) {
        Text(
            text = stringResource(R.string.sync_browse_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(state.accumulated, key = { it.path }) { entry ->
            BrowseEntryRow(
                entry = entry,
                onClick = {
                    if (entry.isDirectory) onNavigate(entry)
                    else onSelectFile(entry)
                }
            )
            HorizontalDivider()
        }
        if (state.truncated) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !state.loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.sync_browse_load_more))
                }
            }
        }
    }
}

@Composable
private fun BrowseEntryRow(entry: RemoteListEntry, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (entry.isDirectory) "📁 ${entry.name}" else "📄 ${entry.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
