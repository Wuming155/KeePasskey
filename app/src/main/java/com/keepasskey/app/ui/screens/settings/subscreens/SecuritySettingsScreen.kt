package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.security.RuntimeIntegrityReport
import com.keepasskey.app.security.RuntimeRiskLevel
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
    onUnlockThrottleToggle: (Boolean) -> Unit = {},
    onUnlockLockoutMaxChange: (Int) -> Unit = {},
    onLockWhenScreenOffToggle: (Boolean) -> Unit = {},
    onLockWhenNavigateBackToggle: (Boolean) -> Unit = {},
    onClearPasswordOnLeaveToggle: (Boolean) -> Unit = {},
    onRememberRecentFilesToggle: (Boolean) -> Unit = {},
    onRememberKeyFileLocationToggle: (Boolean) -> Unit = {},
    onShowKillAppOptionToggle: (Boolean) -> Unit = {},
    // ISSUE-P2-08 (ZT-13)：运行环境完整性快照（宿主注入；缺省或 TRUSTED/UNDETERMINED 时不渲染提示）
    integrityReport: RuntimeIntegrityReport? = null,
    modifier: Modifier = Modifier
) {
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    // ISSUE-P3-68：解锁失败重试最长锁定时长选择弹窗
    var showLockoutMaxDialog by remember { mutableStateOf(false) }
    // ISSUE-P2-09 验收标准 1：关闭「禁止截屏与录屏」前的风险确认态
    var showFlagSecureRiskDialog by remember { mutableStateOf(false) }
    val autoLockLabel = stringResource(autoLockTimeoutLabelRes(uiState.autoLockTimeoutSeconds))
    val clipboardLabel = stringResource(clipboardTimeoutLabelRes(uiState.clipboardTimeoutSeconds))
    // ISSUE-P3-68：最长锁定时长以「分钟」格式化呈现（任意自定义值无需枚举标签资源）
    val lockoutMinutes = uiState.unlockLockoutMaxSeconds / 60
    // ISSUE-P2-08：仅「可疑 / 已妥协」两档需要明确风险提示（未判定不等于已判定为风险）
    val integrityLevel = integrityReport?.level
        ?.takeIf { it == RuntimeRiskLevel.ELEVATED || it == RuntimeRiskLevel.COMPROMISED }

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

                        // ISSUE-P3-68：解锁失败重试节流（总开关 + 自定义最长锁定时长）
                        SecuritySwitchRow(
                            icon = Icons.Default.Replay,
                            title = stringResource(R.string.sec_throttle_title),
                            subtitle = stringResource(R.string.sec_throttle_sub),
                            checked = uiState.unlockThrottleEnabled,
                            onCheckedChange = onUnlockThrottleToggle
                        )

                        if (uiState.unlockThrottleEnabled) {
                            SecurityClickableRow(
                                icon = Icons.Default.Replay,
                                title = stringResource(R.string.sec_throttle_time_title),
                                subtitle = stringResource(
                                    R.string.sec_throttle_time_current,
                                    lockoutMinutes
                                ),
                                onClick = { showLockoutMaxDialog = true }
                            )
                        }

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

            // ISSUE-P2-08 (ZT-13)：运行环境完整性风险提示（不静默放行；仅风险档渲染）
            integrityLevel?.let { level ->
                item {
                    IntegrityRiskCard(level = level)
                }
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
                            onCheckedChange = { enabled ->
                                // ISSUE-P2-09 验收标准 1：FLAG_SECURE 属强制项，关闭前必须风险确认
                                if (enabled) {
                                    onFlagSecureToggle(true)
                                } else {
                                    showFlagSecureRiskDialog = true
                                }
                            }
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
                            // ISSUE-P3-17：已真实消费——KeePasskeyApp 经 AppTerminationPolicy 判定，
                            // 在库列表顶栏溢出菜单暴露「彻底退出应用」入口并真实终止进程 → 移除标识
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
        AutoLockTimeoutDialog(
            selectedSeconds = uiState.autoLockTimeoutSeconds,
            onSelect = onAutoLockTimeoutChange,
            onDismiss = { showAutoLockDialog = false }
        )
    }

    // 剪贴板清空倒计时弹窗
    if (showClipboardDialog) {
        ClipboardTimeoutDialog(
            selectedSeconds = uiState.clipboardTimeoutSeconds,
            onSelect = onClipboardTimeoutChange,
            onDismiss = { showClipboardDialog = false }
        )
    }

    // ISSUE-P3-68：解锁失败重试最长锁定时长弹窗（关闭节流时不渲染入口，自然不弹）
    if (showLockoutMaxDialog) {
        LockoutMaxDurationDialog(
            selectedSeconds = uiState.unlockLockoutMaxSeconds,
            onSelect = onUnlockLockoutMaxChange,
            onDismiss = { showLockoutMaxDialog = false }
        )
    }

    // ISSUE-P2-09 验收标准 1：关闭「禁止截屏与录屏」的风险确认（确认后才真正回调关闭）
    if (showFlagSecureRiskDialog) {
        FlagSecureRiskDialog(
            onConfirm = {
                showFlagSecureRiskDialog = false
                onFlagSecureToggle(false)
            },
            onDismiss = { showFlagSecureRiskDialog = false }
        )
    }
}
