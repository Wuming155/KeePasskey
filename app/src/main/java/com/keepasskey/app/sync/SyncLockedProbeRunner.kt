package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 锁库状态下的远端元数据探测（`ISSUE-P2-645`：自 `SyncCoordinator` 拆分）。
 *
 * 探测只读：不改正式库、不上传（上传走 [SyncLockedUploader]），结论以
 * [PeriodicSyncWorkResult] 交回周期 Worker。全流程保持拆分前语义：
 * 目标身份在每次远端往返前后复核一次，库一被解锁/换库即按 `TARGET_CHANGED` 退出。
 */
internal class SyncLockedProbeRunner(
    private val context: Context,
    private val databaseSession: DatabaseSession,
    private val providers: SyncProviderResolver,
    private val session: SyncSessionState,
    private val probeStore: PeriodicSyncProbeStore?,
    /** 惰性取用：构造期不得触达（`context.filesDir` 是 IO，且测试上下文未必实现）。 */
    private val pendingUploads: () -> PeriodicSyncPendingUploadStore,
    private val currentVaultIdentity: () -> String?,
    private val uploader: SyncLockedUploader
) {

    /** 周期 Worker 的锁态入口：库未解锁时只探测远端，不触碰本地明文。 */
    suspend fun runProbe(): PeriodicSyncWorkResult {
        val store = probeStore
            ?: return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT)
        val vaultIdentity = currentVaultIdentity()
            ?: return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.ACTIVE_VAULT_UNKNOWN)
        val defaultFileName = SyncProbeSupport.defaultFileName(databaseSession, vaultIdentity)

        val providerResult = try {
            providers.useProviderWithTarget(
                defaultFileName = defaultFileName,
                testOverride = session.testSyncProvider
            ) { provider, configuredTarget ->
                probeWithProvider(store, provider, configuredTarget, vaultIdentity, defaultFileName)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return PeriodicSyncWorkResult.RemoteRejected(failure.javaClass.simpleName)
        }

        if (providerResult != null) return providerResult

        // Provider 构造失败可能是凭据无法解封。这里只读取非秘密目标描述，以区分未配置和认证失败。
        return try {
            if (providers.configuredTarget(defaultFileName) == null) {
                PeriodicSyncWorkResult.NotConfigured
            } else {
                PeriodicSyncWorkResult.AuthenticationRequired
            }
        } catch (_: Exception) { // cancel-n/a: configuredTarget 为同步函数，保护段不含挂起点
            PeriodicSyncWorkResult.ConfigurationInvalid
        }
    }

    /** 解析目标 → 复核库身份 → 读探测状态与待决队列 → 交给 [observe]。 */
    private suspend fun probeWithProvider(
        store: PeriodicSyncProbeStore,
        provider: SyncProvider,
        configuredTarget: SyncProviderTarget?,
        vaultIdentity: String,
        defaultFileName: String
    ): PeriodicSyncWorkResult {
        val target = configuredTarget ?: session.testRemotePath?.let { testPath ->
            SyncProviderTarget(testPath, SyncProbeSupport.targetIdentity(vaultIdentity, testPath))
        }
        if (target == null || SyncProbeSupport.isUnresolvedDefaultPath(target.remotePath, vaultIdentity)) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_UNAVAILABLE)
        }
        if (!isStillLockedOn(vaultIdentity)) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)
        }

        val targetIdentity = SyncProbeSupport.targetIdentity(vaultIdentity, target.identity)
        val probeState = store.read(vaultIdentity)
        val storedProbe = (probeState as? PeriodicSyncProbeRead.Stored)?.record
        val pendingQueue = pendingUploads().read(vaultIdentity)
        if (pendingUploads().hasEntry(vaultIdentity) && pendingQueue == null) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT)
        }
        if (pendingQueue != null &&
            (pendingQueue.targetIdentity != targetIdentity || pendingQueue.remotePath != target.remotePath)
        ) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)
        }
        val preflight = if (pendingQueue != null) {
            PeriodicSyncProbePolicy.Preflight.RequestMetadata(pendingQueue.expectedEtag)
        } else {
            PeriodicSyncProbePolicy.preflight(probeState, targetIdentity)
        }
        return when (preflight) {
            is PeriodicSyncProbePolicy.Preflight.NeedsUnlock ->
                PeriodicSyncWorkResult.NeedsUnlock(preflight.reason)

            is PeriodicSyncProbePolicy.Preflight.RequestMetadata -> observe(
                store = store,
                provider = provider,
                configuredTarget = configuredTarget,
                defaultFileName = defaultFileName,
                vaultIdentity = vaultIdentity,
                target = target,
                targetIdentity = targetIdentity,
                previousEtag = preflight.previousEtag,
                previous = storedProbe,
                pending = pendingQueue
            )
        }
    }

    /** 取远端元数据并按观测结论分流：未变 → 按需上传；需解锁 → 对账或登记待解锁原因。 */
    private suspend fun observe(
        store: PeriodicSyncProbeStore,
        provider: SyncProvider,
        configuredTarget: SyncProviderTarget?,
        defaultFileName: String,
        vaultIdentity: String,
        target: SyncProviderTarget,
        targetIdentity: String,
        previousEtag: String?,
        previous: PeriodicSyncProbeRecord?,
        pending: PendingEncryptedUpload?
    ): PeriodicSyncWorkResult {
        val metadata = try {
            provider.getMetadata(target.remotePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

        if (!isStillLockedOn(vaultIdentity)) {
            return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)
        }
        if (configuredTarget != null) {
            val currentConfiguredTarget = providers.configuredTarget(defaultFileName)
            if (currentConfiguredTarget?.identity != configuredTarget.identity ||
                currentConfiguredTarget.remotePath != configuredTarget.remotePath
            ) {
                return PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.TARGET_CHANGED)
            }
        }

        val failure = metadata.exceptionOrNull()
        if (failure != null) {
            return when (SyncProbeSupport.classify(failure)) {
                SyncProbeSupport.LockedProbeFailure.AUTH -> PeriodicSyncWorkResult.AuthenticationRequired
                SyncProbeSupport.LockedProbeFailure.TEMPORARY -> PeriodicSyncWorkResult.TemporaryNetworkFailure
                SyncProbeSupport.LockedProbeFailure.MISSING -> SyncProbeSupport.writePendingState(
                    store, vaultIdentity, targetIdentity, observedEtag = "",
                    PeriodicSyncNeedsUnlockReason.REMOTE_MISSING
                )
                SyncProbeSupport.LockedProbeFailure.REJECTED ->
                    PeriodicSyncWorkResult.RemoteRejected(failure.javaClass.simpleName)
            }
        }

        val currentEtag = cleanEtag(metadata.getOrNull()?.etag)
        return when (val observation = PeriodicSyncProbePolicy.observe(previousEtag, currentEtag)) {
            PeriodicSyncProbePolicy.Observation.Unchanged -> uploader.uploadIfChanged(
                store = store,
                provider = provider,
                vaultIdentity = vaultIdentity,
                target = target,
                targetIdentity = targetIdentity,
                currentEtag = currentEtag,
                previous = previous,
                pending = pending
            )

            is PeriodicSyncProbePolicy.Observation.NeedsUnlock -> {
                if (pending != null) {
                    uploader.reconcile(
                        store = store,
                        provider = provider,
                        vaultIdentity = vaultIdentity,
                        target = target,
                        targetIdentity = targetIdentity,
                        currentEtag = currentEtag,
                        pending = pending
                    )
                } else {
                    SyncProbeSupport.writePendingState(
                        store, vaultIdentity, targetIdentity, currentEtag, observation.reason
                    )
                }
            }
        }
    }

    /** 目标与库身份均未变才算「仍在同一次锁态上下文」：否则任何远端结论都不可归因。 */
    private fun isStillLockedOn(vaultIdentity: String): Boolean =
        currentVaultIdentity() == vaultIdentity && databaseSession.databaseFlow.value == null

    /** ISSUE-P3-381：取回同一库、同一目标的进程内 ETag 基线；目标变化时视为首次探测。 */
    fun lastEtag(targetIdentity: String): String =
        if (session.probeBaselineTargetIdentity == targetIdentity) session.lastRemoteEtag else ""

    /** 当前目标首次探测完成后建立进程内基线；锁库后随会话清理。 */
    fun rememberEtag(etag: String, targetIdentity: String) {
        session.lastRemoteEtag = etag
        session.probeBaselineTargetIdentity = targetIdentity
    }

    /**
     * 周期收尾：把本轮确认的 ETag / 同步摘要写成持久化探测记录。
     *
     * 判据与拆分前一致：只有「已完全收敛」的结果才清待决队列；未收敛结果沿用既有摘要，
     * 不把本轮内容当作远端一致的真实凭据。
     */
    suspend fun persistAfterSync(targetBefore: SyncProviderTarget, outcome: SyncOutcome) {
        val store = probeStore ?: return
        val vaultIdentity = currentVaultIdentity() ?: return
        val targetAfter = resolveTarget() ?: return
        if (targetAfter.identity != targetBefore.identity || targetAfter.remotePath != targetBefore.remotePath) return
        val targetIdentity = targetAfter.identity
        val existing = (store.read(vaultIdentity) as? PeriodicSyncProbeRead.Stored)?.record
            ?.takeIf { it.targetIdentity == targetIdentity }
        val cachedEtag = withContext(Dispatchers.IO) {
            session.lastSyncCache?.getState(targetAfter.remotePath)?.etag.orEmpty()
        }
        val etag = cleanEtag(lastEtag(targetIdentity))
            .ifBlank { cleanEtag(cachedEtag) }
            .ifBlank { existing?.observedEtag.orEmpty() }
        val fullyConverged = when (outcome) {
            SyncOutcome.UpToDate, SyncOutcome.UploadedLocal, SyncOutcome.MergedAndUploaded -> true
            SyncOutcome.LocalPendingChanges,
            is SyncOutcome.ConflictNeedsUser,
            is SyncOutcome.VaultBindingMismatch -> false
            else -> return
        }
        if (fullyConverged && etag.isBlank()) {
            // No trustworthy post-sync ETag is available. Clear stale pending state; the next
            // locked probe establishes a fresh observation and never treats it as content proof.
            withContext(Dispatchers.IO) { store.clear(vaultIdentity) }
            clearPendingUploadFor(targetIdentity, targetAfter.remotePath, vaultIdentity)
            return
        }
        val rollbackScope = databaseSession.databaseFlow.value?.rootGroup?.id?.toHexString()
            ?: existing?.rollbackVaultScope
        val syncedDigest = when {
            !fullyConverged -> existing?.syncedContentDigest
            rollbackScope == null -> null
            else -> SyncProbeSupport.cipherDigest(context, vaultIdentity)
        }
        val persistedRollbackScope = rollbackScope.takeIf { syncedDigest != null }
        val wroteState = withContext(Dispatchers.IO) {
            store.write(
                vaultIdentity,
                PeriodicSyncProbeRecord(
                    targetIdentity = targetIdentity,
                    observedEtag = etag,
                    needsUnlock = !fullyConverged,
                    syncedContentDigest = syncedDigest,
                    rollbackVaultScope = persistedRollbackScope
                )
            )
        }
        if (wroteState && fullyConverged) {
            if (etag.isNotBlank()) rememberEtag(etag, targetIdentity)
            // A manual full sync may be the user's recovery action for a locked-state queue.
            // Clear only a queue for the same provider target after a fully converged outcome;
            // local edits made during the sync are reported as LocalPendingChanges above.
            clearPendingUploadFor(targetIdentity, targetAfter.remotePath, vaultIdentity)
        }
    }

    private suspend fun clearPendingUploadFor(
        targetIdentity: String,
        remotePath: String,
        vaultIdentity: String
    ) {
        val queued = withContext(Dispatchers.IO) { pendingUploads().read(vaultIdentity) } ?: return
        if (queued.targetIdentity == targetIdentity && queued.remotePath == remotePath) {
            withContext(Dispatchers.IO) { pendingUploads().clear(vaultIdentity) }
        }
    }

    private fun resolveTarget(): SyncProviderTarget? {
        val activeName = databaseSession.currentFile?.name
            ?: databaseSession.databaseFlow.value?.databaseName?.let { "$it.kdbx" }
            ?: return null
        return try {
            val target = providers.configuredTarget(activeName) ?: return null
            val vaultIdentity = databaseSession.currentPathIdentifier
                ?: databaseSession.currentFile?.absolutePath
                ?: databaseSession.databaseFlow.value?.databaseName.orEmpty()
            target.copy(identity = SyncProbeSupport.targetIdentity(vaultIdentity, target.identity))
        } catch (_: Exception) {
            null
        }
    }
}
