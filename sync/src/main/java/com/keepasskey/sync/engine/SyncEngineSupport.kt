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
 *
 * **`ISSUE-P3-167` 复核结论（为何仍然写两份完整内容）**：本函数与 `writeCache` 各写一份
 * （缓存快照 + 基准快照），**这是有意的**——基准快照必须与工作副本解耦，否则本地修改会污染 base、
 * 使三方合并退化为「远端全胜」。原条目另建议「内容一致则跳过第二次写盘」，复核判定**不做**：
 * ① 逐字节判定（长度 + 摘要）需要在**每一次**调用上先读全量内容并哈希，而本函数总在
 * 「内容刚变更」之后被调用（上传成功 / 接受远端）⇒ 跳过判定在最常见路径上**净增**工作量；
 * ② 廉价推断（`basecache` 存在且 `<hash>.baseversion` 已等于该摘要）**不安全**：本函数的写序是
 * `updateBase` → `writeBaseContent`，两步之间崩溃会留下「baseversion 已前移、basecache 仍旧字节」，
 * 此时跳过写盘会把**陈旧基准永久固化**——正是基准快照存在的意义所要避免的。
 * 结论与解除条件登记于 `docs/architecture/已知工程限界.md` §11。
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
