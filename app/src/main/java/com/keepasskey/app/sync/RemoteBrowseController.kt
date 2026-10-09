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
        val accumulated: List<RemoteListEntry> = entries,
        /** 导航/续拉进行中：保持旧列表可见，避免整表闪一下（仿安卓文件管理器）。 */
        val loading: Boolean = false,
        /** 导航失败时的错误类型名；旧列表仍保留。 */
        val lastError: String? = null
    ) : RemoteBrowseUiState
    data class Failed(val errorType: String) : RemoteBrowseUiState
}

/**
 * 远端目录浏览执行体（ISSUE-P3-387 / P3-396 / 导航防闪）。
 *
 * 口径：
 * - **PD-02 不变**：浏览仍走既有 Provider 构造期 SSRF / HTTPS 校验（同一端点相对路径）；
 * - **保守降级**：Provider 失败一律上浮失败态，**不**伪装空目录（kp2a 教训）；
 * - **表单优先**：浏览使用**当前表单**（可能尚未保存）的端点与凭据 clone；
 * - **目录路径语义（P3-396）**：path 参数是**要列举的目录路径本身**；
 * - **导航防闪**：已有列表时下钻/上一级不先切空 Loading，而是 `Listing(loading=true)` 保留旧表。
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
     * @param cursor 上一页 nextCursor；null = 首页
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
        beginBrowseLoading()
        val provider = try {
            WebDavSyncProvider(
                serverUrl = normalizeHttps(url),
                username = username,
                passwordChars = password.copyOf(),
                networkOptions = SyncNetworkOptions(),
                transferOptions = SyncTransferOptions.DISABLED
            )
        } catch (t: Throwable) { // cancel-n/a: 保护段为非挂起 Provider 构造
            debugLog.warn(TAG, "WebDAV 浏览 Provider 构造失败: ${t.javaClass.simpleName}")
            publishBrowseFailure(t.javaClass.simpleName)
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
        beginBrowseLoading()
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
        } catch (t: Throwable) { // cancel-n/a: 保护段为非挂起 Provider 构造
            accessClone.fill('0')
            secretClone.fill('0')
            debugLog.warn(TAG, "S3 浏览 Provider 构造失败: ${t.javaClass.simpleName}")
            publishBrowseFailure(t.javaClass.simpleName)
            return
        }
        try {
            runAndPublish(provider, directoryPath, cursor)
        } finally {
            provider.clearCredentials()
        }
    }

    /**
     * 导航/续拉开始：已有 Listing 时保留旧表 + `loading=true`，避免整表闪一下。
     * 首次打开（无旧表）才进入纯 Loading。
     */
    private fun beginBrowseLoading() {
        val previous = mutableState.value
        if (previous is RemoteBrowseUiState.Listing) {
            // 续拉时保留累积；下钻/上一级时累积将在成功后整体替换
            mutableState.value = previous.copy(loading = true, lastError = null)
        } else {
            if (previous !is RemoteBrowseUiState.Listing) {
                accumulated = mutableListOf()
            }
            mutableState.value = RemoteBrowseUiState.Loading
        }
    }

    private fun publishBrowseFailure(errorType: String) {
        val previous = mutableState.value as? RemoteBrowseUiState.Listing
        if (previous != null && previous.loading) {
            mutableState.value = previous.copy(loading = false, lastError = errorType)
        } else {
            mutableState.value = RemoteBrowseUiState.Failed(errorType)
        }
    }

    private suspend fun runAndPublish(
        provider: SyncProvider,
        directoryPath: String,
        cursor: String?
    ) {
        val browsePath = directoryPath.normalizeBrowsePath()
        val result = provider.listRemoteDirectory(browsePath, cursor = cursor, pageSize = PAGE_SIZE)
        // 配置路径列举失败且非分页续拉时：回退端点根（默认路径），避免「设了远程路径后浏览打不开」
        val effective = if (result.isFailure && cursor == null && browsePath.isNotEmpty()) {
            debugLog.warn(TAG, "列举 $browsePath 失败，回退默认路径（端点根）")
            provider.listRemoteDirectory("", cursor = null, pageSize = PAGE_SIZE)
        } else {
            result
        }
        when {
            effective.isFailure -> {
                val err = effective.exceptionOrNull()
                val errorType = err?.javaClass?.simpleName ?: "UnknownError"
                debugLog.warn(TAG, "远端目录浏览失败: $errorType")
                publishBrowseFailure(errorType)
            }
            else -> {
                val page = effective.getOrThrow()
                val publishedPath = if (result.isFailure) "" else browsePath
                // 成功才替换累积；失败/加载中不提前清表
                if (cursor == null) accumulated = page.entries.toMutableList()
                else accumulated.addAll(page.entries)
                mutableState.value = RemoteBrowseUiState.Listing(
                    directoryPath = publishedPath,
                    entries = page.entries,
                    nextCursor = page.nextCursor,
                    truncated = page.truncated,
                    accumulated = accumulated.toList(),
                    loading = false,
                    lastError = null
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
 * 把「远端路径字段」映射为浏览种子目录。
 *
 * - 完整 URL：取 path 组件后再取父目录；
 * - 相对/绝对路径：取父目录（文件路径 → 所在目录；单段目录 → 端点根）。
 *
 * 设置远程路径后浏览失败时，控制器侧还会回退端点根（见 `runAndPublish`）。
 */
internal fun parentDirectoryPath(remotePath: String): String {
    var path = remotePath.trim()
    val nutstoreBase = WebDavDefaults.NUTSTORE_URL.trimEnd('/')
    if (path.startsWith(nutstoreBase)) {
        path = path.substring(nutstoreBase.length)
    } else if (path.contains("://")) {
        path = try {
            java.net.URI(path).path.orEmpty()
        } catch (_: Exception) {
            ""
        }
        // 通用 WebDAV：path 常含端点前缀（如 /dav/）；若能识别 Nutstore 形态再剥一次
        if (path.startsWith("/dav/")) path = path.removePrefix("/dav")
        else if (path == "/dav") path = ""
    }
    path = path.trim().trim('/')
    if (path.isEmpty()) return ""
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) "" else path.substring(0, idx)
}
