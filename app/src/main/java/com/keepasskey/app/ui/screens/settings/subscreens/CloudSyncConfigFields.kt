package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
