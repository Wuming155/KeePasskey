package com.keepasskey.sync.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ISSUE-P2-308：引擎基线三步（缓存交付 / `updateBase` / `writeBaseContent` / `recordAccepted`）
 * 的延迟落地实现（自 [SyncEngine] 内部类外置为同包协作类，函数体逐行搬运——`SyncEngine` 触及
 * tier1(>500) 规模闸门，拆分口径同 ISSUE-P3-305 / §308 的同包协作类先例）。
 *
 * [receipt] 非 null 表示远端下载回执仍停留在 tmp（accept 时才原子交付进 `<hash>.cache`）；
 * null 表示工作副本已由 `writeCache` 先行落盘（commitLocal 系），accept 只前移基线。
 * accept / reject 各自幂等且二选一；accept 的文件 IO 固定在 `Dispatchers.IO`。
 */
internal class DeferredBaselineSettlement(
    private val engine: SyncEngine,
    private val remotePath: String,
    private val receipt: SyncCache.ReceivedRemote?,
    private val bytes: ByteArray,
    private val contentDigest: String,
    private val etag: String,
    private val eventOnAccept: SyncCacheEvent?
) : RemoteAdoptionSettlement {
    private var settled = false

    override suspend fun accept() {
        if (settled) return
        settled = true
        withContext(Dispatchers.IO) {
            receipt?.commit()
            advanceBaseAndPersist(engine.cache, remotePath, etag, contentDigest, bytes)
            engine.recordAccepted(remotePath, contentDigest)
            eventOnAccept?.let { engine.events.tryEmit(it) }
        }
    }

    override suspend fun reject() {
        if (settled) return
        settled = true
        receipt?.abort()
    }
}
