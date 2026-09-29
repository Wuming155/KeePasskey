package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.sync.RemoteBrowseController
import com.keepasskey.app.sync.RemoteBrowseUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ISSUE-P3-387：设置页远端目录浏览门面（自 [SettingsViewModel] 拆出，行数门禁）。
 *
 * 只做状态转发与协程调度；浏览语义见 [RemoteBrowseController]。
 */
internal class SettingsRemoteBrowseHost(
    debugLog: DebugLogBuffer,
    private val scope: CoroutineScope
) {
    private val controller = RemoteBrowseController(debugLog)

    val state: StateFlow<RemoteBrowseUiState> = controller.state

    fun browseWebDav(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String,
        cursor: String? = null
    ) {
        scope.launch {
            controller.browseWebDav(url, username, password, remotePath, cursor)
        }
    }

    fun browseS3(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean,
        cursor: String? = null
    ) {
        scope.launch {
            controller.browseS3(
                endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle, cursor
            )
        }
    }

    fun dismiss() = controller.reset()
}
