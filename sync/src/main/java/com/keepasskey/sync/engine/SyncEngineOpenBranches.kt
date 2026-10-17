package com.keepasskey.sync.engine

import com.keepasskey.sync.model.SyncException

/**
 * [SyncEngine.openRemote] 的分支实现（`ISSUE-P2-645`：自 `SyncEngine` 拆分，纯结构性，行为零变更）。
 *
 * 之所以是同包扩展函数而非独立协作类：分支决策依赖引擎的三哈希缓存、Provider、事件流与
 * 防回滚守卫，抽成独立类只会把同一组依赖重新装配一遍，且引入「引擎状态两份」的风险。
 * 拆分后 `SyncEngine` 只保留决策树入口与提交路径；分支顺序、早退条件与事件发射点逐条未动。
 */
    internal suspend fun SyncEngine.openUncached(remotePath: String): SyncOpenResult {
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

        // ISSUE-P2-18：远端返回设备侧曾接受过的历史版本（回退/重放）即拒绝写入缓存与基线；摘要由接收期一次算出并全程贯穿（ISSUE-P3-167），不再对整库重算
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
                this, remotePath, receipt, remoteBytes, receipt.digest, meta.etag,
                SyncCacheEvent.LoadedFromRemoteInSync(remotePath)
            )
        )
    }

    /**
     * [openRemote] 元数据获取失败分支：远端 404 且有缓存 -> 上传恢复；
     * 其余降级读缓存，并按起因分型（见 [degradeUsingCache]，ISSUE-P2-548）。
     */
    internal suspend fun SyncEngine.recoverFromMetaFailure(
        remotePath: String,
        cachedBytes: ByteArray,
        ex: Throwable?
    ): SyncOpenResult = when (ex) {
        is SyncException.FileNotFound -> {
            // 远端 404 且有缓存 -> 上传恢复远端
            val uploadResult = provider.uploadAtomic(remotePath, cachedBytes, expectedEtag = null)
            val newEtag = uploadResult.getOrThrow()
            // ISSUE-P3-311 项 2：baseversion 必须取**本次所写字节的摘要**（真值），
            // 而非磁盘 `.version` 内容——崩溃窗内两者可能失配，取后者会把陈旧版本固化进基线
            advanceBaseAndPersist(cache, remotePath, newEtag, null, cachedBytes)
            recordAccepted(remotePath, cachedBytes)
            events.tryEmit(SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath))
            SyncOpenResult.RemoteLostRestored(newEtag)
        }
        else -> {
            events.tryEmit(SyncCacheEvent.CouldntOpenFromRemote(remotePath, ex))
            degradeUsingCache(cachedBytes, ex) // 网络不可达或服务器错误 → 降级读缓存（按起因分型）
        }
    }

    /** [openRemote] 分支 2：已缓存且本地无修改 -> 远端无变直接返回 / 远端修改则下载刷新。 */
    internal suspend fun SyncEngine.openCachedWithoutLocalChanges(
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
                this, remotePath, receipt, remoteBytes, receipt.digest, remoteEtag,
                SyncCacheEvent.UpdatedCachedFileOnLoad(remotePath)
            )
        )
    }

    /**
     * [openRemote] 分支 3/4/5：已缓存且本地有修改 -> 远端未变则本地赢自动上传；
     * 远端已变（或强覆盖开关开启、412 预条件失败）则按冲突/降级路径处置。
     */
    internal suspend fun SyncEngine.openCachedWithLocalChanges(
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
            return degradeUsingCache(cachedBytes, forcedEx)
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
        return degradeUsingCache(cachedBytes, uploadEx)
    }
