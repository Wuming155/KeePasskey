package com.keepasskey.app.ui.screens.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.sync.CloudVaultImportRequest
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.app.ui.screens.settings.subscreens.RemoteBrowseSection

/**
 * `OpenExistingVaultDialog` 的表单体与提交组装（ISSUE-P2-399 自对话框本体下沉，
 * 沿用 §178「快照状态留对话框本体、此处只消费状态对象」口径；字段增多后独立成文件）。
 *
 * 借用语义：提交时凭据 `CharArray` 传**副本**（[buildOpenVaultSubmission] 内 copyOf），
 * 对话框本体留存原数组供导入失败时重试，离开组合时统一擦除。
 */

/** 表单体：来源说明 + 来源切换 Chip + 按来源渲染对应表单段落 + 远端目录浏览（段落组件见 Sections 文件） */
@Composable
internal fun OpenVaultFormBody(
    selectedSource: OpenVaultSourceType,
    onSelectSource: (OpenVaultSourceType) -> Unit,
    local: LocalVaultFormState,
    webdav: WebdavVaultFormState,
    s3: S3VaultFormState,
    onBrowse: () -> Unit,
    showBrowseDialog: Boolean,
    onShowBrowseDialog: () -> Unit,
    onHideBrowseDialog: () -> Unit,
    browseState: com.keepasskey.app.sync.RemoteBrowseUiState,
    onBrowseWebDav: (String, String, CharArray, String, String?) -> Unit,
    onBrowseS3: (String, String, String, CharArray, CharArray, String, Boolean, String?) -> Unit,
    onDismissBrowse: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OpenVaultSourceHint()

        OpenVaultSourceChips(
            selectedSource = selectedSource,
            onSelect = onSelectSource
        )

        when (selectedSource) {
            OpenVaultSourceType.LOCAL -> LocalVaultSourceForm(
                localName = local.name,
                localPath = local.path,
                onLocalNameChange = { local.name = it },
                onLocalPathChange = { local.path = it },
                onBrowse = onBrowse
            )

            OpenVaultSourceType.WEBDAV -> WebdavVaultSourceForm(
                state = webdav,
                // 与 CloudSyncScreen 同口径：开弹窗同时用表单凭据发起首次列举（浏览其父目录）
                onBrowseRemote = {
                    onShowBrowseDialog()
                    onBrowseWebDav(
                        webdav.url, webdav.username, webdav.passwordChars.copyOf(),
                        com.keepasskey.app.sync.parentDirectoryPath(webdav.remotePath), null
                    )
                }
            )

            OpenVaultSourceType.S3_COMPATIBLE -> S3VaultSourceForm(
                state = s3,
                onBrowseRemote = {
                    onShowBrowseDialog()
                    onBrowseS3(
                        s3.endpoint, s3.bucket, s3.region,
                        s3.accessKeyChars.copyOf(), s3.secretKeyChars.copyOf(),
                        com.keepasskey.app.sync.parentDirectoryPath(s3.objectKey), s3.usePathStyle, null
                    )
                }
            )
        }

        // ISSUE-P3-400：远端目录浏览（ISSUE-P3-387 装配段复用；表单凭据优先，选中文件回填路径）
        RemoteBrowseSection(
            visible = showBrowseDialog,
            provider = when (selectedSource) {
                OpenVaultSourceType.S3_COMPATIBLE -> CloudSyncProvider.S3_COMPATIBLE
                else -> CloudSyncProvider.WEBDAV
            },
            browseState = browseState,
            webdavUrl = webdav.url,
            webdavUsername = webdav.username,
            webdavPasswordChars = webdav.passwordChars,
            webdavRemotePath = webdav.remotePath,
            s3Endpoint = s3.endpoint,
            s3Bucket = s3.bucket,
            s3Region = s3.region,
            s3AccessKeyChars = s3.accessKeyChars,
            s3SecretKeyChars = s3.secretKeyChars,
            s3ObjectKey = s3.objectKey,
            s3UsePathStyle = s3.usePathStyle,
            onBrowseWebDav = onBrowseWebDav,
            onBrowseS3 = onBrowseS3,
            onSelectFile = { entry ->
                if (selectedSource == OpenVaultSourceType.S3_COMPATIBLE) {
                    s3.applyObjectKey(entry.path)
                } else {
                    webdav.applyRemotePath(entry.path)
                }
                onHideBrowseDialog()
                onDismissBrowse()
            },
            onDismissBrowse = onDismissBrowse
        )
    }
}

/** 「打开并加载」的必填项判定：本地要选定文件，云端要完整连接凭据（含展示名） */
internal fun openVaultConfirmEnabled(
    source: OpenVaultSourceType,
    local: LocalVaultFormState,
    webdav: WebdavVaultFormState,
    s3: S3VaultFormState
): Boolean = when (source) {
    OpenVaultSourceType.LOCAL -> local.path.isNotBlank() && local.name.isNotBlank()
    OpenVaultSourceType.WEBDAV -> webdav.name.isNotBlank() && webdav.url.isNotBlank() &&
        webdav.username.isNotBlank() && webdav.remotePath.isNotBlank() && webdav.passwordChars.isNotEmpty()
    OpenVaultSourceType.S3_COMPATIBLE -> s3.name.isNotBlank() && s3.endpoint.isNotBlank() &&
        s3.bucket.isNotBlank() && s3.region.isNotBlank() && s3.objectKey.isNotBlank() &&
        s3.accessKeyChars.isNotEmpty() && s3.secretKeyChars.isNotEmpty()
}

/** 按来源组装提交载荷：本地只带路径；云端携带完整凭据（副本交出，导入链路用毕擦除） */
internal fun buildOpenVaultSubmission(
    source: OpenVaultSourceType,
    local: LocalVaultFormState,
    webdav: WebdavVaultFormState,
    s3: S3VaultFormState
): OpenVaultSubmission = when (source) {
    OpenVaultSourceType.LOCAL -> OpenVaultSubmission.Local(name = local.name, path = local.path)
    OpenVaultSourceType.WEBDAV -> OpenVaultSubmission.Cloud(
        CloudVaultImportRequest.WebDav(
            name = webdav.name.trim(),
            url = webdav.url.trim(),
            username = webdav.username.trim(),
            password = webdav.passwordChars.copyOf(),
            remotePath = webdav.remotePath.trim()
        )
    )
    OpenVaultSourceType.S3_COMPATIBLE -> OpenVaultSubmission.Cloud(
        CloudVaultImportRequest.S3(
            name = s3.name.trim(),
            endpoint = s3.endpoint.trim(),
            bucket = s3.bucket.trim(),
            region = s3.region.trim(),
            accessKey = s3.accessKeyChars.copyOf(),
            secretKey = s3.secretKeyChars.copyOf(),
            objectKey = s3.objectKey.trim(),
            usePathStyle = s3.usePathStyle
        )
    )
}

/** 来源区顶部的说明文案（自 Sections 文件随表单体同迁，保持唯一消费点相邻） */
@Composable
internal fun OpenVaultSourceHint() {
    Text(
        text = stringResource(R.string.picker_open_vault_source_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
