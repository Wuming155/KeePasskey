package com.keepasskey.sync.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private enum class State { PENDING, COMMITTING, COMMITTED, RECOVERY_REQUIRED, REJECTED }

    private val mutex = Mutex()
    private var state = State.PENDING

    override suspend fun accept() {
        mutex.withLock {
            when (state) {
                State.COMMITTED, State.REJECTED -> return
                State.RECOVERY_REQUIRED -> throw IllegalStateException("同步采纳事务需要恢复")
                State.PENDING -> state = State.COMMITTING
                State.COMMITTING -> error("settlement mutex must serialize committing state")
            }

            var intentWritten = false
            try {
                withContext(Dispatchers.IO) {
                    // Persist quarantine before the first cache/base/high-water mutation. If a later
                    // file operation fails, a new engine instance must refuse automatic adoption.
                    engine.rollbackGuard?.beginAcceptance(remotePath, contentDigest)
                    intentWritten = true
                    engine.reportSettlementStageForTest(SyncAdoptionSettlementStage.INTENT_PERSISTED)
                    receipt?.commit()
                    if (receipt != null) {
                        engine.reportSettlementStageForTest(SyncAdoptionSettlementStage.CACHE_RECEIPT_COMMITTED)
                    }
                    engine.cache.updateBase(remotePath, contentDigest, etag)
                    engine.reportSettlementStageForTest(SyncAdoptionSettlementStage.BASE_METADATA_PERSISTED)
                    engine.cache.writeBaseContent(remotePath, bytes)
                    engine.reportSettlementStageForTest(SyncAdoptionSettlementStage.BASE_CONTENT_PERSISTED)
                    engine.recordAccepted(remotePath, contentDigest)
                    engine.reportSettlementStageForTest(SyncAdoptionSettlementStage.HIGH_WATER_PERSISTED)
                    eventOnAccept?.let { engine.events.tryEmit(it) }
                }
                state = State.COMMITTED
            } catch (failure: Throwable) {
                state = if (intentWritten) State.RECOVERY_REQUIRED else State.PENDING
                throw failure
            }
        }
    }

    override suspend fun reject() {
        mutex.withLock {
            if (state != State.PENDING) return
            state = State.REJECTED
            withContext(Dispatchers.IO) { receipt?.abort() }
        }
    }
}
