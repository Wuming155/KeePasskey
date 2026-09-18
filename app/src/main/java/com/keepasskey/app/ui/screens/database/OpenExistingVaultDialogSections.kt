package com.keepasskey.app.ui.screens.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * `OpenExistingVaultDialog` 的段落组件（ISSUE-P3-188 第 3 目：§178 自该对话框拆出，**纯结构性改动**）。
 *
 * 口径同 §156 / §159 / §175 的段落组件先例：**窄参数**（只收本段用到的值与 setter）、
 * **不读 UiState / ViewModel**、**不自持状态**——七个字段的 `remember { mutableStateOf }` 与
 * SAF launcher 全部留在对话框本体，本文件只是把绘制搬过来。
 */

/** 来源模式切换 Chip（三种来源各一枚） */
@Composable
internal fun OpenVaultSourceChips(
    selectedSource: OpenVaultSourceType,
    onSelect: (OpenVaultSourceType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OpenVaultSourceType.entries.forEach { source ->
            FilterChip(
                selected = selectedSource == source,
                onClick = { onSelect(source) },
                label = {
                    Text(
                        text = when (source) {
                            OpenVaultSourceType.LOCAL -> stringResource(R.string.picker_chip_local)
                            OpenVaultSourceType.WEBDAV -> stringResource(R.string.picker_chip_webdav)
                            OpenVaultSourceType.S3_COMPATIBLE -> stringResource(R.string.picker_chip_s3)
                        },
                        fontSize = 12.sp
                    )
                },
                shape = CapsuleShape
            )
        }
    }
}

/** 本地来源表单：文件选择入口 + 显示名 + 路径两个字段 */
@Composable
internal fun LocalVaultSourceForm(
    localName: String,
    localPath: String,
    onLocalNameChange: (String) -> Unit,
    onLocalPathChange: (String) -> Unit,
    onBrowse: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.picker_local_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LocalVaultFilePicker(localName = localName, onBrowse = onBrowse)

        OutlinedTextField(
            value = localName,
            onValueChange = onLocalNameChange,
            label = { Text(stringResource(R.string.picker_vault_id_name)) },
            placeholder = { Text("passwords.kdbx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = localPath,
            onValueChange = onLocalPathChange,
            label = { Text(stringResource(R.string.picker_local_path_label)) },
            placeholder = { Text("content://... 或 /path/to/vault.kdbx") },
            leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** SAF 浏览按钮 + 「已选文件」状态提示（未选时给占位文案） */
@Composable
internal fun LocalVaultFilePicker(localName: String, onBrowse: () -> Unit) {
    OutlinedButton(
        onClick = onBrowse,
        shape = CapsuleShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.picker_browse_file))
    }

    if (localName.isNotBlank()) {
        Text(
            text = stringResource(R.string.picker_file_selected, localName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(
            text = stringResource(R.string.picker_file_not_selected),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** WebDAV 来源表单（Wave 15 假桩清零：仅保留有消费者的展示名称与 URL） */
@Composable
internal fun WebdavVaultSourceForm(
    webdavName: String,
    webdavUrl: String,
    onWebdavNameChange: (String) -> Unit,
    onWebdavUrlChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.picker_webdav_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = webdavName,
            onValueChange = onWebdavNameChange,
            label = { Text(stringResource(R.string.picker_vault_display_name)) },
            placeholder = { Text("cloud_vault.kdbx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = webdavUrl,
            onValueChange = onWebdavUrlChange,
            label = { Text(stringResource(R.string.picker_webdav_url_label)) },
            placeholder = { Text("https://example.com/dav/passwords.kdbx") },
            leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** S3 兼容来源表单（同上，凭据统一在同步设置中配置） */
@Composable
internal fun S3VaultSourceForm(
    s3Name: String,
    s3Endpoint: String,
    s3Bucket: String,
    onS3NameChange: (String) -> Unit,
    onS3EndpointChange: (String) -> Unit,
    onS3BucketChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.picker_s3_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = s3Name,
            onValueChange = onS3NameChange,
            label = { Text(stringResource(R.string.picker_vault_display_name)) },
            placeholder = { Text("s3_vault.kdbx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = s3Endpoint,
            onValueChange = onS3EndpointChange,
            label = { Text(stringResource(R.string.picker_s3_endpoint_label)) },
            placeholder = { Text("https://<account>.r2.cloudflarestorage.com") },
            leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = s3Bucket,
            onValueChange = onS3BucketChange,
            label = { Text(stringResource(R.string.picker_s3_bucket_label)) },
            placeholder = { Text("my-vault/keepass.kdbx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 来源区顶部的说明文案 */
@Composable
internal fun OpenVaultSourceHint() {
    Text(
        text = stringResource(R.string.picker_open_vault_source_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 「打开并加载」确认按钮。
 *
 * 必填项判定与「按来源取哪两个字段提交」的 `when` **留在调用方**（对话框持有七个字段的快照状态），
 * 本组件只负责呈现与禁用态配色（ISSUE-P3-134）。
 */
@Composable
internal fun OpenVaultConfirmButton(
    enabled: Boolean,
    onConfirm: () -> Unit
) {
    Button(
        onClick = onConfirm,
        enabled = enabled,
        shape = CapsuleShape,
        // ISSUE-P3-134：接入禁用态共用配色——未选文件 / 未填必填项时「打开并加载」
        // 不得再落成 MD3 默认的 1.29:1 灰块（同 ButtonStyles.kt 的口径）
        colors = disabledPrimaryButtonColors(),
        border = disabledPrimaryButtonBorder()
    ) {
        Text(stringResource(R.string.picker_open_and_load))
    }
}

/** 对话框标题（titleMedium + Bold，与设置页其它对话框同口径） */
@Composable
internal fun OpenVaultDialogTitle() {
    Text(
        text = stringResource(R.string.picker_open_vault_title),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    )
}
