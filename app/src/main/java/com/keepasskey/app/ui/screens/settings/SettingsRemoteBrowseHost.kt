package com.keepasskey.app.ui.screens.settings

import com.keepasskey.core.log.AppLog
import com.keepasskey.app.sync.RemoteBrowseController
import com.keepasskey.app.sync.RemoteBrowseCredentials
import com.keepasskey.app.sync.RemoteBrowseUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "RemoteBrowse"

/**
 * ISSUE-P3-387 / ISSUE-P3-395：设置页远端目录浏览门面。
 *
 * 职责：
 * - 状态转发与协程调度（见 [RemoteBrowseController]）；
 * - **凭据解析**：表单优先，空则回退已保存凭据（与「测试连接」同源）；
 * - **借用语义**：启动浏览前解析出的数组在协程结束时一律清零——
 *   UI 侧必须传 `copyOf()`，不得把表单原数组直接交给本宿主。
 *
 * 已保存凭据经 [loadS3Snapshot] / WebDAV 回调**一次性**读出；未采用的副本在本类内立即
 * 清零，避免「二次 loadS3Config 把 Secret 落成无人看管的临时数组」。
 */
internal class SettingsRemoteBrowseHost(
    private val scope: CoroutineScope,
    /** 已保存 WebDAV 密码（`null` = 无配置 / 解封失败）。 */
    private val savedWebDavPassword: () -> CharArray? = { null },
    /** 一次性读出 S3 已保存密钥对；实现方须返回**调用方可清零的数组**。 */
    private val loadS3Snapshot: () -> Pair<CharArray?, CharArray?> = { null to null }
) {
    private val controller = RemoteBrowseController()

    val state: StateFlow<RemoteBrowseUiState> = controller.state

    fun browseWebDav(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String,
        cursor: String? = null
    ) {
        val saved = try {
            savedWebDavPassword()
        } catch (t: Throwable) {
            // 已保存凭据读取失败（解封 / 偏好损坏）不阻断表单输入，但必须留痕，
            // 否则无法区分「没有保存值」与「读取出错」。
            AppLog.w(TAG, "读取已保存 WebDAV 凭据失败: ${t.javaClass.simpleName}")
            null
        }
        val effective = RemoteBrowseCredentials.resolveBrowsePassword(password, saved)
        // 表单优先时 saved 副本无人消费：立即清零，防泄漏
        if (saved != null && saved !== effective) saved.fill('0')
        scope.launch {
            try {
                controller.browseWebDav(url, username, effective, remotePath, cursor)
            } finally {
                effective.fill('0')
            }
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
        val (savedAccess, savedSecret) = try {
            loadS3Snapshot()
        } catch (t: Throwable) {
            AppLog.w(TAG, "读取已保存 S3 凭据失败: ${t.javaClass.simpleName}")
            null to null
        }
        val (effectiveAccess, effectiveSecret) = RemoteBrowseCredentials.resolveS3BrowseKeys(
            formAccessKey = accessKey,
            formSecretKey = secretKey,
            savedAccessKey = savedAccess,
            savedSecretKey = savedSecret
        )
        // 未采用的保存侧副本立即清零
        if (savedAccess != null && savedAccess !== effectiveAccess) savedAccess.fill('0')
        if (savedSecret != null && savedSecret !== effectiveSecret) savedSecret.fill('0')
        scope.launch {
            try {
                controller.browseS3(
                    endpoint, bucket, region, effectiveAccess, effectiveSecret,
                    objectKey, usePathStyle, cursor
                )
            } finally {
                effectiveAccess.fill('0')
                effectiveSecret.fill('0')
            }
        }
    }

    fun dismiss() = controller.reset()
}
