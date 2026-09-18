package com.keepasskey.app.ui.screens.database

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * `CreateVaultWizardDialog` 的段落组件（ISSUE-P3-188 第 3 目：§179 自该向导拆出，**纯结构性改动**）。
 *
 * 口径同 §156 / §159 / §175 / §178：窄参数、不读 `UiState`、不自持状态。
 * **主密码与确认密码的 `CharArray` 管线、`SecureDialogWindowEffect` 与离场擦除的
 * `DisposableEffect` 全部留在向导本体**——本文件不接触任何秘密字节（`AGENTS.md` §3 敏感数据铁律）。
 */

/**
 * 新建库向导中密钥文件来源的选择态（ISSUE-P3-21：替换原裸字符串 `"GENERATE"` / `"SELECT_EXISTING"`）。
 *
 * §179 自 `CreateVaultWizardDialog.kt` 移入，可见性 `private → internal`（同包段落组件需用），
 * **取值与语义未变**。
 */
internal enum class KeyFileSourceChoice {
    /** 生成全新密钥文件（由 database 模块唯一生成器产出 KeePass 2.x XML v2.0） */
    GENERATE,

    /** 使用用户从设备选取的既有密钥文件 */
    SELECT_EXISTING
}

/** 「使用密钥文件」开关行（整行可点，不止复选框） */
@Composable
internal fun KeyFileToggleRow(
    useKeyFile: Boolean,
    onUseKeyFileChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onUseKeyFileChange(!useKeyFile) }
    ) {
        Checkbox(checked = useKeyFile, onCheckedChange = onUseKeyFileChange)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = stringResource(R.string.picker_keyfile_toggle),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = stringResource(R.string.picker_keyfile_toggle_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 密钥文件来源区：「生成新密钥 / 选择已有密钥」两枚芯片，以及各自的说明文案或 SAF 选取入口。
 *
 * `onBrowse` 由本体传入（launcher 的回调要写本体的两个字段），本组件不持有任何状态。
 */
@Composable
internal fun KeyFileSourceSection(
    keyFileChoice: KeyFileSourceChoice,
    onKeyFileChoiceChange: (KeyFileSourceChoice) -> Unit,
    selectedKeyFileName: String,
    selectedKeyFilePath: String,
    onSelectedKeyFilePathChange: (String) -> Unit,
    onBrowse: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = keyFileChoice == KeyFileSourceChoice.GENERATE,
            onClick = { onKeyFileChoiceChange(KeyFileSourceChoice.GENERATE) },
            label = { Text(stringResource(R.string.picker_keyfile_generate), fontSize = 11.sp) },
            shape = CapsuleShape
        )
        FilterChip(
            selected = keyFileChoice == KeyFileSourceChoice.SELECT_EXISTING,
            onClick = { onKeyFileChoiceChange(KeyFileSourceChoice.SELECT_EXISTING) },
            label = { Text(stringResource(R.string.picker_keyfile_select_existing), fontSize = 11.sp) },
            shape = CapsuleShape
        )
    }

    if (keyFileChoice == KeyFileSourceChoice.GENERATE) {
        // ISSUE-P3-21：原文案声称「自动保存至安全存储」，实际并无自动保存——
        // 现改为如实描述「生成后强制一次性交付」
        Text(
            text = stringResource(R.string.db_picker_keyfile_generate_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onBrowse,
                shape = CapsuleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.picker_keyfile_pick), fontSize = 12.sp)
            }

            if (selectedKeyFileName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.picker_file_selected, selectedKeyFileName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            OutlinedTextField(
                value = selectedKeyFilePath,
                onValueChange = onSelectedKeyFilePathChange,
                label = { Text(stringResource(R.string.picker_keyfile_path_label)) },
                placeholder = { Text("content://... 或 /path/to/keyfile.key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 加密预设芯片：表项与文案一律取自 [CreateVaultPreset]，不得再出现裸预设字符串 */
@Composable
internal fun CreateVaultPresetChips(
    selected: CreateVaultPreset,
    onSelect: (CreateVaultPreset) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CreateVaultPreset.entries.forEach { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = { onSelect(preset) },
                label = { Text(preset.chipLabel, fontSize = 11.sp) },
                shape = CapsuleShape
            )
        }
    }
}

/**
 * 「创建」按钮。
 *
 * `onCreate` 由本体给出（要按密钥文件来源决定上送哪个 Uri），本组件只负责呈现与禁用态配色
 * （ISSUE-P3-134）。
 */
@Composable
internal fun CreateVaultConfirmButton(
    enabled: Boolean,
    onCreate: () -> Unit
) {
    Button(
        onClick = onCreate,
        enabled = enabled,
        shape = CapsuleShape,
        // ISSUE-P3-134：接入禁用态共用配色——MD3 默认 onSurface @12% 在 background
        // 画布上仅 1.29:1（见 ButtonStyles.kt），未填完表单时「创建」形同消失
        colors = disabledPrimaryButtonColors(),
        border = disabledPrimaryButtonBorder()
    ) {
        Text(stringResource(R.string.btn_create))
    }
}
