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
import androidx.compose.material3.CircularProgressIndicator
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
    /** ISSUE-P2-354 AC①：建库进行中——按钮内嵌进度（无字面量默认值，调用方必须显式传） */
    showProgress: Boolean,
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
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(CREATE_PROGRESS_SIZE.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(stringResource(R.string.btn_create))
        }
    }
}

/**
 * 新建库的存储位置选择（**ISSUE-P2-229**；ISSUE-P3-425 增设「云端」直建）。
 *
 * 三行单选：内部存储为默认；选「自选位置」即当场拉起系统文件选择器，挑定的文件名回显在下方；
 * 「云端」仅在已配置云同步账号时可选（`CloudSyncSnapshot.ready`），未就绪时如实呈现
 * 「读取中 / 未配置」而非假装可选。外部位置的两条降级（写回非原子、不参与 WebDAV / S3 同步）
 * **必须**同屏如实告知——不得让用户在不知情下拿到一个「看起来一样但更容易损坏、也同步不走」的库。
 */
@Composable
internal fun VaultStorageLocationSection(
    location: VaultStorageLocation,
    pickedFileName: String,
    /** ISSUE-P3-425：云同步配置快照（决定「云端」行可用性与副文案） */
    cloudSnapshot: CloudSyncSnapshot,
    onSelectInternal: () -> Unit,
    onSelectExternal: () -> Unit,
    onSelectCloud: () -> Unit
) {
    Column(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.db_create_location_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        StorageLocationOption(
            title = stringResource(R.string.db_create_location_internal),
            subtitle = stringResource(R.string.db_create_location_internal_sub),
            selected = location == VaultStorageLocation.INTERNAL,
            onClick = onSelectInternal
        )
        StorageLocationOption(
            title = stringResource(R.string.db_create_location_external),
            subtitle = stringResource(R.string.db_create_location_external_sub),
            selected = location == VaultStorageLocation.EXTERNAL,
            onClick = onSelectExternal
        )
        StorageLocationOption(
            title = stringResource(R.string.db_create_location_cloud),
            subtitle = cloudLocationSubtitle(cloudSnapshot),
            selected = location == VaultStorageLocation.CLOUD,
            enabled = cloudSnapshot.ready,
            onClick = onSelectCloud
        )
        if (location == VaultStorageLocation.EXTERNAL) {
            Text(
                text = if (pickedFileName.isBlank()) {
                    stringResource(R.string.db_create_location_external_hint)
                } else {
                    stringResource(R.string.db_create_location_picked, pickedFileName)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.db_create_location_external_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (location == VaultStorageLocation.CLOUD && cloudSnapshot.ready) {
            // 云端直建的远端目标同屏如实告知（与同步配置页同一非敏感摘要）
            Text(
                text = stringResource(R.string.db_create_location_cloud_target, cloudSnapshot.targetHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 「云端」行的副文案：读取中 / 未配置 / 可用（含 Provider 种类）三态如实区分 */
@Composable
private fun cloudLocationSubtitle(snapshot: CloudSyncSnapshot): String = when {
    !snapshot.loaded -> stringResource(R.string.db_create_location_cloud_loading)
    !snapshot.ready -> stringResource(R.string.db_create_location_cloud_unconfigured_sub)
    else -> stringResource(
        R.string.db_create_location_cloud_ready_sub,
        when (snapshot.kind) {
            OpenVaultSourceType.S3_COMPATIBLE -> stringResource(R.string.picker_chip_s3)
            else -> stringResource(R.string.picker_chip_webdav)
        }
    )
}

/** 单个位置选项（单选钮 + 标题 + 说明），整行可点；[enabled]=false 时整行置灰不可点。 */
@Composable
private fun StorageLocationOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val optionColor = if (enabled) {
        androidx.compose.ui.graphics.Color.Unspecified
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Spacer(modifier = androidx.compose.ui.Modifier.width(6.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = optionColor)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else optionColor
            )
        }
    }
}

/** ISSUE-P2-354 AC①：「创建」按钮内嵌进度圈直径（dp） */
private const val CREATE_PROGRESS_SIZE = 18

/**
 * 新建密码库的落地位置（ISSUE-P2-229；ISSUE-P3-425 增设云端直建）。
 * §391 起本枚举与消费它的存储位置区同文件（向导本体按行数分档闸门瘦身）。
 *
 * - [INTERNAL]：应用私有目录（`filesDir`）——具备原子写盘（`.tmp` + rename + `.bak`）
 *   与 WebDAV / S3 同步能力，为默认项；
 * - [EXTERNAL]：经系统文件选择器（`ACTION_CREATE_DOCUMENT`）由用户自选位置——
 *   便于自行备份与跨应用查看，但写回为非原子的 `"rwt"` 截断式写、且不参与同步
 *   （两条降级在向导内如实告知，并登记于 `docs/architecture/已知工程限界.md`）；
 * - [CLOUD]：云端直建（ISSUE-P3-425）——实际仍在 `filesDir` 建库（享受原子写盘），
 *   建成后以云 syncType 幂等重登记（与「云端打开」同形态），远端上传交由既有同步周期；
 *   仅在已配置云同步账号时可选（`CloudSyncSnapshot.ready`）。
 */
enum class VaultStorageLocation {
    INTERNAL,
    EXTERNAL,
    CLOUD
}
