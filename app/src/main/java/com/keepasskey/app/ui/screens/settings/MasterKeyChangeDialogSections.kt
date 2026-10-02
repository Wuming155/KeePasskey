package com.keepasskey.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField

/**
 * 主密钥更改对话框的渲染面（ISSUE-P3-433 自 [MasterKeyChangeDialog] 下沉：行数分档闸门）。
 *
 * **只搬渲染面**：密码开关状态、密钥文件三态选择、已读取文件字节全部留在
 * [MasterKeyChangeDialog] 现场（擦除链「单一现场」口径，勿把状态搬回本文件），
 * 本文件所有回调原样上行；整段随 [MasterKeyChangePasswordSection.enabled] /
 * [MasterKeyFileChoiceSection.enabled] 冻结（忙 / 已提交时不可再改，与既有口径一致）。
 * 密码开关为 ISSUE-P3-433 的 KeePassDX 口径：关闭＝保持当前主密码，两框不渲染。
 * `SecureDialog` 包装留在宿主文件——`SecureDialogFlagPolicyTest` 以该调用点计数锚定（§193 清单锁）。
 */

/**
 * 「修改主密码」开关段（ISSUE-P3-433）：开关 + 开启后的新/确认两框 + 关闭时的保持提示。
 * 开关关闭这一态没有输入面——「保持不动」靠开关显式表达，不再存在「留空即保持」。
 */
@Composable
internal fun MasterKeyChangePasswordSection(
    changeEnabled: Boolean,
    passwordVisible: Boolean,
    enabled: Boolean,
    onToggleChange: (Boolean) -> Unit,
    onNewPassword: (CharArray) -> Unit,
    onConfirmPassword: (CharArray) -> Unit,
    onToggleVisibility: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.set_master_key_change_password_switch),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = changeEnabled,
                onCheckedChange = onToggleChange,
                enabled = enabled
            )
        }
        if (changeEnabled) {
            SecurePasswordField(
                label = stringResource(R.string.set_new_master_password),
                onPasswordChanged = onNewPassword,
                isPasswordVisible = passwordVisible,
                onToggleVisibility = onToggleVisibility,
                enabled = enabled
            )
            SecurePasswordField(
                label = stringResource(R.string.set_confirm_master_password),
                onPasswordChanged = onConfirmPassword,
                isPasswordVisible = passwordVisible,
                onToggleVisibility = onToggleVisibility,
                enabled = enabled
            )
        } else {
            Text(
                text = stringResource(R.string.set_master_key_password_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 密钥文件第二因子三态选择段（ISSUE-P3-428）。
 * 状态持有与意图映射留在 [MasterKeyChangeDialog] 现场（擦除链「单一现场」口径）。
 */
@Composable
internal fun MasterKeyFileChoiceSection(
    choice: KeyFileChoice,
    picked: PickedKeyFile?,
    readFailed: Boolean,
    enabled: Boolean,
    onChoiceSelect: (KeyFileChoice) -> Unit,
    onPickFile: () -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.set_master_keyfile_section),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        KeyFileChoiceRow(
            label = stringResource(R.string.set_master_keyfile_keep),
            selected = choice == KeyFileChoice.KEEP,
            enabled = enabled,
            onSelect = { onChoiceSelect(KeyFileChoice.KEEP) }
        )
        KeyFileChoiceRow(
            label = picked?.let { stringResource(R.string.set_master_keyfile_selected, it.displayName) }
                ?: stringResource(R.string.set_master_keyfile_use),
            selected = choice == KeyFileChoice.USE,
            enabled = enabled,
            onSelect = {
                onChoiceSelect(KeyFileChoice.USE)
                onPickFile()
            }
        )
        KeyFileChoiceRow(
            label = stringResource(R.string.set_master_keyfile_remove),
            selected = choice == KeyFileChoice.REMOVE,
            enabled = enabled,
            onSelect = { onChoiceSelect(KeyFileChoice.REMOVE) }
        )
        if (readFailed) {
            Text(
                text = stringResource(R.string.unlock_keyfile_read_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 三态选择段的单行（渲染面；选中态与点击上行，无本地状态） */
@Composable
private fun KeyFileChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier.clickable(enabled = enabled) { onSelect() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * ISSUE-P3-433：开关「开启」那一态（默认态预览只画关闭）——
 * 开启态差异恰在「两框出现 + 保持提示消失」，不补态就永远看不见。
 */
@androidx.compose.ui.tooling.preview.Preview(name = "密码开关开启段 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "密码开关开启段 - 深色", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MasterKeyChangePasswordSectionEnabledPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        MasterKeyChangePasswordSection(
            changeEnabled = true,
            passwordVisible = false,
            enabled = true,
            onToggleChange = {},
            onNewPassword = {},
            onConfirmPassword = {},
            onToggleVisibility = {}
        )
    }
}
