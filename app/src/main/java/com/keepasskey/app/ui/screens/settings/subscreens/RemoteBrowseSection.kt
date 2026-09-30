package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.runtime.Composable
import com.keepasskey.app.sync.RemoteBrowseUiState
import com.keepasskey.app.sync.parentDirectoryPath
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.model.RemoteListEntry

/**
 * 云端同步配置页的远端目录浏览对话框装配段。
 *
 * 对齐 keepass2android：路径段跳转走同一 browse 通道；选中 `.kdbx` 回填远程路径。
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

    fun browseDirectory(directoryPath: String) {
        if (provider == CloudSyncProvider.WEBDAV) {
            onBrowseWebDav(webdavUrl, webdavUsername, webdavPasswordChars.copyOf(), directoryPath, null)
        } else {
            onBrowseS3(
                s3Endpoint, s3Bucket, s3Region,
                s3AccessKeyChars.copyOf(), s3SecretKeyChars.copyOf(),
                directoryPath, s3UsePathStyle, null
            )
        }
    }

    RemoteBrowseDialog(
        state = browseState,
        onSelectFile = onSelectFile,
        onNavigate = { entry -> browseDirectory(entry.path) },
        onNavigateToPath = { path -> browseDirectory(path) },
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
