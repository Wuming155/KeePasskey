package com.keepasskey.app.ui.screens.database

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R
import com.keepasskey.app.sync.WebDavDefaults

/**
 * 打开已有 KDBX 文件对话框 (支持本地/WebDAV/S3 自动展示并填写连接配置)
 *
 * ISSUE-P3-31 批次 B：自 `DatabasePickerScreen.kt` 拆出。ISSUE-P3-188 §178：表单段落与
 * 来源切换 Chip 拆至 `OpenExistingVaultDialogSections.kt`；字段快照状态与 SAF launcher
 * 留在本体。
 *
 * ISSUE-P2-399 整改：云端表单补齐全部凭据字段（WebDAV：用户名 / 密码 / 远端路径；
 * S3：Region / AccessKey / SecretKey / ObjectKey / path-style），确认即提交
 * [OpenVaultSubmission.Cloud] 走 `CloudVaultImporter` 完整链路——不再是「只登记不连接」的假桩。
 * 字段增多后，各来源快照状态收拢为下方三个状态类（**仍由对话框本体 `remember` 持有**）；
 * 敏感 `CharArray` 离开组合即擦除；提交的是副本（导入失败时表单内容保留，可直接重试）。
 *
 * ISSUE-P3-400 整改：表单内**不放**长段 supporting 提示（同步配置页保留完整文案）；
 * 「浏览远端目录」接入本对话框（复用设置页同一 `RemoteBrowseController` 单例，
 * 表单凭据优先），点选远端文件回填 remotePath / objectKey。
 */
@Composable
internal fun OpenExistingVaultDialog(
    onDismiss: () -> Unit,
    onConfirm: (submission: OpenVaultSubmission) -> Unit,
    // ISSUE-P3-400：远端目录浏览（宿主经 ViewModel 透传；默认空实现供预览）
    browseState: com.keepasskey.app.sync.RemoteBrowseUiState = com.keepasskey.app.sync.RemoteBrowseUiState.Idle,
    onBrowseWebDav: (url: String, username: String, password: CharArray, directoryPath: String, cursor: String?) -> Unit = { _, _, _, _, _ -> },
    onBrowseS3: (
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        directoryPath: String,
        usePathStyle: Boolean,
        cursor: String?
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onDismissBrowse: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedSource by remember { mutableStateOf(OpenVaultSourceType.LOCAL) }

    val local = remember { LocalVaultFormState() }
    val webdav = remember { WebdavVaultFormState() }
    val s3 = remember { S3VaultFormState() }

    val kdbxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            local.name = queryDocumentDisplayName(context, uri)
            local.path = uri.toString()
        }
    }

    // ISSUE-P3-400：浏览远端目录可见性（cloud 表单的「浏览远端目录」按钮触发）
    var showBrowseDialog by remember { mutableStateOf(false) }

    // 离开组合（确认成功关窗 / 用户取消）即擦除本地敏感驻留
    DisposableEffect(Unit) {
        onDispose {
            webdav.wipeSensitive()
            s3.wipeSensitive()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { OpenVaultDialogTitle() },
        text = {
            OpenVaultFormBody(
                selectedSource = selectedSource,
                onSelectSource = { selectedSource = it },
                local = local,
                webdav = webdav,
                s3 = s3,
                onBrowse = { kdbxPickerLauncher.launch(arrayOf("*/*")) },
                showBrowseDialog = showBrowseDialog,
                onShowBrowseDialog = { showBrowseDialog = true },
                onHideBrowseDialog = { showBrowseDialog = false },
                browseState = browseState,
                onBrowseWebDav = onBrowseWebDav,
                onBrowseS3 = onBrowseS3,
                onDismissBrowse = onDismissBrowse
            )
        },
        confirmButton = {
            OpenVaultConfirmButton(
                enabled = openVaultConfirmEnabled(selectedSource, local, webdav, s3),
                onConfirm = {
                    onConfirm(buildOpenVaultSubmission(selectedSource, local, webdav, s3))
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
            onConfirm = { }
        )
    }
}

/**
 * 本地来源表单快照状态（`remember` 留对话框本体，§178 口径）。
 * [name] 由 SAF 选定结果回填，[path] 为 `content://` uri 或文件路径。
 */
internal class LocalVaultFormState {
    var name by mutableStateOf("")
    var path by mutableStateOf("")
}

/**
 * WebDAV 来源表单快照状态（ISSUE-P2-399：URL 与远端路径分离，与同步配置页同一模型）。
 * [passwordChars] 以 `CharArray` 承载（显示用 String 仅存活于 SecurePasswordField 组件内部）；
 * 默认预填坚果云端点，避免重复输入示例 URL。
 */
internal class WebdavVaultFormState {
    var name by mutableStateOf("")
    var url by mutableStateOf(WebDavDefaults.NUTSTORE_URL)
    var username by mutableStateOf("")
    var passwordChars by mutableStateOf(CharArray(0))
    var passwordVisible by mutableStateOf(false)
    var remotePath by mutableStateOf(WebDavDefaults.DEFAULT_REMOTE_PATH)

    /** 离开组合 / 关窗时擦除敏感驻留（借用语义收口） */
    fun wipeSensitive() {
        passwordChars.fill('0')
    }
}

/** S3 兼容来源表单快照状态（ISSUE-P2-399：补齐 Region / AccessKey / SecretKey / ObjectKey / path-style） */
internal class S3VaultFormState {
    var name by mutableStateOf("")
    var endpoint by mutableStateOf("")
    var bucket by mutableStateOf("")
    var region by mutableStateOf("auto")
    var accessKeyChars by mutableStateOf(CharArray(0))
    var accessKeyVisible by mutableStateOf(false)
    var secretKeyChars by mutableStateOf(CharArray(0))
    var secretKeyVisible by mutableStateOf(false)
    var objectKey by mutableStateOf("keepasskey.kdbx")
    var usePathStyle by mutableStateOf(false)

    /** 离开组合 / 关窗时擦除敏感驻留（借用语义收口） */
    fun wipeSensitive() {
        accessKeyChars.fill('0')
        secretKeyChars.fill('0')
    }
}
