package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.sync.SyncProbeSupport.LockedProbeFailure
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 锁态下的密文上传与对账（`ISSUE-P2-645`：自 `SyncCoordinator` 拆分）。
 *
 * 不变量（与拆分前逐条等价）：
 * 1. 待上传密文先在 [PeriodicSyncPendingUploadStore] 落成队列项，上传前再比对磁盘摘要；
 * 2. 任何提前返回路径都必须把已读出的密文缓冲 `fill(0)`；
 * 3. 取消一律原样上抛（不得被 `catch (Exception)` 归为业务失败）。
 */
internal class SyncLockedUploader(
    private val context: Context,
    private val databaseSession: DatabaseSession,
    private val providers: SyncProviderResolver,
    /** 惰性取用：构造期不得触达（`context.filesDir` 是 IO，且测试上下文未必实现）。 */
    private val pendingUploads: () -> PeriodicSyncPendingUploadStore,
    private val currentVaultIdentity: () -> String?
) {

    /** 远端无变化时按需上传当前密文：队列项缺失则先落队，成功后登记录取基线。 */
    suspend fun uploadIfChanged(
        store: PeriodicSyncProbeStore,
        provider: SyncProvider,
        vaultIdentity: String,
        target: SyncProviderTarget,
        targetIdentity: String,
        currentEtag: String,
        previous: PeriodicSyncProbeRecord?,
        pending: PendingEncryptedUpload?
    ): PeriodicSyncWorkResult {
        val staged = stageQueued(store, vaultIdentity, target, targetIdentity, currentEtag, previous, pending)
        val queued = when (staged) {
            is StageOutcome.Failed -> return staged.result
            is StageOutcome.Ready -> staged.queued
        }
        val payload = withContext(Dispatchers.IO) {
            pendingUploads().readPayload(vaultIdentity, queued)
        } ?: return SyncProbeSupport.writePendingState(
            store, vaultIdentity, targetIdentity, currentEtag,
            PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT
        )
        return transmit(
            store = store,
            provider = provider,
            vaultIdentity = vaultIdentity,
            target = target,
            targetIdentity = targetIdentity,
            currentEtag = currentEtag,
            previousSyncedDigest = previous?.syncedContentDigest,
            queued = queued,
            payload = payload
        )
    }

    /**
     * 取得本轮要上传的队列项：没有队列项时先按当前磁盘密文落队；再校验库身份、
     * 磁盘摘要与目标身份，任一项变化即提前失败（此时尚未产生任何远端写）。
     */
    private suspend fun stageQueued(
        store: PeriodicSyncProbeStore,
        vaultIdentity: String,
        target: SyncProviderTarget,
        targetIdentity: String,
        currentEtag: String,
        previous: PeriodicSyncProbeRecord?,
        pending: PendingEncryptedUpload?
    ): StageOutcome {
        var queued = pending
        if (queued == null) {
            val currentDigest = SyncProbeSupport.cipherDigest(context, vaultIdentity)
                ?: return StageOutcome.Failed(
                    PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_UNAVAILABLE)
                )
            if (currentDigest == previous?.syncedContentDigest) {
                return StageOutcome.Failed(PeriodicSyncWorkResult.MetadataUnchanged)
            }
            val rollbackScope = previous?.rollbackVaultScope ?: return StageOutcome.Failed(
                SyncProbeSupport.writePendingState(
                    store, vaultIdentity, targetIdentity, currentEtag,
                    PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT
                )
            )
            val source = withContext(Dispatchers.IO) {
                SyncProbeSupport.openSnapshotStream(context, vaultIdentity)
            } ?: return StageOutcome.Failed(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_UNAVAILABLE)
            )
            queued = withContext(Dispatchers.IO) {
                pendingUploads().stage(
                    vaultIdentity = vaultIdentity,
                    targetIdentity = targetIdentity,
                    remotePath = target.remotePath,
                    expectedEtag = currentEtag,
                    rollbackVaultScope = rollbackScope,
                    input = source
                )
            } ?: return StageOutcome.Failed(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_WRITE_FAILED)
            )
        }

        if (currentVaultIdentity() != vaultIdentity || databaseSession.databaseFlow.value != null) {
            return StageOutcome.Failed(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)
            )
        }
        val onDiskDigest = SyncProbeSupport.cipherDigest(context, vaultIdentity)
            ?: return StageOutcome.Failed(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_UNAVAILABLE)
            )
        if (onDiskDigest != queued.contentDigest) {
            val guard = SyncRollbackGuard(
                File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
                queued.rollbackVaultScope
            )
            if (!guard.hasPendingAcceptance(target.remotePath)) {
                withContext(Dispatchers.IO) { pendingUploads().clear(vaultIdentity) }
            }
            return StageOutcome.Failed(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PENDING_UNLOCK)
            )
        }
        val currentTarget = providers.configuredTarget(
            SyncProbeSupport.defaultFileName(databaseSession, vaultIdentity)
        )
        if (currentTarget != null && currentTarget.identity != target.identity) {
            return StageOutcome.Failed(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)
            )
        }
        return StageOutcome.Ready(queued)
    }

    /**
     * 落回滚受理意图后执行原子上传，并核对远端返回的 ETag。
     *
     * 意图写失败与受理写失败都在**未发生远端写**的前提下返回（密文缓冲先清零）。
     */
    private suspend fun transmit(
        store: PeriodicSyncProbeStore,
        provider: SyncProvider,
        vaultIdentity: String,
        target: SyncProviderTarget,
        targetIdentity: String,
        currentEtag: String,
        previousSyncedDigest: String?,
        queued: PendingEncryptedUpload,
        payload: ByteArray
    ): PeriodicSyncWorkResult {
        val intentWritten = withContext(Dispatchers.IO) {
            store.write(
                vaultIdentity,
                PeriodicSyncProbeRecord(
                    targetIdentity = targetIdentity,
                    observedEtag = queued.expectedEtag,
                    needsUnlock = true,
                    syncedContentDigest = previousSyncedDigest,
                    rollbackVaultScope = queued.rollbackVaultScope
                )
            )
        }
        if (!intentWritten) {
            payload.fill(0)
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_WRITE_FAILED)
        }

        val guard = SyncRollbackGuard(
            File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
            queued.rollbackVaultScope
        )
        try {
            withContext(Dispatchers.IO) { guard.beginAcceptance(target.remotePath, queued.contentDigest) }
        } catch (cancelled: CancellationException) {
            payload.fill(0)
            throw cancelled
        } catch (_: Exception) {
            payload.fill(0)
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT)
        }
        val upload = try {
            provider.uploadAtomic(
                remotePath = target.remotePath,
                data = payload,
                expectedEtag = queued.expectedEtag,
                remoteExists = true
            )
        } catch (cancelled: CancellationException) {
            payload.fill(0)
            throw cancelled
        } catch (failure: Exception) {
            payload.fill(0)
            return classifiedFailure(failure, store, vaultIdentity, targetIdentity, currentEtag, Rejection.REPORT)
        }
        payload.fill(0)

        val failure = upload.exceptionOrNull()
        if (failure != null) {
            // 上传以 Result.failure 返回（非抛异常）：412 / 409 等预条件拒绝意味着远端已被改写，
            // 按「远端变更待解锁」记账（与抛异常分支的「原样上报」不同，语义沿用拆分前实现）。
            return classifiedFailure(
                failure, store, vaultIdentity, targetIdentity, currentEtag, Rejection.REMARK_CHANGED
            )
        }
        return finish(
            provider = provider,
            store = store,
            vaultIdentity = vaultIdentity,
            target = target,
            targetIdentity = targetIdentity,
            queued = queued,
            etag = cleanEtag(upload.getOrNull())
        )
    }

    /** 远端已存在待决上传时的对账：远端字节与队列摘要一致即视为已送达，否则按远端被改写交回用户。 */
    suspend fun reconcile(
        store: PeriodicSyncProbeStore,
        provider: SyncProvider,
        vaultIdentity: String,
        target: SyncProviderTarget,
        targetIdentity: String,
        currentEtag: String,
        pending: PendingEncryptedUpload
    ): PeriodicSyncWorkResult {
        val downloaded = ByteArrayOutputStream()
        val result = try {
            provider.download(target.remotePath, downloaded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return classifiedFailure(failure, store, vaultIdentity, targetIdentity, currentEtag, Rejection.REPORT)
        }
        if (result.isFailure) {
            val failure = result.exceptionOrNull()
                ?: return PeriodicSyncWorkResult.RemoteRejected("DownloadFailure")
            return classifiedFailure(failure, store, vaultIdentity, targetIdentity, currentEtag, Rejection.REPORT)
        }
        val remoteBytes = downloaded.toByteArray()
        val matchesQueuedRevision = SyncCache.sha256Hex(remoteBytes) == pending.contentDigest
        remoteBytes.fill(0)
        if (!matchesQueuedRevision) {
            return SyncProbeSupport.writePendingState(
                store, vaultIdentity, targetIdentity, currentEtag,
                PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED
            )
        }
        return finish(provider, store, vaultIdentity, target, targetIdentity, pending, currentEtag)
    }

    /** 收尾：补齐 ETag（缺失时回读元数据）→ 登记受理 → 落终态记录 → 清队列。 */
    private suspend fun finish(
        provider: SyncProvider,
        store: PeriodicSyncProbeStore,
        vaultIdentity: String,
        target: SyncProviderTarget,
        targetIdentity: String,
        queued: PendingEncryptedUpload,
        etag: String
    ): PeriodicSyncWorkResult {
        if (etag.isBlank()) {
            val metadata = try {
                provider.getMetadata(target.remotePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return PeriodicSyncWorkResult.TemporaryNetworkFailure
            }
            val metadataFailure = metadata.exceptionOrNull()
            if (metadataFailure != null) {
                return PeriodicSyncWorkResult.RemoteRejected(metadataFailure.javaClass.simpleName)
            }
            val confirmedEtag = cleanEtag(metadata.getOrNull()?.etag)
            if (confirmedEtag.isBlank()) {
                return SyncProbeSupport.writePendingState(
                    store, vaultIdentity, targetIdentity, "",
                    PeriodicSyncNeedsUnlockReason.ETAG_UNAVAILABLE
                )
            }
            return finish(provider, store, vaultIdentity, target, targetIdentity, queued, confirmedEtag)
        }

        val guard = SyncRollbackGuard(
            File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
            queued.rollbackVaultScope
        )
        try {
            withContext(Dispatchers.IO) {
                guard.beginAcceptance(target.remotePath, queued.contentDigest)
                guard.recordAccepted(target.remotePath, queued.contentDigest)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_WRITE_FAILED)
        }

        val written = withContext(Dispatchers.IO) {
            store.write(
                vaultIdentity,
                PeriodicSyncProbeRecord(
                    targetIdentity = targetIdentity,
                    observedEtag = etag,
                    needsUnlock = false,
                    syncedContentDigest = queued.contentDigest,
                    rollbackVaultScope = queued.rollbackVaultScope
                )
            )
        }
        if (!written) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_WRITE_FAILED)
        }
        withContext(Dispatchers.IO) { pendingUploads().clear(vaultIdentity) }
        return PeriodicSyncWorkResult.EncryptedUploadCompleted
    }

    /**
     * 失败分型到工作结果的唯一出口（拆前四处重复块收敛到此）。
     *
     * [Rejection.REPORT] = 原样上报远端拒绝；[Rejection.REMARK_CHANGED] = 记为「远端已被他人改写」。
     */
    private fun classifiedFailure(
        failure: Throwable,
        store: PeriodicSyncProbeStore,
        vaultIdentity: String,
        targetIdentity: String,
        currentEtag: String,
        rejection: Rejection
    ): PeriodicSyncWorkResult = when (SyncProbeSupport.classify(failure)) {
        LockedProbeFailure.AUTH -> PeriodicSyncWorkResult.AuthenticationRequired
        LockedProbeFailure.TEMPORARY -> PeriodicSyncWorkResult.TemporaryNetworkFailure
        LockedProbeFailure.MISSING -> SyncProbeSupport.writePendingState(
            store, vaultIdentity, targetIdentity, currentEtag,
            PeriodicSyncNeedsUnlockReason.REMOTE_MISSING
        )
        LockedProbeFailure.REJECTED -> when (rejection) {
            Rejection.REPORT -> PeriodicSyncWorkResult.RemoteRejected(failure.javaClass.simpleName)
            Rejection.REMARK_CHANGED -> SyncProbeSupport.writePendingState(
                store, vaultIdentity, targetIdentity, currentEtag,
                PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED
            )
        }
    }

    private enum class Rejection { REPORT, REMARK_CHANGED }

    private sealed interface StageOutcome {
        data class Ready(val queued: PendingEncryptedUpload) : StageOutcome
        data class Failed(val result: PeriodicSyncWorkResult) : StageOutcome
    }
}
