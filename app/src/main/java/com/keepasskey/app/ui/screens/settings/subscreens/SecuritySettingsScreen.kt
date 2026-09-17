package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import com.keepasskey.app.security.RuntimeIntegrityPolicy
import com.keepasskey.app.security.RuntimeIntegrityReport
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
    // 本批整改：自动锁定超时 / 最长锁定时长 / 剪贴板清空倒计时三处改为**就地** `FilterChip` 选择
    // （一次点击即生效），原先各自持有的「选择弹窗」显隐状态一并移除。
    // ISSUE-P2-09 验收标准 1：关闭「禁止截屏与录屏」前的风险确认态
    var showFlagSecureRiskDialog by remember { mutableStateOf(false) }
    // ISSUE-P2-08：是否提示由策略字段 requireRiskNotice 单一裁决（不再由 UI 自行按等级推断，
    // 使「声明式判定 ⇄ 用户可见提示」真正闭环）；requireRiskNotice 为 true 时等级必为
    // ELEVATED / COMPROMISED，文案仍按等级取字符串资源
    val integrityLevel = if (RuntimeIntegrityPolicy.requiresRiskNotice(integrityReport)) {
        integrityReport?.level
    } else {
        null
    }

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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
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

                        // ISSUE-P1-22：软件级快速解锁降级的常驻声明（AC②：
                        // 用户确认降级后，设置页持续声明「不提供硬件级保护」）
                        if (uiState.biometricEnabled && uiState.quickUnlockDowngradeAcknowledged) {
                            Text(
                                text = stringResource(R.string.quick_unlock_software_key_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
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
                            // 返回语义用系统返回箭头，避免 ×（Cancel）被读成「关闭本项」
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            title = stringResource(R.string.sec_nav_back_title),
                            subtitle = stringResource(R.string.sec_nav_back_sub),
                            checked = uiState.lockWhenNavigateBack,
                            onCheckedChange = onLockWhenNavigateBackToggle
                        )

                        SecurityChoiceChips(
                            title = stringResource(R.string.sec_autolock_time_title),
                            description = stringResource(R.string.sec_autolock_dialog_desc),
                            options = AUTO_LOCK_TIMEOUT_CHOICES,
                            selectedValue = uiState.autoLockTimeoutSeconds,
                            onSelect = onAutoLockTimeoutChange
                        )

                        // ISSUE-P3-68：解锁失败重试节流（总开关 + 自定义最长锁定时长）
                        SecuritySwitchRow(
                            icon = Icons.Default.GppBad,
                            title = stringResource(R.string.sec_throttle_title),
                            subtitle = stringResource(R.string.sec_throttle_sub),
                            checked = uiState.unlockThrottleEnabled,
                            onCheckedChange = onUnlockThrottleToggle
                        )

                        if (uiState.unlockThrottleEnabled) {
                            SecurityChoiceChips(
                                title = stringResource(R.string.sec_throttle_time_title),
                                description = stringResource(R.string.sec_throttle_dialog_desc),
                                options = LOCKOUT_MAX_DURATION_CHOICES,
                                selectedValue = uiState.unlockLockoutMaxSeconds,
                                onSelect = onUnlockLockoutMaxChange
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
                            SecurityChoiceChips(
                                title = stringResource(R.string.sec_clipboard_countdown_title),
                                description = stringResource(R.string.sec_clipboard_dialog_desc),
                                options = CLIPBOARD_TIMEOUT_CHOICES,
                                selectedValue = uiState.clipboardTimeoutSeconds,
                                onSelect = onClipboardTimeoutChange
                            )
                        } else {
                            // ISSUE-P3-84 AC①：关闭自动擦除是用户可见的产品选择，但代价必须显式告知
                            // （与 FlagSecurePolicy 的风险确认语义保持一致）
                            Text(
                                text = stringResource(R.string.sec_clipboard_risk_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
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

    // 本批整改：原「自动锁定超时 / 剪贴板清空倒计时 / 最长锁定时长」三个选择弹窗已由
    // 页内就地 `SecurityChoiceChips` 取代（选中即回调），此处只保留仍需二次确认的 FLAG_SECURE 风险弹窗。

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

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "安全策略设置页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "安全策略设置页 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun SecuritySettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        SecuritySettingsScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState().copy(
                biometricEnabled = true,
                autoLockBackground = true,
                flagSecureEnabled = true,
                autoClearClipboard = true,
                unlockThrottleEnabled = true,
                autoLockTimeoutSeconds = 300,
                clipboardTimeoutSeconds = 30
            ),
            onBackClick = {},
            onBiometricToggle = {},
            onAutoLockToggle = {},
            onFlagSecureToggle = {},
            onAutoClearClipboardToggle = {}
        )
    }
}
