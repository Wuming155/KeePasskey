package com.keepasskey.sync.engine

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
 * ISSUE-P2-308：引擎基线三步（缓存交付 / `updateBase` / `writeBaseContent` / `recordAccepted`）
 * 的**延迟落地句柄**。
 *
 * 引擎在把远端新内容交还 app 层时**不再先行前移基线**——采纳（远端解析落库 / 本地落盘保存）
 * 结论出来后，调用方必须对本句柄**恰好结算一次**：
 * - [accept]：采纳成功，落地基线三步（幂等）；
 * - [reject]：采纳失败或转入合并路径，弃置下载 tmp、基线保持原状不前移（幂等）。
 *
 * 未结算的代价是基线不前移（下轮同步重试同一份远端内容），绝不会静默前移——
 * 这正是本条整改要消除的「采纳失败后本地陈旧树以 V_prev+edit 整库覆盖他端」损失链的根。
 */
interface RemoteAdoptionSettlement {

    /** 采纳成功：落地基线三步（缓存交付 + 基线前移 + 防回滚高水位记录）。幂等。 */
    suspend fun accept()

    /** 采纳失败 / 转入合并：不落地、不前移；下载回执 tmp 弃置。幂等。 */
    suspend fun reject()
}

/**
 * openRemote 决策状态机结果。
 */
sealed class SyncOpenResult {
    /**
     * 成功与远端一致（下载刷新或直接匹配）。
     *
     * [adoption] 非 null（远端有新内容的路径）时表示基线三步**尚未落地**，调用方（app 层
     * 采纳分支）必须结算；null（远端未变、仅刷新元数据的路径）表示无待结算状态。
     * [equals] / [hashCode] 刻意不含 [adoption]：句柄是身份而非值，两份「同字节同 ETag」
     * 的结果语义相等。
     */
    data class RemoteSynced(
        val remoteBytes: ByteArray,
        val etag: String,
        val adoption: RemoteAdoptionSettlement? = null
    ) : SyncOpenResult() {
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

    /**
     * 远端返回设备侧**曾接受过的历史版本**（回退 / 重放），已被拒绝应用（ISSUE-P2-18）。
     * 调用方必须**保留本地/基准，不得应用远端**，并给出明确用户提示。
     */
    data class RollbackRejected(
        val remoteBytes: ByteArray,
        val remoteEtag: String
    ) : SyncOpenResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RollbackRejected
            return remoteBytes.contentEquals(other.remoteBytes) && remoteEtag == other.remoteEtag
        }

        override fun hashCode(): Int = 31 * remoteBytes.contentHashCode() + remoteEtag.hashCode()
    }

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
 * ISSUE-P2-313：`markResolvedAndUpload` 的终态（基线三步延后为采纳结算句柄）。
 *
 * 合并产物上传成功时本地采纳（校验-采用 + 落盘）仍在**之后**——基线若已即时前移到 merged 内容，
 * 采纳失败后引擎无回滚，下轮将以陈旧树「本地赢」整库覆盖云端刚接收的 merged 内容
 * （他端改动丢失）。故终态携带 [RemoteAdoptionSettlement]：调用方在采纳确认成功后
 * `accept`、采纳失败 / 落盘失败 `reject`（基线保持旧值，下轮按冲突流程收敛，不丢任何一侧）。
 */
sealed interface SyncResolveUploadResult {

    /** 上传成功：基线前移**尚未落地**，须在采纳确认后结算 */
    data class Uploaded(
        val newEtag: String,
        val settlement: RemoteAdoptionSettlement
    ) : SyncResolveUploadResult

    /** 上传失败（含 412 `SyncException.ConflictError`，调用方按原口径取 [error] 分派重入） */
    data class Failed(val error: Throwable) : SyncResolveUploadResult
}

/**
 * commitLocal 提交写结果。
 */
sealed class SyncCommitResult {
    /**
     * 成功保存并推送至远端。
     *
     * [settlement] 非 null 时基线前移**尚未落地**：app 层采纳确认（本地落盘保存成功 /
     * 冲突合并产物落库成功）后须 [RemoteAdoptionSettlement.accept]；落盘失败须
     * [RemoteAdoptionSettlement.reject]（ISSUE-P2-308：否则上传成功而落盘失败时基线已前移，
     * 进程死亡后重启将以旧正式文件内容整库覆盖远端）。
     */
    data class Uploaded(
        val newEtag: String,
        val settlement: RemoteAdoptionSettlement? = null
    ) : SyncCommitResult()

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

    /**
     * 冲突路径下载到的远端内容为设备侧曾接受过的历史版本（回退 / 重放），
     * 已拒绝其参与合并（ISSUE-P2-18）；本地缓存与修改安全保留。
     */
    data class RollbackRejected(val keptLocal: Boolean = true) : SyncCommitResult()
}
