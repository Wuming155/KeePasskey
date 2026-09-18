package com.keepasskey.app.ui.screens.database

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 打开已有 KDBX 文件对话框 (支持本地/WebDAV/S3 自动展示并填写连接配置)
 *
 * ISSUE-P3-31 批次 B：自 `DatabasePickerScreen.kt` 拆出，纯结构性改动。
 * ISSUE-P3-188 §178：三种来源的表单段落与来源切换 Chip 再拆至同包
 * `OpenExistingVaultDialogSections.kt`；**七个字段的 `remember { mutableStateOf }` 与 SAF launcher 仍在本体**。
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
        title = { OpenVaultDialogTitle() },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OpenVaultSourceHint()

                // 来源模式切换 Chip
                OpenVaultSourceChips(
                    selectedSource = selectedSource,
                    onSelect = { selectedSource = it }
                )

                // 根据选中的源展示对应的配置表单
                when (selectedSource) {
                    OpenVaultSourceType.LOCAL -> LocalVaultSourceForm(
                        localName = localName,
                        localPath = localPath,
                        onLocalNameChange = { localName = it },
                        onLocalPathChange = { localPath = it },
                        onBrowse = { kdbxPickerLauncher.launch(arrayOf("*/*")) }
                    )

                    OpenVaultSourceType.WEBDAV -> WebdavVaultSourceForm(
                        webdavName = webdavName,
                        webdavUrl = webdavUrl,
                        onWebdavNameChange = { webdavName = it },
                        onWebdavUrlChange = { webdavUrl = it }
                    )

                    OpenVaultSourceType.S3_COMPATIBLE -> S3VaultSourceForm(
                        s3Name = s3Name,
                        s3Endpoint = s3Endpoint,
                        s3Bucket = s3Bucket,
                        onS3NameChange = { s3Name = it },
                        onS3EndpointChange = { s3Endpoint = it },
                        onS3BucketChange = { s3Bucket = it }
                    )
                }
            }
        },
        confirmButton = {
            OpenVaultConfirmButton(
                enabled = isConfirmEnabled,
                onConfirm = {
                    when (selectedSource) {
                        OpenVaultSourceType.LOCAL -> onConfirm(selectedSource, localName, localPath)
                        OpenVaultSourceType.WEBDAV -> onConfirm(selectedSource, webdavName, webdavUrl)
                        OpenVaultSourceType.S3_COMPATIBLE -> onConfirm(selectedSource, s3Name, "$s3Endpoint/$s3Bucket")
                    }
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview(name = "打开已有密码库 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "打开已有密码库 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun OpenExistingVaultDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        OpenExistingVaultDialog(
            onDismiss = {},
            onConfirm = { _, _, _ -> }
        )
    }
}
