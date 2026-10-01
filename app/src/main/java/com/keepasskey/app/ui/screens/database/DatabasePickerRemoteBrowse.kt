package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.sync.RemoteBrowseController
import com.keepasskey.app.sync.RemoteBrowseUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 「打开已有库」对话框的**远端目录浏览**透传（ISSUE-P3-400）。
 *
 * 自 `DatabasePickerViewModel` 拆出（§391 行数分档闸门，**纯结构性改动、行为逐字不变**）：
 * 浏览能力本体是设置页与本对话框**共用的同一控制器单例** [RemoteBrowseController]（表单凭据优先、
 * 无已保存回退），本类只补两件事——① 未注入控制器时（纯 JVM 单测构造）状态恒 `Idle`；
 * ② 借用语义下传的凭据数组在协程结束即擦。
 */
internal class DatabasePickerRemoteBrowseController(
    private val browseController: RemoteBrowseController?,
    private val scope: CoroutineScope
) {

    private val idleState = MutableStateFlow<RemoteBrowseUiState>(RemoteBrowseUiState.Idle)

    /** 浏览对话框状态（未注入控制器时恒 Idle，仅供单测构造路径） */
    val browseState: StateFlow<RemoteBrowseUiState>
        get() = browseController?.state ?: idleState

    /** 浏览 WebDAV 目录：[password] 为借用语义副本（UI 传 copyOf），协程结束即擦 */
    fun browseWebDav(url: String, username: String, password: CharArray, directoryPath: String, cursor: String?) {
        val controller = browseController ?: return
        scope.launch {
            try {
                controller.browseWebDav(url, username, password, directoryPath, cursor)
            } finally {
                password.fill('0')
            }
        }
    }

    /** 浏览 S3 目录：AK/SK 借用语义同上 */
    fun browseS3(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        directoryPath: String,
        usePathStyle: Boolean,
        cursor: String?
    ) {
        val controller = browseController ?: return
        scope.launch {
            try {
                controller.browseS3(endpoint, bucket, region, accessKey, secretKey, directoryPath, usePathStyle, cursor)
            } finally {
                accessKey.fill('0')
                secretKey.fill('0')
            }
        }
    }

    /** 关闭浏览对话框并复位浏览状态 */
    fun dismiss() {
        browseController?.reset()
    }
}
