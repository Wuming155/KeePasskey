package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表单自动填充与通行密钥 (Passkey) 二级设置页 (整合 KeePass2Android 完整自动填充策略)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onCredentialProviderToggle: (Boolean) -> Unit,
    onPasskeySupportToggle: (Boolean) -> Unit,
    onAutofillServiceToggle: (Boolean) -> Unit,
    onAutoClearClipboardToggle: (Boolean) -> Unit,
    // KP2A 扩展自动填充操作
    onOfferSaveCredentialsToggle: (Boolean) -> Unit = {},
    onInlineSuggestionsToggle: (Boolean) -> Unit = {},
    onAutoReturnFromQueryToggle: (Boolean) -> Unit = {},
    onAutofillCopyTotpToggle: (Boolean) -> Unit = {},
    onAutofillShowTotpNotificationToggle: (Boolean) -> Unit = {},
    onSkipDalVerificationToggle: (Boolean) -> Unit = {},
    onOverrideNoAutofillToggle: (Boolean) -> Unit = {},
    // ISSUE-P3-42：会话授权宽限（默认关闭）
    onAutofillSessionGrantToggle: (Boolean) -> Unit = {},
    // TASK-44：自动填充黑名单真实条目与增删通道（替代原无写入方的禁用计数）
    blockedPackages: List<String> = emptyList(),
    onBlockAutofillPackage: (String) -> Boolean = { false },
    onUnblockAutofillPackage: (String) -> Boolean = { false },
    // ISSUE-P3-43 ③：保存侧独立黑名单（与填充黑名单分离：只禁保存、不禁填充）
    saveBlockedPackages: List<String> = emptyList(),
    onBlockSavePackage: (String) -> Boolean = { false },
    onUnblockSavePackage: (String) -> Boolean = { false },
    // ISSUE-P3-43 ②：字段签名级屏蔽（签名不可逆，故仅可回显条数并整体清除）
    blockedFieldCount: Int = 0,
    onClearBlockedFields: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var showSaveBlacklistDialog by remember { mutableStateOf(false) }
    var showFieldBlockDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_autofill),
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
            // 1. Android 系统级凭据提供程序 (Credential Provider)
            item {
                Text(
                    text = stringResource(R.string.autofill_section_provider),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // ISSUE-P3-41：服务健康自检（实时探测系统侧与本应用侧链路状态并给出修复指引）
            item {
                AutofillHealthCard(appEnabled = uiState.autofillServiceEnabled)
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AutofillSwitchRow(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.autofill_cm_title),
                            subtitle = stringResource(R.string.autofill_cm_sub),
                            checked = uiState.credentialProviderEnabled,
                            onCheckedChange = onCredentialProviderToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Key,
                            title = stringResource(R.string.autofill_passkey_title),
                            subtitle = stringResource(R.string.autofill_passkey_sub),
                            checked = uiState.passkeySupportEnabled,
                            onCheckedChange = onPasskeySupportToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Password,
                            title = stringResource(R.string.autofill_legacy_title),
                            subtitle = stringResource(R.string.autofill_legacy_sub),
                            checked = uiState.autofillServiceEnabled,
                            onCheckedChange = onAutofillServiceToggle
                        )

                        // ISSUE-P0-02：下发前二次确认为**默认强制**安全策略（库锁定必先解锁）。
                        // 此处如实告知该保证，避免设置页出现「看似可关」的假开关。
                        AutofillInfoRow(
                            icon = Icons.Default.Lock,
                            title = stringResource(R.string.autofill_fill_confirm_title),
                            subtitle = stringResource(R.string.autofill_fill_confirm_sub)
                        )

                        // ISSUE-P3-42：会话授权宽限（默认关闭）。开启后仅在**库已解锁**且
                        // 30 秒内已对同一「包名 + 域」确认过时跳过重复弹窗；不影响解锁语义。
                        AutofillSwitchRow(
                            icon = Icons.Default.Lock,
                            title = stringResource(R.string.autofill_session_grant_title),
                            subtitle = stringResource(R.string.autofill_session_grant_sub),
                            checked = uiState.autofillSessionGrantEnabled,
                            onCheckedChange = onAutofillSessionGrantToggle
                        )
                    }
                }
            }

            // 2. 体验与输入法协同 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.autofill_section_ux),
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
                        AutofillSwitchRow(
                            icon = Icons.Default.Keyboard,
                            title = stringResource(R.string.autofill_inline_title),
                            subtitle = stringResource(R.string.autofill_inline_sub),
                            checked = uiState.inlineSuggestionsEnabled,
                            onCheckedChange = onInlineSuggestionsToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.AutoMirrored.Filled.Undo,
                            title = stringResource(R.string.autofill_auto_return_title),
                            // ISSUE-P3-03 (43b)：本应用自动填充走系统框架，确认后必然返回原应用，
                            // 无「查询界面停留」可开关，本轮未接线 → 如实标注
                            subtitle = stringResource(
                                R.string.settings_pref_reserved_suffix,
                                stringResource(R.string.autofill_auto_return_sub)
                            ),
                            checked = uiState.autoReturnFromQuery,
                            onCheckedChange = onAutoReturnFromQueryToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = stringResource(R.string.autofill_clear_clipboard_title),
                            subtitle = stringResource(R.string.autofill_clear_clipboard_sub),
                            checked = uiState.autoClearClipboard,
                            onCheckedChange = onAutoClearClipboardToggle
                        )
                    }
                }
            }

            // 3. 两步验证与 TOTP 联动 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.autofill_section_totp),
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
                        AutofillSwitchRow(
                            icon = Icons.Default.ContentPasteGo,
                            title = stringResource(R.string.autofill_copy_totp_title),
                            subtitle = stringResource(R.string.autofill_copy_totp_sub),
                            checked = uiState.autofillCopyTotp,
                            onCheckedChange = onAutofillCopyTotpToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.NotificationsActive,
                            title = stringResource(R.string.autofill_totp_notif_title),
                            // ISSUE-P3-18：通知通道与 POST_NOTIFICATIONS 已落地，本开关真实控制
                            // 自动填充确认后的验证码通知 → 移除「（预留，暂未生效）」标识
                            subtitle = stringResource(R.string.autofill_totp_notif_sub),
                            checked = uiState.autofillShowTotpNotification,
                            onCheckedChange = onAutofillShowTotpNotificationToggle
                        )
                    }
                }
            }

            // 4. 智能识别、凭证保存与兼容策略 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.autofill_section_capture),
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
                        AutofillSwitchRow(
                            icon = Icons.Default.Save,
                            title = stringResource(R.string.autofill_save_prompt_title),
                            subtitle = stringResource(R.string.autofill_save_prompt_sub),
                            checked = uiState.offerSaveCredentials,
                            onCheckedChange = onOfferSaveCredentialsToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Security,
                            title = stringResource(R.string.autofill_override_title),
                            subtitle = stringResource(R.string.autofill_override_sub),
                            checked = uiState.overrideNoAutofill,
                            onCheckedChange = onOverrideNoAutofillToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.autofill_skip_dal_title),
                            subtitle = stringResource(R.string.autofill_skip_dal_sub),
                            checked = uiState.skipDalVerification,
                            onCheckedChange = onSkipDalVerificationToggle
                        )

                        // 一级：按应用屏蔽填充（TASK-44）
                        AutofillManageEntryRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.autofill_blacklist_row_title),
                            subtitle = if (blockedPackages.isNotEmpty()) {
                                stringResource(R.string.autofill_blacklist_count, blockedPackages.size)
                            } else {
                                stringResource(R.string.autofill_blacklist_empty)
                            },
                            onClick = { showBlacklistDialog = true }
                        )

                        // 二级：按应用屏蔽**保存提示**（ISSUE-P3-43 ③，与填充屏蔽相互独立）
                        AutofillManageEntryRow(
                            icon = Icons.Default.Save,
                            title = stringResource(R.string.autofill_save_blacklist_row_title),
                            subtitle = if (saveBlockedPackages.isNotEmpty()) {
                                stringResource(R.string.autofill_save_blacklist_count, saveBlockedPackages.size)
                            } else {
                                stringResource(R.string.autofill_save_blacklist_empty)
                            },
                            onClick = { showSaveBlacklistDialog = true }
                        )

                        // 三级：字段签名级屏蔽（ISSUE-P3-43 ②，写入入口在手动选择器内）
                        AutofillManageEntryRow(
                            icon = Icons.Default.Password,
                            title = stringResource(R.string.autofill_field_block_row_title),
                            subtitle = if (blockedFieldCount > 0) {
                                stringResource(R.string.autofill_field_block_count, blockedFieldCount)
                            } else {
                                stringResource(R.string.autofill_field_block_empty)
                            },
                            onClick = { showFieldBlockDialog = true }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 填充黑名单管理对话框
    // TASK-36 整改：此前渲染两条写死的示例条目（银行/门户）并挂空 onClick 删除按钮，属假数据回显，
    // 已诚实化下架；TASK-44 补齐真实生命周期——展示持久化包名条目、支持删除与按包名新增，
    // 填充侧（AutofillService / CredentialProviderService）命中即 fail-closed 不下发。
    // ISSUE-P3-43：对话框实现搬至 AutofillBlocklistDialogs.kt 并与保存侧共用（文案参数化）。
    if (showBlacklistDialog) {
        PackageBlocklistManageDialog(
            title = stringResource(R.string.autofill_blacklist_dialog_title),
            description = stringResource(R.string.autofill_blacklist_dialog_desc),
            emptyText = stringResource(R.string.autofill_blacklist_empty),
            addHint = stringResource(R.string.autofill_blacklist_add_hint),
            blockedPackages = blockedPackages,
            onDismiss = { showBlacklistDialog = false },
            onAdd = onBlockAutofillPackage,
            onRemove = onUnblockAutofillPackage
        )
    }

    // ISSUE-P3-43 ③：保存侧黑名单管理（命中后 onSaveRequest 静默跳过，不影响填充）
    if (showSaveBlacklistDialog) {
        PackageBlocklistManageDialog(
            title = stringResource(R.string.autofill_save_blacklist_dialog_title),
            description = stringResource(R.string.autofill_save_blacklist_dialog_desc),
            emptyText = stringResource(R.string.autofill_save_blacklist_empty),
            addHint = stringResource(R.string.autofill_blacklist_add_hint),
            blockedPackages = saveBlockedPackages,
            onDismiss = { showSaveBlacklistDialog = false },
            onAdd = onBlockSavePackage,
            onRemove = onUnblockSavePackage
        )
    }

    // ISSUE-P3-43 ②：字段签名级屏蔽——签名不可逆，只提供条数回显与整体清除
    if (showFieldBlockDialog) {
        FieldBlocklistClearDialog(
            blockedFieldCount = blockedFieldCount,
            onDismiss = { showFieldBlockDialog = false },
            onConfirmClear = onClearBlockedFields
        )
    }
}

/**
 * 只读策略说明行：用于展示**不可关闭**的安全保证（不提供 Switch，杜绝「假开关」）。
 */
@Composable
private fun AutofillInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String
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
    }
}

@Composable
private fun AutofillSwitchRow(
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
