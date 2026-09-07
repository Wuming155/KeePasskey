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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

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
    modifier: Modifier = Modifier
) {
    var showBlacklistDialog by remember { mutableStateOf(false) }

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
                            subtitle = stringResource(R.string.autofill_auto_return_sub),
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showBlacklistDialog = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.autofill_blacklist_row_title),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (uiState.disabledAutofillQueriesCount > 0) {
                                            stringResource(R.string.autofill_blacklist_count, uiState.disabledAutofillQueriesCount)
                                        } else {
                                            stringResource(R.string.autofill_blacklist_empty)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 黑名单管理对话框
    if (showBlacklistDialog) {
        AlertDialog(
            onDismissRequest = { showBlacklistDialog = false },
            title = { Text(stringResource(R.string.autofill_blacklist_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.autofill_blacklist_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        stringResource(R.string.autofill_blacklist_item_bank),
                        stringResource(R.string.autofill_blacklist_item_portal)
                    ).forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item, style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.edit_passkey_unbind), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlacklistDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
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
