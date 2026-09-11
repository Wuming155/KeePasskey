package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * `AutofillSettingsScreen` 的设置分区卡与行组件。
 *
 * 纯结构性拆分：由 `AutofillSettingsScreen.kt` 原样搬出并放宽为 internal，
 * UI 文案、布局参数、开关语义逐字保留，不含任何行为变更。
 */

/** 设置分区标题（样式与设置页其它二级页一致）。 */
@Composable
internal fun AutofillSectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/** 1. Android 系统级凭据提供程序 (Credential Provider) 分区卡。 */
@Composable
internal fun AutofillProviderCard(
    uiState: SettingsUiState,
    onCredentialProviderToggle: (Boolean) -> Unit,
    onPasskeySupportToggle: (Boolean) -> Unit,
    onAutofillServiceToggle: (Boolean) -> Unit,
    onAutofillSessionGrantToggle: (Boolean) -> Unit
) {
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

/** 2. 体验与输入法协同 (KP2A 特性) 分区卡。 */
@Composable
internal fun AutofillUxCard(
    uiState: SettingsUiState,
    onInlineSuggestionsToggle: (Boolean) -> Unit,
    onAutoReturnFromQueryToggle: (Boolean) -> Unit,
    onAutoClearClipboardToggle: (Boolean) -> Unit
) {
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

/** 3. 两步验证与 TOTP 联动 (KP2A 特性) 分区卡。 */
@Composable
internal fun AutofillTotpCard(
    uiState: SettingsUiState,
    onAutofillCopyTotpToggle: (Boolean) -> Unit,
    onAutofillShowTotpNotificationToggle: (Boolean) -> Unit
) {
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

/** 4. 智能识别、凭证保存与兼容策略 (KP2A 特性) 分区卡（含三级黑名单入口）。 */
@Composable
internal fun AutofillCaptureCard(
    uiState: SettingsUiState,
    onOfferSaveCredentialsToggle: (Boolean) -> Unit,
    onOverrideNoAutofillToggle: (Boolean) -> Unit,
    onSkipDalVerificationToggle: (Boolean) -> Unit,
    blockedPackages: List<String>,
    saveBlockedPackages: List<String>,
    blockedFieldCount: Int,
    onOpenBlacklist: () -> Unit,
    onOpenSaveBlacklist: () -> Unit,
    onOpenFieldBlock: () -> Unit
) {
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
                onClick = onOpenBlacklist
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
                onClick = onOpenSaveBlacklist
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
                onClick = onOpenFieldBlock
            )
        }
    }
}

/**
 * 只读策略说明行：用于展示**不可关闭**的安全保证（不提供 Switch，杜绝「假开关」）。
 */
@Composable
internal fun AutofillInfoRow(
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
internal fun AutofillSwitchRow(
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
