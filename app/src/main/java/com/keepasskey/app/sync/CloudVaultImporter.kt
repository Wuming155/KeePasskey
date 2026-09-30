package com.keepasskey.app.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端打开导入请求（ISSUE-P2-399）。
 *
 * 「打开已有 KDBX 密码库」对话框收集的完整远端连接参数。凭据字段（[WebDav.password] /
 * [S3.accessKey] / [S3.secretKey]）为**借用语义**：调用方（对话框）传副本后即交出，
 * 导入链路在任何结果路径用毕擦除，绝不落地 `String`。
 */
sealed interface CloudVaultImportRequest {

    /** 库展示名（导入器据其生成本地文件名 `<name>.kdbx`） */
    val name: String

    /** WebDAV 云端打开请求：[url] 为服务器地址，[remotePath] 为相对远端根的库文件路径 */
    class WebDav(
        override val name: String,
        val url: String,
        val username: String,
        val password: CharArray,
        val remotePath: String
    ) : CloudVaultImportRequest

    /** S3 兼容对象存储云端打开请求 */
    class S3(
        override val name: String,
        val endpoint: String,
        val bucket: String,
        val region: String,
        val accessKey: CharArray,
        val secretKey: CharArray,
        val objectKey: String,
        val usePathStyle: Boolean
    ) : CloudVaultImportRequest
}

/** 云端打开导入结果：[Success.localPath] 为已落地的本地库文件绝对路径（即登记用的 `path`） */
sealed interface CloudVaultImportResult {
    data class Success(val localPath: String) : CloudVaultImportResult
    data class Failure(val message: UiMessage) : CloudVaultImportResult
}

/** Provider 构建缝：生产走 TLS-only 工厂；JVM 单测注入假 Provider 以脱离真实网络 */
fun interface CloudVaultProviderFactory {
    fun create(request: CloudVaultImportRequest): SyncProvider
}

/**
 * 云端打开导入器（ISSUE-P2-399）：把「打开云端数据库」入口从*只登记不连接*的假桩
 * 变成一条自洽可通的链路。
 *
 * 整改前该入口只把 URL 存为 `KnownDatabaseEntry`，凭据全靠用户事后到同步配置页补录；
 * 且解锁链路对远端条目走 `File(url)` 缺失分支会**新建空库顶替云端库**。
 *
 * 本类编排（顺序即安全口径）：
 * 1. 端点 HTTPS 归一化（[HttpsEndpointPolicy]，与同步配置保存同一语义）；
 * 2. **先下载后落凭据**——用所填凭据直接下载远端库文件到应用私有目录，下载失败即整体失败，
 *    **绝不**先写 `SyncCredentialsStore`（否则错凭据会覆盖用户既有的可用同步配置）；
 * 3. 下载成功才提交凭据（封印落盘 + 置当前 Provider），再把临时件原子改名为目标库文件；
 * 4. 上层据 `Success.localPath` 走仓库既有 `importExternalDatabase` 单一登记出口——
 *    此后该库就是一条**本地库 + 已保存同步配置**的常规链路（解锁、自动同步、浏览远端目录全部复用既有机制）。
 */
interface CloudVaultImporter {
    suspend fun import(request: CloudVaultImportRequest): CloudVaultImportResult
}

@Singleton
class RealCloudVaultImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncCredentialsStore: SyncCredentialsStore,
    // 允许 null 仅为 JVM 单测构造；生产 DI 恒注入真实实现（与 SyncCredentialsStore 同范式）
    private val debugLog: DebugLogBuffer? = null
) : CloudVaultImporter {

    /** Provider 构建缝（单测注入假实现；生产恒为 TLS-only 工厂） */
    @VisibleForTesting
    internal var providerFactory: CloudVaultProviderFactory =
        CloudVaultProviderFactory { request -> createProductionProvider(request) }

    override suspend fun import(request: CloudVaultImportRequest): CloudVaultImportResult {
        // 借用语义收口：任何结果路径（含所有早退失败分支）都必须擦除请求侧凭据。
        // commit 路径 store 已封印并擦过，此处重复 fill 无害。
        // ISSUE-P2-403：入口即快照凭据——下载是秒级网络操作，期间任何外部路径对请求侧数组的
        // 擦除（如 UI 离场收口）都不得影响提交封印的内容；快照随本函数 finally 统一擦除。
        val passwordSnapshot = if (request is CloudVaultImportRequest.WebDav) request.password.copyOf() else CharArray(0)
        val accessKeySnapshot = (request as? CloudVaultImportRequest.S3)?.accessKey?.copyOf() ?: CharArray(0)
        val secretKeySnapshot = (request as? CloudVaultImportRequest.S3)?.secretKey?.copyOf() ?: CharArray(0)
        try {
            if (request.name.isBlank()) {
                return CloudVaultImportResult.Failure(downloadFailed())
            }
            val filesDir = context.filesDir ?: return CloudVaultImportResult.Failure(downloadFailed())
            val sanitized = if (request.name.endsWith(".kdbx", ignoreCase = true)) request.name else "${request.name}.kdbx"
            val target = File(filesDir, sanitized)
            // 本地同名库文件已存在时 fail-closed：覆盖既有库文件是数据丢失级动作，绝不静默
            if (target.exists()) {
                return CloudVaultImportResult.Failure(
                    UiMessage(R.string.picker_cloud_local_conflict, isError = true)
                )
            }
            val provider = try {
                providerFactory.create(request)
            } catch (e: Exception) {
                return CloudVaultImportResult.Failure(
                    if (e is com.keepasskey.sync.model.SyncException.InvalidEndpointError) {
                        UiMessage(R.string.sync_error_https_required, listOf(protocolLabel(request)), isError = true)
                    } else {
                        downloadFailed()
                    }
                )
            }
            try {
                val remotePath = when (request) {
                    is CloudVaultImportRequest.WebDav -> {
                        // 与 SyncProviderResolver.resolveRemotePath 同一口径：缺根斜杠自动补
                        val raw = request.remotePath.trim()
                        if (raw.startsWith("/")) raw else "/$raw"
                    }
                    is CloudVaultImportRequest.S3 -> request.objectKey.trim()
                }
                val tmp = File(filesDir, "$sanitized.importing")
                try {
                    tmp.outputStream().use { sink ->
                        provider.download(remotePath, sink).getOrElse {
                            return CloudVaultImportResult.Failure(downloadFailed())
                        }
                    }
                    // 先提交凭据再改名：凭据封印失败时不留下「库已登记但同步配置缺失」的半状态
                    if (!commitCredentials(request, passwordSnapshot, accessKeySnapshot, secretKeySnapshot)) {
                        return CloudVaultImportResult.Failure(
                            UiMessage(R.string.sync_config_save_failed, isError = true)
                        )
                    }
                    if (!tmp.renameTo(target)) {
                        return CloudVaultImportResult.Failure(downloadFailed())
                    }
                    return CloudVaultImportResult.Success(target.absolutePath)
                } finally {
                    tmp.delete()
                }
            } finally {
                // S3 Provider 持有凭据 clone 用于签名，导入结束即擦（同 SyncCycleRunner 收口口径）
                (provider as? S3SyncProvider)?.clearCredentials()
            }
        } finally {
            wipeCredentials(request)
            passwordSnapshot.fill('0')
            accessKeySnapshot.fill('0')
            secretKeySnapshot.fill('0')
        }
    }

    /**
     * 下载成功后把凭据封印落盘并置为当前同步 Provider；store 内部负责凭据擦除。
     * 端点在此处再过一次 [HttpsEndpointPolicy] 归一化（与 Provider 构造同语义），保证
     * 落盘值与同步配置页保存口径一致（无 scheme 自动补 https://）。
     */
    private fun commitCredentials(
        request: CloudVaultImportRequest,
        passwordSnapshot: CharArray,
        accessKeySnapshot: CharArray,
        secretKeySnapshot: CharArray
    ): Boolean = when (request) {
        is CloudVaultImportRequest.WebDav -> {
            val normalizedUrl = HttpsEndpointPolicy.normalize(request.url) ?: return false
            val saved = syncCredentialsStore.saveWebDavConfig(
                normalizedUrl, request.username, passwordSnapshot, request.remotePath.trim()
            )
            if (saved) syncCredentialsStore.saveProvider(CloudSyncProvider.WEBDAV)
            saved
        }
        is CloudVaultImportRequest.S3 -> {
            val normalizedEndpoint = HttpsEndpointPolicy.normalize(request.endpoint) ?: return false
            val saved = syncCredentialsStore.saveS3Config(
                normalizedEndpoint, request.bucket, request.region,
                accessKeySnapshot, secretKeySnapshot, request.objectKey, request.usePathStyle
            )
            if (saved) syncCredentialsStore.saveProvider(CloudSyncProvider.S3_COMPATIBLE)
            saved
        }
    }

    private fun wipeCredentials(request: CloudVaultImportRequest) {
        when (request) {
            is CloudVaultImportRequest.WebDav -> request.password.fill('0')
            is CloudVaultImportRequest.S3 -> {
                request.accessKey.fill('0')
                request.secretKey.fill('0')
            }
        }
    }

    private fun protocolLabel(request: CloudVaultImportRequest): String = when (request) {
        is CloudVaultImportRequest.WebDav -> "WebDAV"
        is CloudVaultImportRequest.S3 -> "S3"
    }

    private fun downloadFailed(): UiMessage =
        UiMessage(R.string.picker_cloud_download_failed, isError = true)

    private companion object {

        /**
         * 生产 Provider 构建：与 `SyncProviderResolver.resolveProvider` 同口径
         * （TLS-only 客户端工厂 + 默认网络选项）。差异：本链路只下载不上传，
         * 分块上传偏好不参与（取 DISABLED 默认）；凭据借用语义见各分支注释。
         */
        fun createProductionProvider(request: CloudVaultImportRequest): SyncProvider = when (request) {
            is CloudVaultImportRequest.WebDav -> {
                val normalizedUrl = HttpsEndpointPolicy.normalize(request.url)
                    ?: throw com.keepasskey.sync.model.SyncException.InvalidEndpointError(
                        "WebDAV 端点必须使用 HTTPS"
                    )
                // passwordChars 借用语义：Provider 在 init 中即时计算 Basic 认证头，
                // 构造返回后数组即归还调用方（由导入编排提交凭据 / 擦除）
                WebDavSyncProvider(
                    serverUrl = normalizedUrl,
                    username = request.username,
                    passwordChars = request.password,
                    networkOptions = SyncNetworkOptions()
                )
            }
            is CloudVaultImportRequest.S3 -> {
                val normalizedEndpoint = HttpsEndpointPolicy.normalize(request.endpoint)
                    ?: throw com.keepasskey.sync.model.SyncException.InvalidEndpointError(
                        "S3 端点必须使用 HTTPS"
                    )
                // ISSUE-P1-06 借用语义转移：Provider 持有 clone 用于多次签名复用，
                // 原数组留在请求侧由导入编排提交凭据 / 擦除
                S3SyncProvider(
                    endpoint = normalizedEndpoint,
                    bucketName = request.bucket,
                    region = request.region,
                    accessKeyId = request.accessKey.clone(),
                    secretAccessKey = request.secretKey.clone(),
                    usePathStyle = request.usePathStyle,
                    networkOptions = SyncNetworkOptions()
                )
            }
        }
    }
}
