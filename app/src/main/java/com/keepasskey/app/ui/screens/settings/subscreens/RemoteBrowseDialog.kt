package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.sync.RemoteBrowsePaths
import com.keepasskey.app.sync.RemoteBrowseUiState
import com.keepasskey.sync.model.RemoteListEntry

/**
 * 远端目录浏览对话框（对齐 keepass2android 文件选择器经验）。
 *
 * - 失败态不伪装空目录；导航保留旧表 + 加载指示；
 * - **面包屑**路径段可点跳转（仿安卓资源管理器 / Kp2a getParentPath）；
 * - **默认只看**目录 + `.kdbx`（可切换显示全部）；
 * - 文件行展示大小与修改时间（PROPFIND 已带）。
 */
@Composable
fun RemoteBrowseDialog(
    state: RemoteBrowseUiState,
    onSelectFile: (RemoteListEntry) -> Unit,
    onNavigate: (RemoteListEntry) -> Unit,
    onNavigateToPath: (String) -> Unit,
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
                        var kdbxOnly by remember { mutableStateOf(true) }
                        RemoteBrowseListingBody(
                            state = state,
                            kdbxOnly = kdbxOnly,
                            onKdbxOnlyChange = { kdbxOnly = it },
                            onSelectFile = onSelectFile,
                            onNavigate = onNavigate,
                            onNavigateToPath = onNavigateToPath,
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

@Composable
private fun RemoteBrowseListingBody(
    state: RemoteBrowseUiState.Listing,
    kdbxOnly: Boolean,
    onKdbxOnlyChange: (Boolean) -> Unit,
    onSelectFile: (RemoteListEntry) -> Unit,
    onNavigate: (RemoteListEntry) -> Unit,
    onNavigateToPath: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    if (state.loading) BrowseLoadingIndicator()
    if (state.lastError != null) {
        Text(
            text = stringResource(R.string.sync_browse_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    RemoteBrowseBreadcrumb(
        directoryPath = state.directoryPath,
        onNavigateToPath = onNavigateToPath
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sync_browse_kdbx_only),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = kdbxOnly, onCheckedChange = onKdbxOnlyChange)
    }
    Text(
        text = stringResource(R.string.sync_browse_select_file_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val visible = if (kdbxOnly) {
        state.accumulated.filter { RemoteBrowsePaths.visibleUnderKdbxOnly(it.isDirectory, it.name) }
    } else {
        state.accumulated
    }
    RemoteBrowseEntriesList(
        entries = visible,
        truncated = state.truncated,
        loading = state.loading,
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

/** 可点击面包屑（Kp2a getParentPath / 安卓资源管理器风格）。 */
@Composable
private fun RemoteBrowseBreadcrumb(
    directoryPath: String,
    onNavigateToPath: (String) -> Unit
) {
    val segments = remember(directoryPath) { RemoteBrowsePaths.breadcrumbSegments(directoryPath) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, label ->
            val isLast = index == segments.lastIndex
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (isLast) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                TextButton(
                    onClick = { onNavigateToPath(RemoteBrowsePaths.breadcrumbPathAt(segments, index)) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteBrowseEntriesList(
    entries: List<RemoteListEntry>,
    truncated: Boolean,
    loading: Boolean,
    onSelectFile: (RemoteListEntry) -> Unit,
    onNavigate: (RemoteListEntry) -> Unit,
    onLoadMore: () -> Unit
) {
    if (entries.isEmpty() && !loading) {
        Text(stringResource(R.string.sync_browse_empty))
        return
    }
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.sync_browse_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(entries, key = { it.path }) { entry ->
            BrowseEntryRow(
                entry = entry,
                onClick = {
                    if (entry.isDirectory) onNavigate(entry)
                    else onSelectFile(entry)
                }
            )
            HorizontalDivider()
        }
        if (truncated) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading,
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
    val subtitle = RemoteBrowsePaths.entrySubtitle(entry.contentLength, entry.lastModifiedMillis)
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (entry.isDirectory) "📁 ${entry.name}" else "📄 ${entry.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
