package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 区块 4：离线缓存与定时后台同步 (KP2A 特性，自 CloudSyncScreen 整体抽出)
 */
@Composable
internal fun OfflineSyncSection(
    uiState: SettingsUiState,
    onUseOfflineCacheToggle: (Boolean) -> Unit,
    onSyncOnColdStartToggle: (Boolean) -> Unit,
    onAutoSyncToggle: (Boolean) -> Unit,
    onPeriodicBackgroundSyncToggle: (Boolean) -> Unit,
    onShowIntervalDialog: () -> Unit,
    onWifiOnlyToggle: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.sync_section_offline),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SyncSwitchItem(
                icon = Icons.Default.Cached,
                title = stringResource(R.string.sync_offline_cache_title),
                subtitle = stringResource(R.string.sync_offline_cache_sub),
                checked = uiState.useOfflineCache,
                onCheckedChange = onUseOfflineCacheToggle
            )

            SyncSwitchItem(
                icon = Icons.Default.Sync,
                title = stringResource(R.string.sync_cold_start_title),
                subtitle = stringResource(R.string.sync_cold_start_sub),
                checked = uiState.syncOnColdStart,
                onCheckedChange = onSyncOnColdStartToggle
            )

            SyncSwitchItem(
                icon = Icons.Default.CloudSync,
                title = stringResource(R.string.sync_auto_sync_title),
                subtitle = stringResource(R.string.sync_auto_sync_sub),
                checked = uiState.autoSyncEnabled,
                onCheckedChange = onAutoSyncToggle
            )

            SyncSwitchItem(
                icon = Icons.Default.Schedule,
                title = stringResource(R.string.sync_periodic_title),
                subtitle = stringResource(R.string.sync_periodic_sub),
                checked = uiState.periodicBackgroundSyncEnabled,
                onCheckedChange = onPeriodicBackgroundSyncToggle
            )

            if (uiState.periodicBackgroundSyncEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowIntervalDialog() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.sync_interval_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text(
                                stringResource(R.string.sync_interval_current, uiState.periodicBackgroundSyncIntervalMinutes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                }
            }

            SyncSwitchItem(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.sync_wifi_only_title),
                subtitle = stringResource(R.string.sync_wifi_only_sub),
                checked = uiState.wifiOnlySync,
                onCheckedChange = onWifiOnlyToggle
            )
        }
    }
}

/**
 * 区块 5：文件安全、备份与冲突解决 (KP2A 特性，自 CloudSyncScreen 整体抽出)
 */
@Composable
internal fun FileSafetySection(
    uiState: SettingsUiState,
    onCreateBackupBeforeSaveToggle: (Boolean) -> Unit,
    onCheckRemoteChangesToggle: (Boolean) -> Unit,
    onShowConflictDialog: () -> Unit,
    onUseFileTransactionsToggle: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.sync_section_file_safety),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SyncSwitchItem(
                icon = Icons.Default.Backup,
                title = stringResource(R.string.sync_backup_title),
                subtitle = stringResource(R.string.sync_backup_sub),
                checked = uiState.createBackupBeforeSave,
                onCheckedChange = onCreateBackupBeforeSaveToggle
            )

            SyncSwitchItem(
                icon = Icons.Default.Security,
                title = stringResource(R.string.sync_check_remote_title),
                subtitle = stringResource(R.string.sync_check_remote_sub),
                checked = uiState.checkRemoteChangesBeforeSave,
                onCheckedChange = onCheckRemoteChangesToggle
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowConflictDialog() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.sync_conflict_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Text(
                            stringResource(R.string.sync_conflict_current, stringResource(uiState.conflictResolution.labelRes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
            }

            SyncSwitchItem(
                icon = Icons.Default.Save,
                title = stringResource(R.string.sync_file_tx_title),
                subtitle = stringResource(R.string.sync_file_tx_sub),
                checked = uiState.useFileTransactions,
                onCheckedChange = onUseFileTransactionsToggle
            )
        }
    }
}

/**
 * 区块 6：网络兼容性与传输选项 (KP2A 特性，自 CloudSyncScreen 整体抽出)
 */
@Composable
internal fun NetworkOptionsSection(
    uiState: SettingsUiState,
    onWebdavChunkedUploadToggle: (Boolean) -> Unit,
    onPreloadDatabaseEnabledToggle: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.sync_section_network),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wave 14 传输安全：同步客户端恒定 TLS-only 且证书验证完全依赖系统默认 CA 链
            // （证书固定已随 Wave 14 整体移除，明文 HTTP 由平台 Network Security Config 全局禁止）

            SyncSwitchItem(
                icon = Icons.Default.CloudQueue,
                title = stringResource(R.string.sync_chunked_title),
                subtitle = stringResource(R.string.sync_chunked_sub),
                checked = uiState.webdavChunkedUpload,
                onCheckedChange = onWebdavChunkedUploadToggle
            )

            SyncSwitchItem(
                icon = Icons.Default.Cached,
                title = stringResource(R.string.sync_preload_title),
                subtitle = stringResource(R.string.sync_preload_sub),
                checked = uiState.preloadDatabaseEnabled,
                onCheckedChange = onPreloadDatabaseEnabledToggle
            )
        }
    }
}

/**
 * 区块 7：零知识与端到端加密机制说明（自 CloudSyncScreen 整体抽出）
 */
@Composable
internal fun ZeroKnowledgeCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = "Security Note",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.sync_zeroknowledge_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = stringResource(R.string.sync_zeroknowledge_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        // ISSUE-P1-06 整改：向用户明示同步凭据封印密钥不绑定生物认证的安全取舍
        Text(
            text = stringResource(R.string.sync_credential_auth_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

/**
 * 后台定时同步间隔选择弹窗（自 CloudSyncScreen 整体抽出）
 */
@Composable
internal fun SyncIntervalDialog(
    currentIntervalMinutes: Int,
    onIntervalSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val intervalOptions = listOf(
        15 to R.string.sync_interval_15,
        30 to R.string.sync_interval_30,
        60 to R.string.sync_interval_60,
        120 to R.string.sync_interval_120,
        360 to R.string.sync_interval_360
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_interval_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                intervalOptions.forEach { (mins, label) ->
                    val isSelected = currentIntervalMinutes == mins
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntervalSelect(mins) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = { onIntervalSelect(mins) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

/**
 * 冲突解决策略选择弹窗（自 CloudSyncScreen 整体抽出）
 */
@Composable
internal fun ConflictResolutionDialog(
    currentResolution: ConflictResolution,
    onResolutionSelect: (ConflictResolution) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_conflict_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ConflictResolution.entries.forEach { res ->
                    val isSelected = currentResolution == res
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResolutionSelect(res) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = { onResolutionSelect(res) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(res.labelRes), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(stringResource(res.descRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}
