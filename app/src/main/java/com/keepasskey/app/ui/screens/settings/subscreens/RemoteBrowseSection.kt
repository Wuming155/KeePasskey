package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.runtime.Composable
import com.keepasskey.app.sync.RemoteBrowseUiState
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.model.RemoteListEntry

/**
 * ISSUE-P3-387：云端同步配置页的远端目录浏览对话框装配段
 * （自 [CloudSyncScreen] 拆出，行数门禁）。
 */
@Composable
internal fun RemoteBrowseSection(
    visible: Boolean,
    provider: CloudSyncProvider,
    browseState: RemoteBrowseUiState,
    webdavUrl: String,
    webdavUsername: String,
    webdavPasswordChars: CharArray,
    webdavRemotePath: String,
    s3Endpoint: String,
    s3Bucket: String,
    s3Region: String,
    s3AccessKeyChars: CharArray,
    s3SecretKeyChars: CharArray,
    s3ObjectKey: String,
    s3UsePathStyle: Boolean,
    onBrowseWebDav: (String, String, CharArray, String, String?) -> Unit,
    onBrowseS3: (String, String, String, CharArray, CharArray, String, Boolean, String?) -> Unit,
    onSelectFile: (RemoteListEntry) -> Unit,
    onDismissBrowse: () -> Unit
) {
    if (!visible) return
    val listing = browseState as? RemoteBrowseUiState.Listing
    RemoteBrowseDialog(
        state = browseState,
        onSelectFile = onSelectFile,
        // ISSUE-P3-395/396：宿主清零入参故传 copyOf；下钻传 entry.path（即目录本身，不再取父目录）
        onNavigate = { entry ->
            if (provider == CloudSyncProvider.WEBDAV) {
                onBrowseWebDav(webdavUrl, webdavUsername, webdavPasswordChars.copyOf(), entry.path, null)
            } else {
                onBrowseS3(
                    s3Endpoint, s3Bucket, s3Region,
                    s3AccessKeyChars.copyOf(), s3SecretKeyChars.copyOf(),
                    entry.path, s3UsePathStyle, null
                )
            }
        },
        onLoadMore = {
            val cursor = listing?.nextCursor
            if (provider == CloudSyncProvider.WEBDAV) {
                onBrowseWebDav(
                    webdavUrl, webdavUsername, webdavPasswordChars.copyOf(),
                    listing?.directoryPath.orEmpty(), cursor
                )
            } else {
                onBrowseS3(
                    s3Endpoint, s3Bucket, s3Region,
                    s3AccessKeyChars.copyOf(), s3SecretKeyChars.copyOf(),
                    listing?.directoryPath.orEmpty().ifEmpty { s3ObjectKey },
                    s3UsePathStyle,
                    cursor
                )
            }
        },
        onClose = onDismissBrowse
    )
}
