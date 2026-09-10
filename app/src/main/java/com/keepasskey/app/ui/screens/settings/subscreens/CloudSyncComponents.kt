package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 带图标的开关行条目（原 CloudSyncScreen 内部组件，随拆分提升为 internal 供区块组件复用）
 */
@Composable
internal fun SyncSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
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

/**
 * 区块 1：同步协议提供商选择卡（自 CloudSyncScreen 整体抽出）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderSelectionSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedProvider: CloudSyncProvider,
    onProviderChange: (CloudSyncProvider) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.sync_section_provider),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = stringResource(selectedProvider.labelRes),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sync_provider_field_label)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )

            // TASK-07：material3 1.5.0 起 ExposedDropdownMenu 由实验性新签名取代，
            // 经 DropdownMenu + exposedDropdownSize() 保持等价的锚定尺寸行为
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.exposedDropdownSize()
            ) {
                CloudSyncProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = stringResource(provider.labelRes),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = stringResource(provider.descRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onProviderChange(provider)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

/**
 * WebDAV 配置表单字段区（自 CloudSyncScreen 区块 2 的 WebDAV 分支整体抽出）。
 * 无状态组件：输入状态由调用方持有，保证 LazyColumn 滚动离场时输入不丢失
 */
@Composable
internal fun WebDavConfigFields(
    url: String,
    onUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    isPasswordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    passwordPrefill: CharArray?,
    onPasswordCharsChange: (CharArray) -> Unit,
    remotePath: String,
    onRemotePathChange: (String) -> Unit
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.sync_webdav_url_label)) },
        placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
        leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.sync_webdav_username_label)) },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )

    // Wave 15 整改：密码输入走 SecurePasswordField——显示用 String 仅存活于组件内部，
    // CharArray 直达本地状态；既有密码经预填通道一次性下发（不触发脏标记）
    SecurePasswordField(
        label = stringResource(R.string.sync_webdav_password_label),
        onPasswordChanged = onPasswordCharsChange,
        isPasswordVisible = isPasswordVisible,
        onToggleVisibility = onTogglePasswordVisibility,
        initialPassword = passwordPrefill,
        initialKey = passwordPrefill,
        leadingIcon = Icons.Default.Lock,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = remotePath,
        onValueChange = onRemotePathChange,
        label = { Text(stringResource(R.string.sync_webdav_path_label)) },
        placeholder = { Text("/Passkeys/keepasskey.kdbx") },
        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * S3 配置表单字段区（自 CloudSyncScreen 区块 2 的 S3 分支整体抽出）。
 * 无状态组件：输入状态由调用方持有，保证 LazyColumn 滚动离场时输入不丢失
 */
@Composable
internal fun S3ConfigFields(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    bucket: String,
    onBucketChange: (String) -> Unit,
    region: String,
    onRegionChange: (String) -> Unit,
    // ISSUE-P2-01：AccessKey ID 改走 SecurePasswordField——显示用 String 仅存活于组件内部，
    // CharArray 直达本地状态；既有 AccessKey 经预填通道一次性下发（不触发脏标记）
    isAccessKeyVisible: Boolean,
    onToggleAccessKeyVisibility: () -> Unit,
    accessKeyPrefill: CharArray?,
    onAccessKeyCharsChange: (CharArray) -> Unit,
    isSecretKeyVisible: Boolean,
    onToggleSecretKeyVisibility: () -> Unit,
    secretKeyPrefill: CharArray?,
    onSecretKeyCharsChange: (CharArray) -> Unit,
    objectKey: String,
    onObjectKeyChange: (String) -> Unit,
    usePathStyle: Boolean,
    onUsePathStyleChange: (Boolean) -> Unit
) {
    OutlinedTextField(
        value = endpoint,
        onValueChange = onEndpointChange,
        label = { Text(stringResource(R.string.sync_s3_endpoint_label)) },
        placeholder = { Text("https://<account_id>.r2.cloudflarestorage.com") },
        leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = bucket,
            onValueChange = onBucketChange,
            label = { Text(stringResource(R.string.sync_s3_bucket_label)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        )

        OutlinedTextField(
            value = region,
            onValueChange = onRegionChange,
            label = { Text(stringResource(R.string.sync_s3_region_label)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        )
    }

    // ISSUE-P2-01：AccessKey ID 输入走 SecurePasswordField（语义同 SecretKey）
    SecurePasswordField(
        label = "Access Key ID",
        onPasswordChanged = onAccessKeyCharsChange,
        isPasswordVisible = isAccessKeyVisible,
        onToggleVisibility = onToggleAccessKeyVisibility,
        initialPassword = accessKeyPrefill,
        initialKey = accessKeyPrefill,
        leadingIcon = Icons.Default.VpnKey,
        modifier = Modifier.fillMaxWidth()
    )

    // Wave 15 整改：SecretKey 输入走 SecurePasswordField（同 WebDAV 密码语义）
    SecurePasswordField(
        label = "Secret Access Key",
        onPasswordChanged = onSecretKeyCharsChange,
        isPasswordVisible = isSecretKeyVisible,
        onToggleVisibility = onToggleSecretKeyVisibility,
        initialPassword = secretKeyPrefill,
        initialKey = secretKeyPrefill,
        leadingIcon = Icons.Default.Lock,
        modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value = objectKey,
        onValueChange = onObjectKeyChange,
        label = { Text(stringResource(R.string.sync_s3_objectkey_label)) },
        placeholder = { Text("passwords/master_vault.kdbx") },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )

    // path-style 寻址开关：自建 MinIO / 反向代理 / IP 直连等场景必须开启
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
            checked = usePathStyle,
            onCheckedChange = onUsePathStyleChange
        )
    }
}

/**
 * 区块 3：同步状态卡片与手动测试（自 CloudSyncScreen 整体抽出）
 */
@Composable
internal fun SyncStatusCard(
    uiState: SettingsUiState,
    onTriggerSync: () -> Unit,
    onTestConnection: () -> Unit
) {
    val securityColors = LocalSecurityColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.sync_connection_status),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(securityColors.success.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = uiState.syncStatusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = securityColors.success
                )
            }
        }

        Text(
            text = uiState.syncLastTime,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (uiState.syncFeedbackMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.syncFeedbackMessage.resolveText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onTriggerSync,
                enabled = !uiState.isSyncing,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.sync_btn_syncing))
                } else {
                    Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.sync_cd_sync_now), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.sync_sync_now_btn))
                }
            }

            OutlinedButton(
                onClick = { onTestConnection() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.NetworkCheck, contentDescription = stringResource(R.string.sync_cd_test_connection), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.sync_test_connection_btn))
            }
        }
    }
}
