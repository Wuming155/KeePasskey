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
