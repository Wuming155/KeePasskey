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

/**
 * `OpenExistingVaultDialog` 的表单体与提交组装（ISSUE-P2-399 自对话框本体下沉，
 * 沿用 §178「快照状态留对话框本体、此处只消费状态对象」口径；字段增多后独立成文件）。
 *
 * 借用语义：提交时凭据 `CharArray` 传**副本**（[buildOpenVaultSubmission] 内 copyOf），
 * 对话框本体留存原数组供导入失败时重试，离开组合时统一擦除。
 */

/** 表单体：来源说明 + 来源切换 Chip + 按来源渲染对应表单段落（段落组件见 Sections 文件） */
@Composable
internal fun OpenVaultFormBody(
    selectedSource: OpenVaultSourceType,
    onSelectSource: (OpenVaultSourceType) -> Unit,
    local: LocalVaultFormState,
    webdav: WebdavVaultFormState,
    s3: S3VaultFormState,
    onBrowse: () -> Unit
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

            OpenVaultSourceType.WEBDAV -> WebdavVaultSourceForm(state = webdav)

            OpenVaultSourceType.S3_COMPATIBLE -> S3VaultSourceForm(state = s3)
        }
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
