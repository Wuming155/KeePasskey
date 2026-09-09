package com.keepasskey.sync.engine

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * 缓存监督事件。
 * 对齐 keepass2android ICacheSupervisor 六事件。
 */
sealed class SyncCacheEvent {
    /** 打开/同步时因远端更新而刷新了本地缓存 */
    data class UpdatedCachedFileOnLoad(val remotePath: String) : SyncCacheEvent()

    /** 打开/同步时因本地修改无冲突而自动上传更新了远端 */
    data class UpdatedRemoteFileOnLoad(val remotePath: String) : SyncCacheEvent()

    /** 冲突发生：保留本地缓存以供查看，推迟合并 */
    data class OpenedFromLocalDueToConflict(val remotePath: String) : SyncCacheEvent()

    /** 远端与本地哈希完全一致，同步加载完成 */
    data class LoadedFromRemoteInSync(val remotePath: String) : SyncCacheEvent()

    /** 保存至远端失败（本地缓存已安全保留） */
    data class CouldntSaveToRemote(val remotePath: String, val cause: Throwable?) : SyncCacheEvent()

    /** 打开远端失败（网络异常回退读本地缓存） */
    data class CouldntOpenFromRemote(val remotePath: String, val cause: Throwable?) : SyncCacheEvent()
}

/**
 * openRemote 决策状态机结果。
 */
sealed class SyncOpenResult {
    /** 成功与远端一致（下载刷新或直接匹配） */
    data class RemoteSynced(val remoteBytes: ByteArray, val etag: String) : SyncOpenResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RemoteSynced
            return remoteBytes.contentEquals(other.remoteBytes) && etag == other.etag
        }
        override fun hashCode(): Int = 31 * remoteBytes.contentHashCode() + etag.hashCode()
    }

    /** 双方均修改冲突检测 */
    data class ConflictDetected(
        val localBytes: ByteArray,
        val remoteBytes: ByteArray,
        val remoteEtag: String
    ) : SyncOpenResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ConflictDetected
            return localBytes.contentEquals(other.localBytes) &&
                    remoteBytes.contentEquals(other.remoteBytes) &&
                    remoteEtag == other.remoteEtag
        }
        override fun hashCode(): Int {
            var result = localBytes.contentHashCode()
            result = 31 * result + remoteBytes.contentHashCode()
            result = 31 * result + remoteEtag.hashCode()
            return result
        }
    }

    /** 本地有修改且远端未变，本地赢并自动完成上传 */
    data class LocalWinAutoUploaded(val etag: String) : SyncOpenResult()

    /** 远端 404 丢失，由本地缓存自愈恢复 */
    data class RemoteLostRestored(val etag: String) : SyncOpenResult()

    /** 离线模式命中本地缓存 */
    data class CacheHitOffline(val localBytes: ByteArray) : SyncOpenResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CacheHitOffline
            return localBytes.contentEquals(other.localBytes)
        }
        override fun hashCode(): Int = localBytes.contentHashCode()
    }

    /** 远端不可达，降级读取本地缓存 */
    data class RemoteUnreachableUsingCache(val localBytes: ByteArray) : SyncOpenResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RemoteUnreachableUsingCache
            return localBytes.contentEquals(other.localBytes)
        }
        override fun hashCode(): Int = localBytes.contentHashCode()
    }
}

/**
 * commitLocal 提交写结果。
 */
sealed class SyncCommitResult {
    /** 成功保存并推送至远端 */
    data class Uploaded(val newEtag: String) : SyncCommitResult()

    /** 远端已被修改并发冲突，需要执行三方合并 */
    data class ConflictNeedsMerge(val remoteBytes: ByteArray, val remoteEtag: String) : SyncCommitResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ConflictNeedsMerge
            return remoteBytes.contentEquals(other.remoteBytes) && remoteEtag == other.remoteEtag
        }
        override fun hashCode(): Int = 31 * remoteBytes.contentHashCode() + remoteEtag.hashCode()
    }

    /** 远端不可达，已将变更安全保存在本地缓存 */
    data class RemoteUnreachable(val keptLocal: Boolean) : SyncCommitResult()
}

/**
 * 纯字节级三哈希同步状态机引擎。
 * 遵循 Kp2a CachingFileStorage 决策树：
 * 1. 优先保证本地数据安全（本地写盘绝对不丢）；
 * 2. 区分无修改、本地赢（自动上传）、远端改（下载刷新）、冲突与远端丢失自愈；
 * 3. 零 Android UI / 零 Database 模块依赖，纯 Kotlin 协程实现。
 */
class SyncEngine(
    private val provider: SyncProvider,
    private val cache: SyncCache
) {
    val events = MutableSharedFlow<SyncCacheEvent>(replay = 16, extraBufferCapacity = 64)

    /**
     * 离线模式开关。开启后直接从缓存读取，不触碰网络。
     * @Volatile 提供单布尔标志的跨协程可见性保证（官方并发最佳实践：
     * 单变量可见性用 @Volatile，代码块互斥用 Mutex——调用方 SyncCoordinator 已用
     * mutex 串行化同步周期，此处仅补齐可见性防御）。
     */
    @Volatile
    var isOffline: Boolean = false

    /**
     * 打开远程数据库决策树。
     *
     * 1. 未缓存 -> 下载 + 写缓存 + 基线设为远端 ETag -> [SyncOpenResult.RemoteSynced]
     * 2. 已缓存且本地 == base（无修改）-> 远端无变直接返回 / 远端修改则下载刷新 -> [SyncOpenResult.RemoteSynced]
     * 3. 本地有修改且 base == 远端 ETag -> 自动上传基线前移 -> [SyncOpenResult.LocalWinAutoUploaded]
     * 4. 本地有修改且 base != 远端 -> [SyncOpenResult.ConflictDetected]
     * 5. 远端 404 且有缓存 -> 上传恢复 -> [SyncOpenResult.RemoteLostRestored]
     * 6. 网络错误且有缓存 -> [SyncOpenResult.RemoteUnreachableUsingCache]
     *
     * 远端一致性双通道裁决：ETag 可用时按乐观锁比对（零下载）；
     * 无 ETag 服务器（部分极简 WebDAV 不返回 ETag）回退内容哈希裁决——
     * 下载一次与 baseversion 记录的 SHA-256 比对，避免 etag 缺失导致
     * "每次同步都误判远端更新/冲突"的退化循环。
     */
    suspend fun openRemote(remotePath: String): SyncOpenResult = withContext(Dispatchers.IO) {
        if (isOffline) {
            val cached = cache.readCache(remotePath)
                ?: throw SyncException.FileNotFound("离线模式下未找到本地缓存: $remotePath")
            return@withContext SyncOpenResult.CacheHitOffline(cached)
        }

        val isCached = cache.isCached(remotePath)

        if (!isCached) {
            // 未缓存：从远端下载
            val metaResult = provider.getMetadata(remotePath)
            if (metaResult.isFailure) {
                val ex = metaResult.exceptionOrNull()
                if (ex is SyncException.FileNotFound) {
                    throw ex
                }
                events.tryEmit(SyncCacheEvent.CouldntOpenFromRemote(remotePath, ex))
                throw ex ?: SyncException.NetworkError("无法连接远端服务器")
            }

            val meta = metaResult.getOrThrow()
            if (meta.isDirectory) {
                // 路径误指远端目录（如 PROPFIND 命中 collection）时 GET 将返回 HTML 目录列表，
                // 直接落库会产生脏缓存并污染三哈希基线，fail-fast 终止
                throw SyncException.ProtocolError(400, "远程路径指向目录而非数据库文件: $remotePath")
            }
            val downloadResult = provider.download(remotePath)
            val remoteBytes = downloadResult.getOrElse { ex ->
                events.tryEmit(SyncCacheEvent.CouldntOpenFromRemote(remotePath, ex))
                throw ex
            }

            val hash = cache.writeCache(remotePath, remoteBytes)
            cache.updateBase(remotePath, hash, meta.etag)
            cache.writeBaseContent(remotePath, remoteBytes)
            events.tryEmit(SyncCacheEvent.LoadedFromRemoteInSync(remotePath))
            return@withContext SyncOpenResult.RemoteSynced(remoteBytes, meta.etag)
        }

        // 已缓存。读取失败绝不以空字节数组继续——空数组一旦命中「本地赢自动上传」
        // 路径会把远端全库覆盖为空（数据丢失级故障），必须 fail-fast 终止本次同步
        val cachedBytes = cache.readCache(remotePath)
            ?: throw SyncException.CacheCorruptedError("本地同步缓存读取失败: $remotePath")
        val state = cache.getState(remotePath)
        val baseEtag = cleanEtag(state?.etag)
        val baseVersionHash = state?.baseVersion.orEmpty().trim()

        val metaResult = provider.getMetadata(remotePath)
        if (metaResult.isFailure) {
            val ex = metaResult.exceptionOrNull()
            when (ex) {
                is SyncException.FileNotFound -> {
                    // 远端 404 且有缓存 -> 上传恢复远端
                    val uploadResult = provider.uploadAtomic(remotePath, cachedBytes, expectedEtag = null)
                    val newEtag = uploadResult.getOrThrow()
                    val localHash = state?.localVersion ?: SyncCache.sha256Hex(cachedBytes)
                    cache.updateBase(remotePath, localHash, newEtag)
                    cache.writeBaseContent(remotePath, cachedBytes)
                    events.tryEmit(SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath))
                    return@withContext SyncOpenResult.RemoteLostRestored(newEtag)
                }
                else -> {
                    // 网络不可达或服务器错误，降级读取缓存
                    events.tryEmit(SyncCacheEvent.CouldntOpenFromRemote(remotePath, ex))
                    return@withContext SyncOpenResult.RemoteUnreachableUsingCache(cachedBytes)
                }
            }
        }

        val remoteMeta = metaResult.getOrThrow()
        val remoteEtag = cleanEtag(remoteMeta.etag)
        val localHasChanges = cache.hasLocalChanges(remotePath)

        var downloadedBytes: ByteArray? = null
        suspend fun isRemoteUnchanged(): Boolean {
            if (baseEtag.isNotEmpty() && remoteEtag.isNotEmpty()) {
                return baseEtag == remoteEtag
            }
            // ETag 双端任一缺失：回退内容哈希裁决
            if (baseVersionHash.isEmpty()) return false
            val bytes = downloadedBytes
                ?: provider.download(remotePath).getOrNull()?.also { downloadedBytes = it }
                ?: return false
            return SyncCache.sha256Hex(bytes) == baseVersionHash
        }

        if (!localHasChanges) {
            // 本地无修改
            if (isRemoteUnchanged()) {
                if (downloadedBytes != null) {
                    // 内容哈希裁决命中：内容一致，仅刷新元数据，无需重复写缓存
                    cache.updateBase(remotePath, baseVersionHash, remoteEtag.ifEmpty { baseEtag })
                }
                events.tryEmit(SyncCacheEvent.LoadedFromRemoteInSync(remotePath))
                SyncOpenResult.RemoteSynced(cachedBytes, remoteEtag.ifEmpty { baseEtag })
            } else {
                // 远端有更新，拉取刷新
                val remoteBytes = downloadedBytes ?: provider.download(remotePath).getOrThrow()
                val newHash = cache.writeCache(remotePath, remoteBytes)
                cache.updateBase(remotePath, newHash, remoteEtag)
                cache.writeBaseContent(remotePath, remoteBytes)
                events.tryEmit(SyncCacheEvent.UpdatedCachedFileOnLoad(remotePath))
                SyncOpenResult.RemoteSynced(remoteBytes, remoteEtag)
            }
        } else {
            // 本地有修改
            if (isRemoteUnchanged()) {
                // 本地有修改且远端未变 -> 本地赢，自动上传并基线前移
                val uploadResult = provider.uploadAtomic(remotePath, cachedBytes, expectedEtag = baseEtag.ifEmpty { null })
                if (uploadResult.isSuccess) {
                    val newEtag = uploadResult.getOrThrow()
                    val localHash = state?.localVersion ?: SyncCache.sha256Hex(cachedBytes)
                    cache.updateBase(remotePath, localHash, newEtag)
                    cache.writeBaseContent(remotePath, cachedBytes)
                    events.tryEmit(SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath))
                    SyncOpenResult.LocalWinAutoUploaded(newEtag)
                } else {
                    val uploadEx = uploadResult.exceptionOrNull()
                    if (uploadEx is SyncException.ConflictError) {
                        val remoteBytes = downloadedBytes ?: provider.download(remotePath).getOrThrow()
                        events.tryEmit(SyncCacheEvent.OpenedFromLocalDueToConflict(remotePath))
                        SyncOpenResult.ConflictDetected(
                            cachedBytes,
                            remoteBytes,
                            uploadEx.remoteEtag.ifEmpty { remoteEtag }
                        )
                    } else {
                        events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, uploadEx))
                        SyncOpenResult.RemoteUnreachableUsingCache(cachedBytes)
                    }
                }
            } else {
                // 本地有修改且远端也有修改 -> 双方冲突
                val remoteBytes = downloadedBytes ?: provider.download(remotePath).getOrThrow()
                events.tryEmit(SyncCacheEvent.OpenedFromLocalDueToConflict(remotePath))
                SyncOpenResult.ConflictDetected(cachedBytes, remoteBytes, remoteEtag)
            }
        }
    }

    /**
     * 提交本地修改。
     * 先写本地缓存（本地数据安全第一），再尽力上传；
     * 上传遭遇 412/ETag 不匹配则下载远端内容并返回 [SyncCommitResult.ConflictNeedsMerge]；
     * 冲突后下载远端内容失败时，严禁以空字节伪造冲突远端——本地缓存已安全保留，
     * 如实返回 [SyncCommitResult.RemoteUnreachable]，待网络恢复后重新同步触发完整冲突流程；
     * 普通网络失败同样保留本地缓存并返回 [SyncCommitResult.RemoteUnreachable]。
     */
    suspend fun commitLocal(remotePath: String, localBytes: ByteArray): SyncCommitResult = withContext(Dispatchers.IO) {
        // 1. 先写缓存
        val localHash = cache.writeCache(remotePath, localBytes)
        val state = cache.getState(remotePath)
        val expectedEtag = state?.etag

        if (isOffline) {
            return@withContext SyncCommitResult.RemoteUnreachable(keptLocal = true)
        }

        // 2. 尽力上传
        val uploadResult = provider.uploadAtomic(remotePath, localBytes, expectedEtag)
        if (uploadResult.isSuccess) {
            val newEtag = uploadResult.getOrThrow()
            cache.updateBase(remotePath, localHash, newEtag)
            cache.writeBaseContent(remotePath, localBytes)
            SyncCommitResult.Uploaded(newEtag)
        } else {
            val ex = uploadResult.exceptionOrNull()
            if (ex is SyncException.ConflictError) {
                val downloadResult = provider.download(remotePath)
                val remoteBytes = downloadResult.getOrNull()
                if (remoteBytes != null) {
                    SyncCommitResult.ConflictNeedsMerge(remoteBytes, ex.remoteEtag)
                } else {
                    // 远端已确认冲突但拉取远端内容失败：严禁以 ByteArray(0) 伪造空冲突远端
                    // ——空字节会被当作合法远端版本参与三方合并，导致远端全部内容被静默丢弃。
                    // 本地缓存已在步骤 1 安全保留，如实返回远端不可达，
                    // 待网络恢复后重新同步走完整的冲突检测与合并流程。
                    val downloadEx = downloadResult.exceptionOrNull()
                    events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, downloadEx ?: ex))
                    SyncCommitResult.RemoteUnreachable(keptLocal = true)
                }
            } else {
                events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, ex))
                SyncCommitResult.RemoteUnreachable(keptLocal = true)
            }
        }
    }

    /**
     * 在冲突合并解决完成后提交最终数据，并将基准版本前移。
     *
     * @param expectedEtag 冲突发生时刻记录的远端 ETag。必须使用该值做 If-Match 乐观锁，
     *   而非重新探测的当前 ETag——用户决策期间远端可能再次被修改，
     *   用当前值会通过校验并静默覆盖他端的更新；用冲突时刻值则 412 暴露新冲突。
     *   传空（如无 ETag 服务器）时回退为探测当前远端元数据。
     *
     * 写序约定（先上传后落缓存）：上传失败时缓存与基线保持原状
     * （本地未同步修改仍在，下次同步自动重试），避免"缓存已含合并结果但
     * 基线未动、内存会话未更新"的三处不一致状态。
     */
    suspend fun markResolvedAndUpload(
        remotePath: String,
        mergedBytes: ByteArray,
        expectedEtag: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val finalExpected = expectedEtag?.takeIf { it.isNotBlank() }
                ?: cleanEtag(provider.getMetadata(remotePath).getOrNull()?.etag)
            val newEtag = provider.uploadAtomic(remotePath, mergedBytes, expectedEtag = finalExpected)
                .getOrThrow()
            val localHash = cache.writeCache(remotePath, mergedBytes)
            cache.updateBase(remotePath, localHash, newEtag)
            cache.writeBaseContent(remotePath, mergedBytes)
            newEtag
        }
    }
}
