package com.keepasskey.app.ui.screens.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * `OpenExistingVaultDialog` 的段落组件（ISSUE-P3-188 第 3 目：§178 自该对话框拆出）。
 *
 * 口径：**不自持状态**——各来源的快照状态由对话框本体 `remember` 持有并以下面三个
 * 状态对象传入（ISSUE-P2-399：云端表单补齐凭据后字段增多，窄参数收拢为状态对象，
 * 「状态在对话框本体」的 §178 口径不变），本文件只是把绘制搬过来。
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

/** WebDAV 来源表单（ISSUE-P2-399：补齐用户名 / 密码 / 远端路径；ISSUE-P3-400：浏览远端目录入口，无 supporting 长提示） */
@Composable
internal fun WebdavVaultSourceForm(state: WebdavVaultFormState, onBrowseRemote: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.picker_webdav_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = { state.name = it },
            label = { Text(stringResource(R.string.picker_vault_display_name)) },
            placeholder = { Text("cloud_vault.kdbx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.url,
            onValueChange = { state.url = it },
            label = { Text(stringResource(R.string.picker_webdav_url_label)) },
            placeholder = { Text(com.keepasskey.app.sync.WebDavDefaults.NUTSTORE_URL) },
            leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.username,
            onValueChange = { state.username = it },
            label = { Text(stringResource(R.string.sync_webdav_username_label)) },
            placeholder = { Text(stringResource(R.string.sync_webdav_username_placeholder_nutstore)) },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 密码输入走 SecurePasswordField——显示用 String 仅存活于组件内部，CharArray 直达状态对象
        SecurePasswordField(
            label = stringResource(R.string.sync_webdav_password_label),
            onPasswordChanged = { chars ->
                val old = state.passwordChars
                state.passwordChars = chars.copyOf()
                old.fill('0')
            },
            isPasswordVisible = state.passwordVisible,
            onToggleVisibility = { state.passwordVisible = !state.passwordVisible },
            initialPassword = null,
            initialKey = null,
            leadingIcon = Icons.Default.Lock,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.remotePath,
            onValueChange = { state.remotePath = it },
            label = { Text(stringResource(R.string.sync_webdav_path_label)) },
            placeholder = { Text(stringResource(R.string.sync_webdav_path_placeholder_nutstore)) },
            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // ISSUE-P3-400：浏览远端目录（与同步配置页同一能力；选中文件后回填远端路径）
        OutlinedButton(
            onClick = onBrowseRemote,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.sync_browse_btn))
        }
    }
}

/** S3 兼容来源表单（ISSUE-P2-399：补齐 Region / AccessKey / SecretKey / ObjectKey / path-style；ISSUE-P3-400：浏览入口 + 短标签防换行） */
@Composable
internal fun S3VaultSourceForm(state: S3VaultFormState, onBrowseRemote: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.picker_s3_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = { state.name = it },
            label = { Text(stringResource(R.string.picker_vault_display_name)) },
            placeholder = { Text("s3_vault.kdbx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.endpoint,
            onValueChange = { state.endpoint = it },
            label = { Text(stringResource(R.string.picker_s3_endpoint_label)) },
            placeholder = { Text("https://<account>.r2.cloudflarestorage.com") },
            leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ISSUE-P3-400：picker 侧用短 ASCII 标签——半宽框内长 CJK 标签会换行导致两框高度不一
            OutlinedTextField(
                value = state.bucket,
                onValueChange = { state.bucket = it },
                label = { Text("Bucket") },
                placeholder = { Text("my-vault") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = state.region,
                onValueChange = { state.region = it },
                label = { Text("Region") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        // 凭据双字段走 SecurePasswordField——显示用 String 仅存活于组件内部，CharArray 直达状态对象
        S3VaultCredentialFields(state)

        OutlinedTextField(
            value = state.objectKey,
            onValueChange = { state.objectKey = it },
            label = { Text(stringResource(R.string.sync_s3_objectkey_label)) },
            placeholder = { Text("passwords/master_vault.kdbx") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        S3VaultPathStyleSwitch(state)

        // ISSUE-P3-400：S3 浏览远端目录（选中文件后回填 Object Key）
        OutlinedButton(
            onClick = onBrowseRemote,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.sync_browse_btn))
        }
    }
}

/**
 * S3 凭据双字段（Access Key ID + Secret Access Key，均走 `SecurePasswordField`）。
 * ISSUE-P2-399：自 [S3VaultSourceForm] 下沉（口径同 §209 的 CloudSyncConfigFields 先例）。
 */
@Composable
private fun S3VaultCredentialFields(state: S3VaultFormState) {
    SecurePasswordField(
        label = "Access Key ID",
        onPasswordChanged = { chars ->
            val old = state.accessKeyChars
            state.accessKeyChars = chars.copyOf()
            old.fill('0')
        },
        isPasswordVisible = state.accessKeyVisible,
        onToggleVisibility = { state.accessKeyVisible = !state.accessKeyVisible },
        initialPassword = null,
        initialKey = null,
        leadingIcon = Icons.Default.VpnKey,
        modifier = Modifier.fillMaxWidth()
    )

    SecurePasswordField(
        label = "Secret Access Key",
        onPasswordChanged = { chars ->
            val old = state.secretKeyChars
            state.secretKeyChars = chars.copyOf()
            old.fill('0')
        },
        isPasswordVisible = state.secretKeyVisible,
        onToggleVisibility = { state.secretKeyVisible = !state.secretKeyVisible },
        initialPassword = null,
        initialKey = null,
        leadingIcon = Icons.Default.Lock,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * path-style 寻址开关行（自建 MinIO / 反向代理 / IP 直连等场景必须开启）。
 * ISSUE-P2-399：自 [S3VaultSourceForm] 下沉（口径同 §209 的 CloudSyncConfigFields 先例）。
 */
@Composable
private fun S3VaultPathStyleSwitch(state: S3VaultFormState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sync_s3_path_style_title),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.sync_s3_path_style_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = state.usePathStyle,
            onCheckedChange = { state.usePathStyle = it }
        )
    }
}

/**
 * 「打开并加载」确认按钮。
 *
 * 必填项判定与「按来源组装哪份 [OpenVaultSubmission] 提交」的 `when` 留在
 * `OpenExistingVaultDialogForm.kt`（对话框持有快照状态），本组件只负责呈现与禁用态配色
 * （ISSUE-P3-134）。
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
