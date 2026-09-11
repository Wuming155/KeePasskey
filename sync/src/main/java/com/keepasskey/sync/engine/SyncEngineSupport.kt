package com.keepasskey.sync.engine

import com.keepasskey.sync.provider.SyncProvider

/**
 * 远端一致性双通道裁决探测器（openRemote 决策树内部协作单元）。
 *
 * ETag 可用时按乐观锁比对（零下载）；无 ETag 服务器（部分极简 WebDAV 不返回 ETag）
 * 回退内容哈希裁决——下载一次与 baseversion 记录的 SHA-256 比对，避免 etag 缺失导致
 * "每次同步都误判远端更新/冲突"的退化循环。
 *
 * 内部记忆已下载的远端字节，供后续按需复用，避免同一决策树内重复下载。
 */
internal class RemoteConsistencyProbe(
    private val provider: SyncProvider,
    private val remotePath: String,
    private val baseEtag: String,
    private val remoteEtag: String,
    private val baseVersionHash: String
) {
    private var downloadedBytes: ByteArray? = null

    /** 已下载并缓存的远端字节；未触发下载时为 null。 */
    val downloaded: ByteArray?
        get() = downloadedBytes

    /** 远端相对基准版本是否未变（ETag 双端可用时优先乐观锁，缺失时回退内容哈希）。 */
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

    /** 复用已下载内容；未下载时拉取远端，失败则抛出。 */
    suspend fun remoteBytes(): ByteArray =
        downloadedBytes ?: provider.download(remotePath).getOrThrow()
}

/**
 * 上传成功后前移基准版本并持久化基准内容快照。
 *
 * 统一 openRemote / commitLocal / commitLocalForce / markResolvedAndUpload 各成功分支的
 * 「基线前移 + 基准内容落盘」写序（updateBase -> writeBaseContent），保证多处写盘序列一致。
 *
 * @param priorLocalVersion 已计算的本地版本哈希；为 null 时按 [bytes] 现算 SHA-256。
 */
internal fun advanceBaseAndPersist(
    cache: SyncCache,
    remotePath: String,
    newEtag: String,
    priorLocalVersion: String?,
    bytes: ByteArray
) {
    cache.updateBase(remotePath, priorLocalVersion ?: SyncCache.sha256Hex(bytes), newEtag)
    cache.writeBaseContent(remotePath, bytes)
}
