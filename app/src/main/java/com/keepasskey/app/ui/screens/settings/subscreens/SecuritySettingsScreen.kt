package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库安全策略与锁定规则二级设置页 (聚焦生物识别指纹验证与严苛锁定策略)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onAutoLockToggle: (Boolean) -> Unit,
    onFlagSecureToggle: (Boolean) -> Unit,
    onAutoClearClipboardToggle: (Boolean) -> Unit,
    onAutoLockTimeoutChange: (Int) -> Unit = {},
    onClipboardTimeoutChange: (Int) -> Unit = {},
    onLockWhenScreenOffToggle: (Boolean) -> Unit = {},
    onLockWhenNavigateBackToggle: (Boolean) -> Unit = {},
    onClearPasswordOnLeaveToggle: (Boolean) -> Unit = {},
    onRememberRecentFilesToggle: (Boolean) -> Unit = {},
    onRememberKeyFileLocationToggle: (Boolean) -> Unit = {},
    onShowKillAppOptionToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    val autoLockLabel = stringResource(autoLockTimeoutLabelRes(uiState.autoLockTimeoutSeconds))
    val clipboardLabel = stringResource(clipboardTimeoutLabelRes(uiState.clipboardTimeoutSeconds))

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.sec_screen_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 生物识别验证（指纹识别解密）
            item {
                Text(
                    text = stringResource(R.string.sec_section_biometric),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.Fingerprint,
                            title = stringResource(R.string.sec_biometric_title),
                            subtitle = stringResource(R.string.sec_biometric_sub),
                            checked = uiState.biometricEnabled,
                            onCheckedChange = onBiometricToggle
                        )
                    }
                }
            }

            // 2. 自动锁定规则与触发条件
            item {
                Text(
                    text = stringResource(R.string.sec_section_autolock),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.ScreenLockPortrait,
                            title = stringResource(R.string.sec_bg_lock_title),
                            subtitle = stringResource(R.string.sec_bg_lock_sub),
                            checked = uiState.autoLockBackground,
                            onCheckedChange = onAutoLockToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.LockClock,
                            title = stringResource(R.string.sec_screen_off_title),
                            subtitle = stringResource(R.string.sec_screen_off_sub),
                            checked = uiState.lockWhenScreenOff,
                            onCheckedChange = onLockWhenScreenOffToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.Cancel,
                            title = stringResource(R.string.sec_nav_back_title),
                            subtitle = stringResource(R.string.sec_nav_back_sub),
                            checked = uiState.lockWhenNavigateBack,
                            onCheckedChange = onLockWhenNavigateBackToggle
                        )

                        SecurityClickableRow(
                            icon = Icons.Default.LockClock,
                            title = stringResource(R.string.sec_autolock_time_title),
                            subtitle = stringResource(R.string.sec_autolock_time_current, autoLockLabel),
                            onClick = { showAutoLockDialog = true }
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.VisibilityOff,
                            title = stringResource(R.string.sec_clear_input_title),
                            subtitle = stringResource(R.string.sec_clear_input_sub),
                            checked = uiState.clearPasswordOnLeave,
                            onCheckedChange = onClearPasswordOnLeaveToggle
                        )
                    }
                }
            }

            // 3. 系统环境防泄露保护 (FLAG_SECURE / 剪贴板)
            item {
                Text(
                    text = stringResource(R.string.sec_section_leak),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.Security,
                            title = stringResource(R.string.sec_flag_secure_title),
                            subtitle = stringResource(R.string.sec_flag_secure_sub),
                            checked = uiState.flagSecureEnabled,
                            onCheckedChange = onFlagSecureToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = stringResource(R.string.sec_clipboard_title),
                            subtitle = stringResource(R.string.sec_clipboard_sub),
                            checked = uiState.autoClearClipboard,
                            onCheckedChange = onAutoClearClipboardToggle
                        )

                        if (uiState.autoClearClipboard) {
                            SecurityClickableRow(
                                icon = Icons.Default.ContentPasteGo,
                                title = stringResource(R.string.sec_clipboard_countdown_title),
                                subtitle = stringResource(R.string.sec_clipboard_countdown_current, clipboardLabel),
                                onClick = { showClipboardDialog = true }
                            )
                        }
                    }
                }
            }

            // 4. 凭据记忆与进程控制
            item {
                Text(
                    text = stringResource(R.string.sec_section_memory),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SecuritySwitchRow(
                            icon = Icons.Default.History,
                            title = stringResource(R.string.sec_recent_files_title),
                            subtitle = stringResource(R.string.sec_recent_files_sub),
                            checked = uiState.rememberRecentFiles,
                            onCheckedChange = onRememberRecentFilesToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.Bookmark,
                            title = stringResource(R.string.sec_keyfile_title),
                            subtitle = stringResource(R.string.sec_keyfile_sub),
                            checked = uiState.rememberKeyFileLocation,
                            onCheckedChange = onRememberKeyFileLocationToggle
                        )

                        SecuritySwitchRow(
                            icon = Icons.Default.PowerSettingsNew,
                            title = stringResource(R.string.sec_kill_app_title),
                            subtitle = stringResource(R.string.sec_kill_app_sub),
                            checked = uiState.showKillAppOption,
                            onCheckedChange = onShowKillAppOptionToggle
                        )
                    }
                }
            }

            // 5. 架构说明
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Security Engine",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sec_arch_title),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.sec_arch_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 自动锁定超时选择弹窗
    if (showAutoLockDialog) {
        val lockOptions = listOf(
            0 to R.string.sec_lock_now,
            30 to R.string.sec_30s,
            60 to R.string.sec_1min,
            300 to R.string.sec_5min,
            900 to R.string.sec_15min,
            -1 to R.string.sec_lock_never
        )
        AlertDialog(
            onDismissRequest = { showAutoLockDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.sec_autolock_time_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.sec_autolock_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    lockOptions.forEach { (seconds, label) ->
                        val isSelected = uiState.autoLockTimeoutSeconds == seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAutoLockTimeoutChange(seconds)
                                    showAutoLockDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onAutoLockTimeoutChange(seconds)
                                    showAutoLockDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoLockDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 剪贴板清空倒计时弹窗
    if (showClipboardDialog) {
        val clipOptions = listOf(
            15 to R.string.sec_clip_15s,
            30 to R.string.sec_clip_30s,
            60 to R.string.sec_1min,
            120 to R.string.sec_2min,
            -1 to R.string.sec_clip_no_clear
        )
        AlertDialog(
            onDismissRequest = { showClipboardDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.sec_clipboard_countdown_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.sec_clipboard_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    clipOptions.forEach { (seconds, label) ->
                        val isSelected = uiState.clipboardTimeoutSeconds == seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onClipboardTimeoutChange(seconds)
                                    showClipboardDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onClipboardTimeoutChange(seconds)
                                    showClipboardDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showClipboardDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * 将自动锁定秒数映射为对应的字符串资源 (0 = 立即锁定, -1 = 永不)
 */
@StringRes
private fun autoLockTimeoutLabelRes(seconds: Int): Int = when (seconds) {
    0 -> R.string.sec_lock_now
    30 -> R.string.sec_30s
    60 -> R.string.sec_1min
    120 -> R.string.sec_2min
    300 -> R.string.sec_5min
    900 -> R.string.sec_15min
    else -> R.string.sec_never
}

/**
 * 将剪贴板清空秒数映射为对应的字符串资源 (-1 = 不清空)
 */
@StringRes
private fun clipboardTimeoutLabelRes(seconds: Int): Int = when (seconds) {
    15 -> R.string.sec_clip_15s
    30 -> R.string.sec_30s
    60 -> R.string.sec_1min
    120 -> R.string.sec_2min
    else -> R.string.sec_clip_never
}

@Composable
private fun SecuritySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun SecurityClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}
