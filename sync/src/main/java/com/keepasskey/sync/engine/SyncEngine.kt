package com.keepasskey.sync.engine

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * 纯字节级三哈希同步状态机引擎。
 * 遵循 Kp2a CachingFileStorage 决策树：
 * 1. 优先保证本地数据安全（本地写盘绝对不丢）；
 * 2. 区分无修改、本地赢（自动上传）、远端改（下载刷新）、冲突与远端丢失自愈；
 * 3. 零 Android UI / 零 Database 模块依赖，纯 Kotlin 协程实现。
 */
class SyncEngine(
    private val provider: SyncProvider,
    /**
     * 三哈希缓存。`ISSUE-P2-308` 起由 `private` 放宽为 `internal`（仅同模块可见）：
     * 延迟落地结算句柄 [DeferredBaselineSettlement]（同包协作类）须在其 accept() 内
     * 前移基线——口径同 `SyncCycleRunner` / `SyncConflictController` 的既有放宽先例。
     */
    internal val cache: SyncCache,
    /**
     * 防回滚守卫（ISSUE-P2-18）：由 app 层以 AndroidKeystore HMAC 认证实现注入；
     * 默认 null 表示未接线（行为与接线前逐字节一致，既有单测不受影响）。
     * `ISSUE-P2-308` 起由 `private` 放宽为 `internal`（同上，供结算句柄记录高水位）。
     */
    internal val rollbackGuard: SyncRollbackGuard? = null
) {
    val events = MutableSharedFlow<SyncCacheEvent>(replay = 16, extraBufferCapacity = 64)

    /** 远端内容是否为设备侧曾接受过的历史版本（回退 / 重放） */
    /**
     * 是否命中「曾接受过的历史版本」（防回滚裁决）。
     *
     * `ISSUE-P3-167`：拆成「字节」与「摘要」两个重载——接受远端内容的路径上，同一份字节的
     * SHA-256 会被回滚裁决、缓存写入与高水位记录共用，由调用方一次算出并贯穿
     * （原实现三处各算一遍全库 SHA-256）。
     */
    private fun isReplay(remotePath: String, bytes: ByteArray): Boolean =
        isReplay(remotePath, SyncCache.sha256Hex(bytes))

    /** 同上，摘要由调用方提供（须为内容的 SHA-256 十六进制小写摘要）。 */
    private fun isReplay(remotePath: String, contentDigest: String): Boolean =
        rollbackGuard?.inspect(remotePath, contentDigest) == RollbackVerdict.ReplayDetected

    /** 记录一份已被设备接受的内容（前移防回滚高水位） */
    private fun recordAccepted(remotePath: String, bytes: ByteArray) {
        recordAccepted(remotePath, SyncCache.sha256Hex(bytes))
    }

    /**
     * 同上，摘要由调用方提供（须为内容的 SHA-256 十六进制小写摘要）。
     * `ISSUE-P2-308` 起由 `private` 放宽为 `internal`：延迟落地结算句柄（同包协作类）
     * 在采纳确认后调用，单一实现不复制。
     */
    internal fun recordAccepted(remotePath: String, contentDigest: String) {
        rollbackGuard?.recordAccepted(remotePath, contentDigest)
    }

    /**
     * 离线模式开关。开启后直接从缓存读取，不触碰网络。
     * @Volatile 提供单布尔标志的跨协程可见性保证（官方并发最佳实践：
     * 单变量可见性用 @Volatile，代码块互斥用 Mutex——调用方 SyncCoordinator 已用
     * mutex 串行化同步周期，此处仅补齐可见性防御）。
     */
    @Volatile
    var isOffline: Boolean = false

    /**
     * 「上传前不比对云端版本」开关（ISSUE-P3-03 43a：`checkRemoteChangesBeforeSave = false`）。
     *
     * 开启后，本地存在修改时**跳过远端一致性裁决与 ETag 预条件**，直接以本地内容覆盖远端，
     * 语义退化为最后写入者胜——这正是用户关闭「同步前检查远程变更」时所表达的意图。
     * 默认关闭：接线前的行为（乐观锁 + 冲突检测）保持不变。
     *
     * 与 [isOffline] 同构的单布尔跨协程可见性声明（调用方 SyncCoordinator 已用 mutex
     * 串行化同步周期，此处仅补齐可见性防御）。
     */
    @Volatile
    var overwriteRemoteWithoutPrecondition: Boolean = false

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
     * ISSUE-P2-308：分支 1 / 2 的「基线三步」（缓存交付 / updateBase / writeBaseContent /
     * recordAccepted）**不在本方法内落地**，随 [SyncOpenResult.RemoteSynced.adoption] 延后到
     * app 层采纳确认之后结算；分支 3 / 5 上传的是本地既有内容、无采纳窗口，基线照常即时前移。
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

        if (!cache.isCached(remotePath)) {
            return@withContext openUncached(remotePath)
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
            return@withContext recoverFromMetaFailure(
                remotePath, cachedBytes, state?.localVersion,
                metaResult.exceptionOrNull()
            )
        }

        val remoteMeta = metaResult.getOrThrow()
        val remoteEtag = cleanEtag(remoteMeta.etag)
        val remoteProbe = RemoteConsistencyProbe(
            provider = provider,
            cache = cache,
            remotePath = remotePath,
            baseEtag = baseEtag,
            remoteEtag = remoteEtag,
            baseVersionHash = baseVersionHash
        )

        if (!cache.hasLocalChanges(remotePath)) {
            openCachedWithoutLocalChanges(remotePath, cachedBytes, baseEtag, baseVersionHash, remoteProbe, remoteEtag)
        } else {
            openCachedWithLocalChanges(remotePath, cachedBytes, state?.localVersion, baseEtag, remoteProbe, remoteEtag)
        }
    }

    /** [openRemote] 分支 1：未缓存 -> 下载 + 写缓存 + 基线设为远端 ETag。 */
    private suspend fun openUncached(remotePath: String): SyncOpenResult {
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
        // ISSUE-P3-206：下载体流式接收进缓存 tmp（下载期不在堆上整份物化），摘要接收期同步累计
        val receipt = try {
            cache.receiveRemote(remotePath) { sink ->
                provider.download(remotePath, sink).getOrThrow()
            }
        } catch (ex: Throwable) {
            events.tryEmit(SyncCacheEvent.CouldntOpenFromRemote(remotePath, ex))
            throw ex
        }

        // ISSUE-P2-18：若远端返回设备侧曾接受过的历史版本（回退/重放），拒绝写入缓存与基线
        // ISSUE-P3-167：摘要由接收期一次算出并全程贯穿，不再对整库重算
        if (isReplay(remotePath, receipt.digest)) {
            val remoteBytes = receipt.readBytes()
            receipt.abort()
            return SyncOpenResult.RollbackRejected(remoteBytes, meta.etag)
        }

        val remoteBytes = receipt.readBytes()
        // ISSUE-P2-308：基线三步延后——回执 tmp 暂不交付、基线不前移、高水位不记录；
        // app 层采纳确认后经结算句柄 accept() 落地，采纳失败 reject() 弃置 tmp，
        // 下轮同步重新下载重试（重试不构成重放：digest 尚未进入已接受历史）
        return SyncOpenResult.RemoteSynced(
            remoteBytes,
            meta.etag,
            DeferredBaselineSettlement(
                this@SyncEngine, remotePath, receipt, remoteBytes, receipt.digest, meta.etag,
                SyncCacheEvent.LoadedFromRemoteInSync(remotePath)
            )
        )
    }

    /**
     * [openRemote] 元数据获取失败分支：远端 404 且有缓存 -> 上传恢复；
     * 其余（网络不可达 / 服务器错误）-> 降级读取缓存。
     */
    private suspend fun recoverFromMetaFailure(
        remotePath: String,
        cachedBytes: ByteArray,
        localVersion: String?,
        ex: Throwable?
    ): SyncOpenResult = when (ex) {
        is SyncException.FileNotFound -> {
            // 远端 404 且有缓存 -> 上传恢复远端
            val uploadResult = provider.uploadAtomic(remotePath, cachedBytes, expectedEtag = null)
            val newEtag = uploadResult.getOrThrow()
            advanceBaseAndPersist(cache, remotePath, newEtag, localVersion, cachedBytes)
            recordAccepted(remotePath, cachedBytes)
            events.tryEmit(SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath))
            SyncOpenResult.RemoteLostRestored(newEtag)
        }
        else -> {
            // 网络不可达或服务器错误，降级读取缓存
            events.tryEmit(SyncCacheEvent.CouldntOpenFromRemote(remotePath, ex))
            SyncOpenResult.RemoteUnreachableUsingCache(cachedBytes)
        }
    }

    /** [openRemote] 分支 2：已缓存且本地无修改 -> 远端无变直接返回 / 远端修改则下载刷新。 */
    private suspend fun openCachedWithoutLocalChanges(
        remotePath: String,
        cachedBytes: ByteArray,
        baseEtag: String,
        baseVersionHash: String,
        remoteProbe: RemoteConsistencyProbe,
        remoteEtag: String
    ): SyncOpenResult {
        if (remoteProbe.isRemoteUnchanged()) {
            if (remoteProbe.hasDownloaded) {
                // 内容哈希裁决命中：内容一致，仅刷新元数据，无需重复写缓存；
                // 探测用的接收回执与 base 内容一致，tmp 用后即弃（ISSUE-P3-206）
                remoteProbe.received().abort()
                cache.updateBase(remotePath, baseVersionHash, remoteEtag.ifEmpty { baseEtag })
            }
            events.tryEmit(SyncCacheEvent.LoadedFromRemoteInSync(remotePath))
            return SyncOpenResult.RemoteSynced(cachedBytes, remoteEtag.ifEmpty { baseEtag })
        }
        // 远端有更新，拉取刷新（回执已在探测期接收，决策树内复用不重复下载）
        val receipt = remoteProbe.received()
        // ISSUE-P2-18：重放的历史版本拒绝落地
        if (isReplay(remotePath, receipt.digest)) {
            val remoteBytes = receipt.readBytes()
            receipt.abort()
            return SyncOpenResult.RollbackRejected(remoteBytes, remoteEtag)
        }
        val remoteBytes = receipt.readBytes()
        // ISSUE-P2-308：基线三步延后（同 openUncached）——此前 base 在「app 采纳」之前即已
        // 前移并写盘，采纳失败（PARSE_FAILED / SAVE_FAILED / SESSION_DIVERGED）后引擎无回滚，
        // 下轮同步将以本地陈旧树整库上传覆盖他端改动
        return SyncOpenResult.RemoteSynced(
            remoteBytes,
            remoteEtag,
            DeferredBaselineSettlement(
                this@SyncEngine, remotePath, receipt, remoteBytes, receipt.digest, remoteEtag,
                SyncCacheEvent.UpdatedCachedFileOnLoad(remotePath)
            )
        )
    }

    /**
     * [openRemote] 分支 3/4/5：已缓存且本地有修改 -> 远端未变则本地赢自动上传；
     * 远端已变（或强覆盖开关开启、412 预条件失败）则按冲突/降级路径处置。
     */
    private suspend fun openCachedWithLocalChanges(
        remotePath: String,
        cachedBytes: ByteArray,
        localVersion: String?,
        baseEtag: String,
        remoteProbe: RemoteConsistencyProbe,
        remoteEtag: String
    ): SyncOpenResult {
        if (overwriteRemoteWithoutPrecondition) {
            // ISSUE-P3-03 43a：用户关闭「上传前比对云端版本」→ 不做远端一致性裁决、
            // 不带 ETag 预条件，本地内容直接覆盖远端（最后写入者胜）。
            // 缓存已保存本地内容，上传失败时本地修改仍安全保留在缓存中。
            val forcedUpload = provider.uploadAtomic(remotePath, cachedBytes, expectedEtag = null)
            if (forcedUpload.isSuccess) {
                val newEtag = forcedUpload.getOrThrow()
                advanceBaseAndPersist(cache, remotePath, newEtag, localVersion, cachedBytes)
                recordAccepted(remotePath, cachedBytes)
                events.tryEmit(SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath))
                return SyncOpenResult.LocalWinAutoUploaded(newEtag)
            }
            val forcedEx = forcedUpload.exceptionOrNull()
            events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, forcedEx))
            return SyncOpenResult.RemoteUnreachableUsingCache(cachedBytes)
        }
        if (!remoteProbe.isRemoteUnchanged()) {
            // 本地有修改且远端也有修改 -> 双方冲突（远端内容仅供三方合并，缓存保留本地工作副本）
            val receipt = remoteProbe.received()
            // ISSUE-P2-18：重放的历史版本不参与三方合并
            if (isReplay(remotePath, receipt.digest)) {
                val remoteBytes = receipt.readBytes()
                receipt.abort()
                return SyncOpenResult.RollbackRejected(remoteBytes, remoteEtag)
            }
            val remoteBytes = receipt.readBytes()
            // 冲突路径不落地：远端内容不写缓存（保留本地），接收 tmp 用后即弃（ISSUE-P3-206）
            receipt.abort()
            events.tryEmit(SyncCacheEvent.OpenedFromLocalDueToConflict(remotePath))
            return SyncOpenResult.ConflictDetected(cachedBytes, remoteBytes, remoteEtag)
        }
        // 本地有修改且远端未变 -> 本地赢，自动上传并基线前移
        val uploadResult = provider.uploadAtomic(remotePath, cachedBytes, expectedEtag = baseEtag.ifEmpty { null })
        if (uploadResult.isSuccess) {
            val newEtag = uploadResult.getOrThrow()
            advanceBaseAndPersist(cache, remotePath, newEtag, localVersion, cachedBytes)
            recordAccepted(remotePath, cachedBytes)
            events.tryEmit(SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath))
            return SyncOpenResult.LocalWinAutoUploaded(newEtag)
        }
        val uploadEx = uploadResult.exceptionOrNull()
        if (uploadEx is SyncException.ConflictError) {
            // 412 预条件失败：远端冲突内容已在探测期流式接收，读出后按冲突处置（不落地缓存）
            val receipt = remoteProbe.received()
            // ISSUE-P2-18：重放的历史版本不参与三方合并
            if (isReplay(remotePath, receipt.digest)) {
                val remoteBytes = receipt.readBytes()
                receipt.abort()
                return SyncOpenResult.RollbackRejected(
                    remoteBytes,
                    uploadEx.remoteEtag.ifEmpty { remoteEtag }
                )
            }
            val remoteBytes = receipt.readBytes()
            // 冲突路径不落地：远端内容不写缓存（保留本地），接收 tmp 用后即弃（ISSUE-P3-206）
            receipt.abort()
            events.tryEmit(SyncCacheEvent.OpenedFromLocalDueToConflict(remotePath))
            return SyncOpenResult.ConflictDetected(
                cachedBytes,
                remoteBytes,
                uploadEx.remoteEtag.ifEmpty { remoteEtag }
            )
        }
        events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, uploadEx))
        return SyncOpenResult.RemoteUnreachableUsingCache(cachedBytes)
    }

    /**
     * 提交本地修改。
     * 先写本地缓存（本地数据安全第一），再尽力上传；
     * 上传遭遇 412/ETag 不匹配则下载远端内容并返回 [SyncCommitResult.ConflictNeedsMerge]；
     * 冲突后下载远端内容失败时，严禁以空字节伪造冲突远端——本地缓存已安全保留，
     * 如实返回 [SyncCommitResult.RemoteUnreachable]，待网络恢复后重新同步触发完整冲突流程；
     * 普通网络失败同样保留本地缓存并返回 [SyncCommitResult.RemoteUnreachable]。
     */    suspend fun commitLocal(
        remotePath: String,
        localBytes: ByteArray,
        remoteExists: Boolean? = null
    ): SyncCommitResult = withContext(Dispatchers.IO) {
        // 1. 先写缓存
        val localHash = cache.writeCache(remotePath, localBytes)
        val state = cache.getState(remotePath)
        val expectedEtag = state?.etag

        if (isOffline) {
            return@withContext SyncCommitResult.RemoteUnreachable(keptLocal = true)
        }

        // 2. 尽力上传
        // ISSUE-P3-180：把调用方已探明的远端存在性结论下传（首传路径为 false），
        // 避免 Provider 侧再探一次（WebDAV 的 Overwrite 判定）
        val uploadResult = provider.uploadAtomic(remotePath, localBytes, expectedEtag, remoteExists)
        if (uploadResult.isSuccess) {
            val newEtag = uploadResult.getOrThrow()
            // ISSUE-P2-308：基线前移延后到 app 层采纳确认（本地落盘保存成功）之后；
            // 落盘失败 reject() ⇒ 基线保持原状，缓存工作副本（步骤 1）保留本次内容供下轮重试
            SyncCommitResult.Uploaded(
                newEtag,
                DeferredBaselineSettlement(
                    this@SyncEngine, remotePath, null, localBytes, localHash, newEtag, null
                )
            )
        } else {
            val ex = uploadResult.exceptionOrNull()
            if (ex is SyncException.ConflictError) {
                // ISSUE-P3-206：远端冲突内容流式接收（仅供合并，不落地缓存），失败归一为远端不可达
                var downloadFailure: Throwable? = null
                val receipt = try {
                    cache.receiveRemote(remotePath) { sink ->
                        provider.download(remotePath, sink).getOrThrow()
                    }
                } catch (t: Throwable) {
                    downloadFailure = t
                    null
                }
                if (receipt != null) {
                    // ISSUE-P2-18：远端冲突内容若为设备侧曾接受过的历史版本（回退/重放），拒绝合并
                    if (isReplay(remotePath, receipt.digest)) {
                        receipt.abort()
                        SyncCommitResult.RollbackRejected(keptLocal = true)
                    } else {
                        val remoteBytes = receipt.readBytes()
                        receipt.abort()
                        SyncCommitResult.ConflictNeedsMerge(remoteBytes, ex.remoteEtag)
                    }
                } else {
                    // 远端已确认冲突但拉取远端内容失败：严禁以空字节伪造冲突远端
                    // ——空字节会被当作合法远端版本参与三方合并，导致远端全部内容被静默丢弃。
                    // 本地缓存已在步骤 1 安全保留，如实返回远端不可达，
                    // 待网络恢复后重新同步走完整的冲突检测与合并流程。
                    events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, downloadFailure ?: ex))
                    SyncCommitResult.RemoteUnreachable(keptLocal = true)
                }
            } else {
                events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, ex))
                SyncCommitResult.RemoteUnreachable(keptLocal = true)
            }
        }
    }

    /**
     * 强制提交本地修改（ISSUE-P3-03 43a：`checkRemoteChangesBeforeSave=false` 与
     * `conflictResolution=KEEP_LOCAL` 的真实消费点）。
     *
     * 与 [commitLocal] 的唯一差异：**不带 ETag 乐观锁预条件**（`expectedEtag = null`），
     * 即不做「上传前比对云端版本」——本地版本直接覆盖远端，语义退化为最后写入者胜。
     * 本地缓存仍照常先行写入（本地数据安全第一），离线时同样保留本地并返回
     * [SyncCommitResult.RemoteUnreachable]。
     *
     * 调用方必须已获得用户的显式偏好（这两个开关默认均不开启本路径），
     * 即「用户主动选择放弃云端并发保护」，不是静默降级。
     */
    suspend fun commitLocalForce(
        remotePath: String,
        localBytes: ByteArray
    ): SyncCommitResult = withContext(Dispatchers.IO) {
        // 1. 先写缓存（与 commitLocal 同序：本地数据安全优先）
        val localHash = cache.writeCache(remotePath, localBytes)

        if (isOffline) {
            return@withContext SyncCommitResult.RemoteUnreachable(keptLocal = true)
        }

        // 2. 无预条件上传：远端已有内容被本地整体覆盖
        val uploadResult = provider.uploadAtomic(remotePath, localBytes, expectedEtag = null)
        if (uploadResult.isSuccess) {
            val newEtag = uploadResult.getOrThrow()
            // ISSUE-P2-308：与 commitLocal 同口径——基线前移延后到采纳确认之后
            SyncCommitResult.Uploaded(
                newEtag,
                DeferredBaselineSettlement(
                    this@SyncEngine, remotePath, null, localBytes, localHash, newEtag, null
                )
            )
        } else {
            val ex = uploadResult.exceptionOrNull()
            events.tryEmit(SyncCacheEvent.CouldntSaveToRemote(remotePath, ex))
            SyncCommitResult.RemoteUnreachable(keptLocal = true)
        }
    }

    /**
     * 在冲突合并解决完成后提交最终数据，并**待采纳确认后**前移基准版本。
     *
     * @param expectedEtag 冲突发生时刻记录的远端 ETag。必须使用该值做 If 预条件乐观锁，
     *   而非重新探测的当前 ETag——用户决策期间远端可能再次被修改，
     *   用当前值会通过校验并静默覆盖他端的更新；用冲突时刻值则 412 暴露新冲突。
     *   传 null / 空白是**唯一**显式回退情形：调用方已判定「无基线可校验」（无 ETag 服务器，
     *   冲突时刻本就拿不到 ETag）⇒ 无预条件上传（与 [commitLocalForce] 同语义）。
     *   **禁止**在本方法内回退重探当前值充当预条件（ISSUE-P1-275 AC①：那会撤除合并窗口
     *   ——一次 KDF 级全库序列化 + 一次分钟级网络往返——最后一步的乐观锁）。
     *
     * 写序约定（先上传后落缓存）：上传失败时缓存与基线保持原状
     * （本地未同步修改仍在，下次同步自动重试），避免"缓存已含合并结果但
     * 基线未动、内存会话未更新"的三处不一致状态。
     *
     * ISSUE-P2-313：基线前移与高水位记录随 [SyncResolveUploadResult.Uploaded.settlement]
     * 延后到调用方采纳确认（校验-采用 + 本地落盘）成功之后——采纳失败 reject ⇒ 基线保持旧值，
     * 下轮按冲突流程收敛，云端 merged 内容不被陈旧树覆盖。
     */
    suspend fun markResolvedAndUpload(
        remotePath: String,
        mergedBytes: ByteArray,
        expectedEtag: String?
    ): SyncResolveUploadResult = withContext(Dispatchers.IO) {
        try {
            val newEtag = provider.uploadAtomic(remotePath, mergedBytes, expectedEtag?.takeIf { it.isNotBlank() })
                .getOrThrow()
            val localHash = cache.writeCache(remotePath, mergedBytes)
            SyncResolveUploadResult.Uploaded(
                newEtag,
                DeferredBaselineSettlement(
                    this@SyncEngine, remotePath, null, mergedBytes, localHash, newEtag, null
                )
            )
        } catch (t: Throwable) {
            SyncResolveUploadResult.Failed(t)
        }
    }
}
