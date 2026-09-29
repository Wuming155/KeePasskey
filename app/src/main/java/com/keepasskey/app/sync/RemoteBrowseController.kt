package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.model.RemoteListEntry
import com.keepasskey.sync.model.RemoteListPage
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 远端目录浏览 UI 状态（ISSUE-P3-387）。
 *
 * 全部字段非敏感：路径 / 名称 / 类型 / 计数 / 错误类型名；**不含**凭据。
 */
sealed interface RemoteBrowseUiState {
    data object Idle : RemoteBrowseUiState
    data object Loading : RemoteBrowseUiState
    data class Listing(
        val directoryPath: String,
        val entries: List<RemoteListEntry>,
        val nextCursor: String?,
        val truncated: Boolean,
        /** 上一页累积条目（分页续拉时合并展示）。 */
        val accumulated: List<RemoteListEntry> = entries
    ) : RemoteBrowseUiState
    data class Failed(val errorType: String) : RemoteBrowseUiState
}

/**
 * 远端目录浏览执行体（ISSUE-P3-387 / P3-396）。
 *
 * 口径：
 * - **PD-02 不变**：浏览仍走既有 Provider 构造期 SSRF / HTTPS 校验（同一端点相对路径）；
 * - **保守降级**：Provider 失败一律上浮失败态，**不**伪装空目录（kp2a 教训）；
 * - **表单优先**：浏览使用**当前表单**（可能尚未保存）的端点与凭据 clone；
 * - **目录路径语义（P3-396）**：[browseWebDav] / [browseS3] 的 path 参数是**要列举的目录路径本身**，
 *   **不再**在控制器内取父目录——下钻传 `entry.path`，分页续拉传 `directoryPath`；
 *   仅初始选库时由 UI 对可能为文件的 `remotePath` 取父目录（见 [parentDirectoryPath]）。
 */
@Singleton
class RemoteBrowseController @Inject constructor(
    private val debugLog: DebugLogBuffer
) {
    private val mutableState = MutableStateFlow<RemoteBrowseUiState>(RemoteBrowseUiState.Idle)
    val state: StateFlow<RemoteBrowseUiState> = mutableState.asStateFlow()

    /** 续拉时累积的条目（组件重入 Loading 时保留）。 */
    private var accumulated: MutableList<RemoteListEntry> = mutableListOf()

    /**
     * 浏览 [directoryPath] 目录（空串 = 端点根）。
     *
     * @param cursor 上一页 nextCursor；null = 首页（会清空累积）
     */
    suspend fun browseWebDav(
        url: String,
        username: String,
        password: CharArray,
        directoryPath: String,
        cursor: String? = null
    ) {
        if (url.isBlank()) {
            mutableState.value = RemoteBrowseUiState.Failed("InvalidEndpointError")
            return
        }
        mutableState.value = RemoteBrowseUiState.Loading
        if (cursor == null) accumulated = mutableListOf()
        val provider = try {
            WebDavSyncProvider(
                serverUrl = normalizeHttps(url),
                username = username,
                // 借用语义：Provider 构造期清零本 clone；本方法结束前再兜底
                passwordChars = password.copyOf(),
                networkOptions = SyncNetworkOptions(),
                transferOptions = SyncTransferOptions.DISABLED
            )
        } catch (t: Throwable) {
            debugLog.warn(TAG, "WebDAV 浏览 Provider 构造失败: ${t.javaClass.simpleName}")
            mutableState.value = RemoteBrowseUiState.Failed(t.javaClass.simpleName)
            return
        }
        runAndPublish(provider, directoryPath, cursor)
    }

    suspend fun browseS3(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        directoryPath: String,
        usePathStyle: Boolean,
        cursor: String? = null
    ) {
        if (endpoint.isBlank() || bucket.isBlank()) {
            mutableState.value = RemoteBrowseUiState.Failed("InvalidEndpointError")
            return
        }
        mutableState.value = RemoteBrowseUiState.Loading
        if (cursor == null) accumulated = mutableListOf()
        val accessClone = accessKey.copyOf()
        val secretClone = secretKey.copyOf()
        val provider = try {
            S3SyncProvider(
                endpoint = normalizeHttps(endpoint),
                bucketName = bucket,
                region = region.ifBlank { "us-east-1" },
                accessKeyId = accessClone,
                secretAccessKey = secretClone,
                usePathStyle = usePathStyle,
                networkOptions = SyncNetworkOptions()
            )
        } catch (t: Throwable) {
            accessClone.fill('0')
            secretClone.fill('0')
            debugLog.warn(TAG, "S3 浏览 Provider 构造失败: ${t.javaClass.simpleName}")
            mutableState.value = RemoteBrowseUiState.Failed(t.javaClass.simpleName)
            return
        }
        try {
            runAndPublish(provider, directoryPath, cursor)
        } finally {
            provider.clearCredentials()
        }
    }

    private suspend fun runAndPublish(
        provider: SyncProvider,
        directoryPath: String,
        cursor: String?
    ) {
        val browsePath = directoryPath.normalizeBrowsePath()
        val result = provider.listRemoteDirectory(browsePath, cursor = cursor, pageSize = PAGE_SIZE)
        when {
            result.isFailure -> {
                val err = result.exceptionOrNull()
                debugLog.warn(TAG, "远端目录浏览失败: ${err?.javaClass?.simpleName ?: "Unknown"}")
                mutableState.value = RemoteBrowseUiState.Failed(
                    err?.javaClass?.simpleName ?: "UnknownError"
                )
            }
            else -> {
                val page = result.getOrThrow()
                if (cursor == null) accumulated = page.entries.toMutableList()
                else accumulated.addAll(page.entries)
                mutableState.value = RemoteBrowseUiState.Listing(
                    directoryPath = browsePath,
                    entries = page.entries,
                    nextCursor = page.nextCursor,
                    truncated = page.truncated,
                    accumulated = accumulated.toList()
                )
            }
        }
    }

    fun reset() {
        accumulated = mutableListOf()
        mutableState.value = RemoteBrowseUiState.Idle
    }

    private fun String.normalizeBrowsePath(): String = trim().trim('/')

    private fun normalizeHttps(url: String): String {
        val t = url.trim()
        return if (t.contains("://")) t else "https://$t"
    }

    private companion object {
        const val TAG = "RemoteBrowseController"
        const val PAGE_SIZE = 200
    }
}

/**
 * 把「远端路径字段」映射为其**父目录**（路径字段可能是文件时，浏览应列文件所在目录）。
 * ISSUE-P3-396：下钻与分页续拉**不得**再对该函数结果二次取父目录。
 */
internal fun parentDirectoryPath(remotePath: String): String {
    val trimmed = remotePath.trim().trim('/')
    if (trimmed.isEmpty()) return ""
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "" else trimmed.substring(0, idx)
}
