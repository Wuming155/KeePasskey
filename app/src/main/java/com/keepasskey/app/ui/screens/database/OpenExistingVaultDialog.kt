package com.keepasskey.app.ui.screens.database

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 打开已有 KDBX 文件对话框 (支持本地/WebDAV/S3 自动展示并填写连接配置)
 *
 * ISSUE-P3-31 批次 B：自 `DatabasePickerScreen.kt` 拆出，纯结构性改动。
 */
@Composable
internal fun OpenExistingVaultDialog(
    onDismiss: () -> Unit,
    onConfirm: (source: OpenVaultSourceType, name: String, path: String) -> Unit
) {
    val context = LocalContext.current
    var selectedSource by remember { mutableStateOf(OpenVaultSourceType.LOCAL) }

    // 本地字段（彻底去除硬编码假路径，通过 SAF 选择器获取真实 URI 与文件名）
    var localPath by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }

    val kdbxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = queryDocumentDisplayName(context, uri)
            localName = displayName
            localPath = uri.toString()
        }
    }

    // WebDAV 字段（Wave 15 假桩清零：仅保留有消费者的展示名称与 URL，凭据统一在同步设置中配置）
    var webdavUrl by remember { mutableStateOf("") }
    var webdavName by remember { mutableStateOf("") }

    // S3 兼容字段（同上）
    var s3Endpoint by remember { mutableStateOf("") }
    var s3Bucket by remember { mutableStateOf("") }
    var s3Name by remember { mutableStateOf("") }

    val isConfirmEnabled = when (selectedSource) {
        OpenVaultSourceType.LOCAL -> localPath.isNotBlank() && localName.isNotBlank()
        OpenVaultSourceType.WEBDAV -> webdavName.isNotBlank() && webdavUrl.isNotBlank()
        OpenVaultSourceType.S3_COMPATIBLE -> s3Name.isNotBlank() && s3Endpoint.isNotBlank() && s3Bucket.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.picker_open_vault_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.picker_open_vault_source_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 来源模式切换 Chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OpenVaultSourceType.entries.forEach { source ->
                        FilterChip(
                            selected = selectedSource == source,
                            onClick = { selectedSource = source },
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

                // 根据选中的源展示对应的配置表单
                when (selectedSource) {
                    OpenVaultSourceType.LOCAL -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.picker_local_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedButton(
                                onClick = { kdbxPickerLauncher.launch(arrayOf("*/*")) },
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
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            OutlinedTextField(
                                value = localName,
                                onValueChange = { localName = it },
                                label = { Text(stringResource(R.string.picker_vault_id_name)) },
                                placeholder = { Text("passwords.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = localPath,
                                onValueChange = { localPath = it },
                                label = { Text(stringResource(R.string.picker_local_path_label)) },
                                placeholder = { Text("content://... 或 /path/to/vault.kdbx") },
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    OpenVaultSourceType.WEBDAV -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.picker_webdav_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = webdavName,
                                onValueChange = { webdavName = it },
                                label = { Text(stringResource(R.string.picker_vault_display_name)) },
                                placeholder = { Text("cloud_vault.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = webdavUrl,
                                onValueChange = { webdavUrl = it },
                                label = { Text(stringResource(R.string.picker_webdav_url_label)) },
                                placeholder = { Text("https://example.com/dav/passwords.kdbx") },
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    OpenVaultSourceType.S3_COMPATIBLE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.picker_s3_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = s3Name,
                                onValueChange = { s3Name = it },
                                label = { Text(stringResource(R.string.picker_vault_display_name)) },
                                placeholder = { Text("s3_vault.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3Endpoint,
                                onValueChange = { s3Endpoint = it },
                                label = { Text(stringResource(R.string.picker_s3_endpoint_label)) },
                                placeholder = { Text("https://<account>.r2.cloudflarestorage.com") },
                                leadingIcon = { Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = s3Bucket,
                                onValueChange = { s3Bucket = it },
                                label = { Text(stringResource(R.string.picker_s3_bucket_label)) },
                                placeholder = { Text("my-vault/keepass.kdbx") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedSource) {
                        OpenVaultSourceType.LOCAL -> onConfirm(selectedSource, localName, localPath)
                        OpenVaultSourceType.WEBDAV -> onConfirm(selectedSource, webdavName, webdavUrl)
                        OpenVaultSourceType.S3_COMPATIBLE -> onConfirm(selectedSource, s3Name, "$s3Endpoint/$s3Bucket")
                    }
                },
                enabled = isConfirmEnabled,
                shape = CapsuleShape
            ) {
                Text(stringResource(R.string.picker_open_and_load))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
