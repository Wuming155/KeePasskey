package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云同步 Provider 构建与远端路径解析（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运）。
 *
 * 职责边界：只做「凭据存储 → Provider 实例 / 远端对象路径」的构建，不参与同步周期决策
 * （见 `SyncCycleRunner`）与冲突决策（见 `SyncConflictController`）。
 * 凭据借用-擦除契约与拆分前逐行一致：
 * - WebDAV：`passwordChars` 为借用语义，构造完成后由本方法即时 `fill('0')`；
 * - S3：AccessKey/SecretKey 以 clone 传入 Provider 持有，构造失败立即擦除 clone；
 *   生产消费者经 [useProvider] 持有并在作用域退出时统一释放。
 */
@Singleton
class SyncProviderResolver @Inject constructor(
    private val syncCredentialsStore: SyncCredentialsStore,
    private val preferences: SyncPreferences,
    private val debugLog: DebugLogBuffer,
    private val providerFactory: SyncProviderFactory
) {

    /** 保留既有 JVM 单测三参数构造入口。 */
    constructor(
        syncCredentialsStore: SyncCredentialsStore,
        preferences: SyncPreferences,
        debugLog: DebugLogBuffer
    ) : this(syncCredentialsStore, preferences, debugLog, ProductionSyncProviderFactory())

    /** ISSUE-P3-03 (43a)：偏好 → 传输层配置（依赖倒置；sync 模块不感知 app 偏好类型） */
    private fun transferOptions(): SyncTransferOptions {
        val settings = preferences.currentSettings()
        return SyncTransferOptions.fromPreferences(
            chunkedUploadEnabled = settings.webdavChunkedUpload,
            chunkSizeMb = settings.webdavChunkSizeMb
        )
    }

    fun resolveProvider(): SyncProvider? = resolveProviderWithTarget(DEFAULT_REMOTE_FILE_NAME)?.provider

    /** 从同一份配置快照构造 Provider 和非秘密目标描述。 */
    private fun resolveProviderWithTarget(defaultFileName: String): ResolvedProviderTarget? {
        return when (syncCredentialsStore.loadProvider()) {
            CloudSyncProvider.WEBDAV -> {
                val cfg = syncCredentialsStore.loadWebDavConfig() ?: return null
                try {
                    if (cfg.url.isBlank()) return null
                    val remotePath = cfg.remotePath.trim().takeIf(String::isNotBlank)
                        ?.let { if (it.startsWith("/")) it else "/$it" }
                        ?: "/$defaultFileName"
                    val target = SyncProviderTarget(
                        remotePath = remotePath,
                        identity = syncCredentialsStore.targetIdentity(
                            CloudSyncProvider.WEBDAV.name,
                            cfg.url,
                            cfg.username,
                            remotePath
                        )
                    )
                    val provider = providerFactory.createWebDav(
                        serverUrl = cfg.url,
                        username = cfg.username,
                        // Wave 15：cfg.password 已是 CharArray（借用语义），构造完成后由本方统一擦除
                        passwordChars = cfg.password,
                        // Wave 14 传输安全：TLS-only + 显式超时；证书固定已整体移除，
                        // 证书验证完全依赖系统默认 CA 链（客户端由 sync 模块工厂构建）
                        networkOptions = SyncNetworkOptions(),
                        // ISSUE-P3-03 (43a)：分块上传偏好经纯数据契约下传（依赖倒置）
                        transferOptions = transferOptions()
                    )
                    ResolvedProviderTarget(provider, target)
                } finally {
                    cfg.password.fill('0')
                }
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val cfg = syncCredentialsStore.loadS3Config() ?: return null
                try {
                    if (cfg.endpoint.isBlank() || cfg.bucket.isBlank()) return null
                    // ISSUE-P1-06 整改：凭据以 CharArray clone 传入 Provider（借用语义转移），
                    // Provider 持有期间可多次签名复用，由 useProvider 在作用域退出时释放。
                    // 旧版 String(cfg.accessKey) 物化后与 Provider 同生命周期，结构性不可擦除。
                    val accessKeyClone = cfg.accessKey.clone()
                    var secretKeyClone: CharArray? = null
                    var ownershipTransferred = false
                    try {
                        val secretKeyForProvider = cfg.secretKey.clone().also { secretKeyClone = it }
                        val objectKey = cfg.objectKey.trim().takeIf(String::isNotBlank) ?: defaultFileName
                        val target = SyncProviderTarget(
                            remotePath = objectKey,
                            identity = syncCredentialsStore.targetIdentity(
                                CloudSyncProvider.S3_COMPATIBLE.name,
                                cfg.endpoint,
                                cfg.bucket,
                                cfg.region,
                                objectKey,
                                cfg.usePathStyle.toString()
                            )
                        )
                        val provider = providerFactory.createS3(
                            endpoint = cfg.endpoint,
                            bucketName = cfg.bucket,
                            region = cfg.region,
                            accessKeyId = accessKeyClone,
                            secretAccessKey = secretKeyForProvider,
                            usePathStyle = cfg.usePathStyle,
                            networkOptions = SyncNetworkOptions(),
                            // TASK-45（P2-14）：恢复上次持久化的服务端时钟偏移，启动即补偿；
                            // 同步期间每次响应携带 Date 头即经回调刷新落盘（跨进程保留）
                            initialClockOffsetMillis = syncCredentialsStore.loadS3ClockOffsetMillis(),
                            clockOffsetUpdater = { offset ->
                                try {
                                    syncCredentialsStore.saveS3ClockOffsetMillis(offset)
                                } catch (e: Exception) {
                                    // 持久化失败仅丢失跨进程记忆：本会话内存偏移仍即时生效，
                                    // 下次同步按 fail-closed 重新学习，不阻断本次同步
                                    debugLog.warn(SYNC_LOG_TAG, "S3 时钟偏移持久化失败: ${e.javaClass.simpleName}")
                                }
                            }
                        )
                        ownershipTransferred = true
                        ResolvedProviderTarget(provider, target)
                    } finally {
                        if (!ownershipTransferred) {
                            // 构造失败或同步早退时擦除尚未转移给 Provider 的副本。
                            accessKeyClone.fill('0')
                            secretKeyClone?.fill('0')
                        }
                    }
                } finally {
                    // 原始 cfg 数组由 loadS3Config 借用语义管理，此处擦除防止残留
                    cfg.accessKey.fill('0')
                    cfg.secretKey.fill('0')
                }
            }
        }
    }

    /**
     * 从已保存回执创建 Provider。每次 use 都重新解封独立凭据副本，但端点、路径与凭据密文
     * 固定来自回执，不会重新读取当前全局配置。
     */
    private fun resolveProviderWithSnapshot(
        defaultFileName: String,
        snapshot: SyncProviderConfigSnapshot
    ): ResolvedProviderTarget? {
        return when (snapshot.provider) {
        CloudSyncProvider.WEBDAV -> {
            val cfg = syncCredentialsStore.loadWebDavConfig(snapshot) ?: return null
            try {
                if (cfg.url.isBlank()) return null
                val remotePath = cfg.remotePath.trim().takeIf(String::isNotBlank)
                    ?.let { if (it.startsWith("/")) it else "/$it" }
                    ?: "/$defaultFileName"
                val target = SyncProviderTarget(
                    remotePath = remotePath,
                    identity = syncCredentialsStore.targetIdentity(
                        CloudSyncProvider.WEBDAV.name,
                        cfg.url,
                        cfg.username,
                        remotePath
                    )
                )
                val provider = providerFactory.createWebDav(
                    serverUrl = cfg.url,
                    username = cfg.username,
                    passwordChars = cfg.password,
                    networkOptions = SyncNetworkOptions(),
                    transferOptions = transferOptions()
                )
                ResolvedProviderTarget(provider, target)
            } finally {
                cfg.password.fill('0')
            }
        }
        CloudSyncProvider.S3_COMPATIBLE -> {
            val cfg = syncCredentialsStore.loadS3Config(snapshot) ?: return null
            try {
                if (cfg.endpoint.isBlank() || cfg.bucket.isBlank()) return null
                val accessKeyClone = cfg.accessKey.clone()
                var secretKeyClone: CharArray? = null
                var ownershipTransferred = false
                try {
                    val secretForProvider = cfg.secretKey.clone().also { secretKeyClone = it }
                    val objectKey = cfg.objectKey.trim().takeIf(String::isNotBlank) ?: defaultFileName
                    val target = SyncProviderTarget(
                        remotePath = objectKey,
                        identity = syncCredentialsStore.targetIdentity(
                            CloudSyncProvider.S3_COMPATIBLE.name,
                            cfg.endpoint,
                            cfg.bucket,
                            cfg.region,
                            objectKey,
                            cfg.usePathStyle.toString()
                        )
                    )
                    val provider = providerFactory.createS3(
                        endpoint = cfg.endpoint,
                        bucketName = cfg.bucket,
                        region = cfg.region,
                        accessKeyId = accessKeyClone,
                        secretAccessKey = secretForProvider,
                        usePathStyle = cfg.usePathStyle,
                        networkOptions = SyncNetworkOptions(),
                        initialClockOffsetMillis = snapshot.s3ClockOffsetMillis,
                        clockOffsetUpdater = { offset ->
                            try {
                                syncCredentialsStore.saveS3ClockOffsetForSnapshot(snapshot, offset)
                            } catch (failure: Exception) {
                                debugLog.warn(
                                    SYNC_LOG_TAG,
                                    "S3 时钟偏移持久化失败: ${failure.javaClass.simpleName}"
                                )
                            }
                        }
                    )
                    ownershipTransferred = true
                    ResolvedProviderTarget(provider, target)
                } finally {
                    if (!ownershipTransferred) {
                        accessKeyClone.fill('0')
                        secretKeyClone?.fill('0')
                    }
                }
            } finally {
                cfg.accessKey.fill('0')
                cfg.secretKey.fill('0')
            }
        }
        }
    }

    /**
     * 在单一拥有式作用域内使用 Provider。
     *
     * 测试覆写是借用依赖，作用域不会释放它；生产 Provider 从构造成功起归本作用域所有，
     * S3 凭据无论正常返回、提前返回、抛异常或协程取消都会在 `finally` 中清零。
     */
    suspend fun <T> useProvider(
        testOverride: SyncProvider? = null,
        block: suspend (SyncProvider) -> T
    ): T? {
        if (testOverride != null) return block(testOverride)

        var providerBuiltBeforeDispatch: SyncProvider? = null
        val provider = try {
            withContext(Dispatchers.IO) {
                resolveProvider().also { providerBuiltBeforeDispatch = it }
            }
        } catch (failure: Throwable) {
            // withContext may observe cancellation after construction but before returning its value.
            providerBuiltBeforeDispatch?.let(providerFactory::releaseOwned)
            throw failure
        } ?: return null
        try {
            return block(provider)
        } finally {
            providerFactory.releaseOwned(provider)
        }
    }

    /** 使用保存回执构造独立 Provider；生产 Provider 在作用域退出时照常释放。 */
    internal suspend fun <T> useProviderAtSnapshot(
        snapshot: SyncProviderConfigSnapshot,
        testOverride: SyncProvider? = null,
        block: suspend (SyncProvider) -> T
    ): T? = useProviderWithTargetAtSnapshot(
        defaultFileName = DEFAULT_REMOTE_FILE_NAME,
        snapshot = snapshot,
        testOverride = testOverride
    ) { provider, _ -> block(provider) }

    /** 同一份回执用于周期目标和 Provider，生产 Provider 仍由本次 use 独占并释放。 */
    internal suspend fun <T> useProviderWithTargetAtSnapshot(
        defaultFileName: String,
        snapshot: SyncProviderConfigSnapshot,
        testOverride: SyncProvider? = null,
        block: suspend (SyncProvider, SyncProviderTarget?) -> T
    ): T? {
        if (testOverride != null) return block(testOverride, null)

        var providerBuiltBeforeDispatch: ResolvedProviderTarget? = null
        val resolved = try {
            withContext(Dispatchers.IO) {
                resolveProviderWithSnapshot(defaultFileName, snapshot)
                    .also { providerBuiltBeforeDispatch = it }
            }
        } catch (failure: Throwable) {
            providerBuiltBeforeDispatch?.provider?.let(providerFactory::releaseOwned)
            throw failure
        } ?: return null
        return try {
            block(resolved.provider, resolved.target)
        } finally {
            providerFactory.releaseOwned(resolved.provider)
        }
    }

    /** 明确流程可按需绑定保存回执；null 分支保持既有普通同步解析语义。 */
    internal suspend fun <T> useProviderWithTargetForConfig(
        defaultFileName: String,
        testOverride: SyncProvider?,
        snapshot: SyncProviderConfigSnapshot?,
        block: suspend (SyncProvider, SyncProviderTarget?) -> T
    ): T? = if (snapshot == null) {
        useProviderWithTarget(defaultFileName, testOverride, block)
    } else {
        useProviderWithTargetAtSnapshot(defaultFileName, snapshot, testOverride, block)
    }

    /**
     * 使用 Provider 时同时返回由同一份解封配置快照计算的目标描述。
     * 这样设置变更不会让本地检查点绑定到与实际请求不同的端点或路径。
     */
    internal suspend fun <T> useProviderWithTarget(
        defaultFileName: String,
        testOverride: SyncProvider? = null,
        block: suspend (SyncProvider, SyncProviderTarget?) -> T
    ): T? {
        if (testOverride != null) return block(testOverride, null)

        var providerBuiltBeforeDispatch: ResolvedProviderTarget? = null
        val resolved = try {
            withContext(Dispatchers.IO) {
                resolveProviderWithTarget(defaultFileName).also { providerBuiltBeforeDispatch = it }
            }
        } catch (failure: Throwable) {
            providerBuiltBeforeDispatch?.provider?.let(providerFactory::releaseOwned)
            throw failure
        } ?: return null
        try {
            return block(resolved.provider, resolved.target)
        } finally {
            providerFactory.releaseOwned(resolved.provider)
        }
    }

    /** 只读非秘密目标描述；不会解封凭据或构造 Provider。 */
    fun configuredTarget(defaultFileName: String): SyncProviderTarget? =
        syncCredentialsStore.describeCurrentProvider(defaultFileName)

    /** 云同步配置存在性检查不构造并丢弃 Provider。 */
    fun hasConfiguredProvider(): Boolean =
        configuredTarget(DEFAULT_REMOTE_FILE_NAME) != null

    /**
     * 解析远端库路径。
     *
     * ISSUE-P3-100（审计 L3）：本方法只需 `remotePath` / `objectKey`，但 `loadWebDavConfig()` /
     * `loadS3Config()` 会**解密**出完整凭据（WebDAV 口令、S3 AK/SK）。此前这些派生敏态数据
     * 被直接丢弃、从不清零（对照 [resolveProvider] 已在 finally 中收口）。
     * 现按同一口径收口：用毕立即擦除解密得到的凭据。
     */
    fun resolveRemotePath(defaultFileName: String): String {
        return when (syncCredentialsStore.loadProvider()) {
            CloudSyncProvider.WEBDAV -> {
                val cfg = syncCredentialsStore.loadWebDavConfig()
                try {
                    val path = cfg?.remotePath?.trim()
                    if (!path.isNullOrBlank()) {
                        if (path.startsWith("/")) path else "/$path"
                    } else {
                        "/$defaultFileName"
                    }
                } finally {
                    cfg?.password?.fill('0')
                }
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val cfg = syncCredentialsStore.loadS3Config()
                try {
                    cfg?.objectKey?.trim()?.ifBlank { defaultFileName } ?: defaultFileName
                } finally {
                    cfg?.accessKey?.fill('0')
                    cfg?.secretKey?.fill('0')
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_REMOTE_FILE_NAME = "keepasskey.kdbx"
    }

    private data class ResolvedProviderTarget(
        val provider: SyncProvider,
        val target: SyncProviderTarget
    )
}
