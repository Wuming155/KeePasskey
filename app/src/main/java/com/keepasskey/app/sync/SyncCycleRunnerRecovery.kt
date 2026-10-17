package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.SyncConflictStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SyncCycleRunner] 的两条独立路径（`ISSUE-P2-645`：自 `SyncCycleRunner` 拆分，纯结构性）。
 *
 * 1. [SyncCycleRunner.takeoverVaultBinding]：库身份改绑后的整库覆盖上传；
 * 2. [SyncCycleRunner.handleOpenRemote]：步骤 4 的 openRemote 决策分派与异常归一。
 *
 * 之所以是同包扩展函数：两者都要用运行器已装配的 Provider / 编解码 / 缓存工厂 / 挂锁守卫，
 * 抽成独立类会重复装配同一组依赖并制造第二份状态。取锁范围仍由调用方在
 * [SyncSessionState.mutex] 内保证（与拆分前一致）。
 */
    /**
     * `ISSUE-P2-291` AC②：用户显式确认「整库覆盖并绑定当前库」后的执行路径。
     *
     * 语义（对话框已如实告知）：
     * 1. `remotePath` 的归属登记**改绑**为当前库（此后另一库再同步将被同一闸拦截）；
     * 2. 当前库**整库无预条件上传**（`commitLocalForce`，`expectedEtag = null`）——
     *    云端副本（原属另一库）被整体替换，这正是用户确认的内容；
     *    不复用旧库 ETag 基线（AC④：库身份变更后旧基线天然不参与新键）；
     * 3. 新库身份键下的缓存 / 基线 / 防回滚从空开始，由本次上传建立。
     */
    internal suspend fun SyncCycleRunner.takeoverVaultBinding(): SyncOutcome = session.mutex.withLock {
        val currentDb = databaseSession.databaseFlow.value
            ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_vault_not_unlocked))
        // §411：与 runSyncCycle 同口径——SAF 库 currentFile 为 null 时以库名兜底解析远端路径
        val vaultFileName = databaseSession.currentFile?.name ?: "${currentDb.databaseName}.kdbx"

        try {
            providerResolver.useProvider(session.testSyncProvider) { provider ->
                val remotePath = withContext(Dispatchers.IO) {
                    session.testRemotePath ?: providerResolver.resolveRemotePath(vaultFileName)
                }
                val vaultScope = vaultBindingStore?.let { currentDb.rootGroup.id.toHexString() }
                val cacheScope = vaultScope.orEmpty()

                withContext(Dispatchers.IO) {
                autoLockGuard?.beginLongTask()
                var holdoffActive = autoLockGuard != null
                val localBytes = try {
                    codec.serializeLocalDatabase(currentDb)
                } finally {
                    if (holdoffActive) {
                        holdoffActive = false
                        autoLockGuard?.endLongTask()
                    }
                } ?: return@withContext SyncOutcome.Error(
                    strings.get(R.string.sync_error_local_serialize_failed)
                )
                // 确认即改绑：此后该远端目标归当前库所有
                if (vaultScope != null) vaultBindingStore?.saveBinding(remotePath, vaultScope)

                val syncDir = File(context.cacheDir, SyncCache.CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
                val syncCache = SyncCache(syncDir, cacheScope)
                val rollbackGuard = createRollbackGuard(
                    rollbackStateDir ?: File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
                    cacheScope
                )
                val engine = SyncEngine(provider, syncCache, rollbackGuard)
                engine.isOffline = session.isOfflineMode
                session.lastSyncEngine = engine
                when (val takeoverCommit = engine.commitLocalForce(remotePath, localBytes)) {
                    is SyncCommitResult.Uploaded -> {
                        // ISSUE-P2-308：上传内容即当前会话库内容，采纳已隐式完成，立即落地基线
                        takeoverCommit.settlement?.accept()
                        // ISSUE-P1-591：基线前移到**实际上传的版本**（窗口内换库/换版本则回报待同步）
                        when (settleSyncedVersion(session, currentDb, databaseSession.databaseFlow.value)) {
                            UploadSettlement.CONVERGED -> SyncOutcome.UploadedLocal
                            UploadSettlement.LOCAL_DIVERGED -> SyncOutcome.LocalPendingChanges
                        }
                    }
                    is SyncCommitResult.RemoteUnreachable -> SyncOutcome.Offline
                    SyncCommitResult.RecoveryRequired ->
                        SyncOutcome.Error(strings.get(R.string.sync_error_recovery_required))
                    // ISSUE-P2-548：整库覆盖被鉴权 / 协议 / 服务端拒绝——不是「离线」
                    is SyncCommitResult.RemoteRejected -> strings.remoteFailureOutcome(takeoverCommit.cause)
                    else -> SyncOutcome.Error(strings.get(R.string.sync_error_vault_takeover_failed))
                }
            }
            } ?: SyncOutcome.Error(strings.get(R.string.sync_error_no_sync_credentials))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // ISSUE-P3-311 项 3：同上——固定通用文案，不透传异常 message
            SyncOutcome.Error(strings.get(R.string.sync_error_vault_takeover_failed))
        }
    }

    /**
     * 步骤 4（原样搬运）：openRemote 同步状态机决策与异常归一。
     *
     * 调用方在 [SyncSessionState.mutex] 内调用；异常归一（NetworkError → Offline、
     * 其他异常 → Error）与拆分前 `try { when (openRemote) {...} } catch {...}` 结构逐一对应。
     *
     * @param localDbSnapshot ISSUE-P3-168 ①：与本次冲突涉及的本地字节内容等价的本地内存树，
     *   转三方合并时直接充当本地侧（免去一次「解析回树」的整库解密）。
     * @param localChangeState `ISSUE-P2-404`：内容变更三态（见 [RemoteSyncContext]）。
     */
    internal suspend fun SyncCycleRunner.handleOpenRemote(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
        localDbSnapshot: KdbxDatabase,
        baseSnapshotBytes: ByteArray?,
        isDirty: Boolean,
        hasLocalContentChanged: Boolean,
        localChangeState: LocalContentChangeState,
        localFileUnchangedSinceSync: Boolean,
        conflictStrategy: SyncConflictStrategy
    ): SyncOutcome {
        val ctx = RemoteSyncContext(
            syncEngine = syncEngine,
            syncCache = syncCache,
            remotePath = remotePath,
            localBytes = localBytes,
            localDbSnapshot = localDbSnapshot,
            baseSnapshotBytes = baseSnapshotBytes,
            isDirty = isDirty,
            hasLocalContentChanged = hasLocalContentChanged,
            localChangeState = localChangeState,
            localFileUnchangedSinceSync = localFileUnchangedSinceSync,
            conflictStrategy = conflictStrategy
        )
        return try {
            when (val openResult = syncEngine.openRemote(remotePath)) {
                is SyncOpenResult.RemoteSynced -> handleRemoteSynced(ctx, openResult)
                // ISSUE-P1-591：这两支上传的都是周期起点快照 ⇒ 基线结算以快照为准（窗口内换版本即回报待同步）
                is SyncOpenResult.LocalWinAutoUploaded -> when (
                    settleSyncedVersion(session, localDbSnapshot, databaseSession.databaseFlow.value)
                ) {
                    UploadSettlement.CONVERGED -> SyncOutcome.UploadedLocal
                    UploadSettlement.LOCAL_DIVERGED -> SyncOutcome.LocalPendingChanges
                }
                is SyncOpenResult.RemoteLostRestored -> when (
                    settleSyncedVersion(session, localDbSnapshot, databaseSession.databaseFlow.value)
                ) {
                    UploadSettlement.CONVERGED -> SyncOutcome.UploadedLocal
                    UploadSettlement.LOCAL_DIVERGED -> SyncOutcome.LocalPendingChanges
                }
                is SyncOpenResult.CacheHitOffline -> SyncOutcome.Offline
                is SyncOpenResult.RemoteUnreachableUsingCache -> SyncOutcome.Offline
                // ISSUE-P2-548：远端**可达但拒绝**（401/403、协议错误、5xx）此前与「真断网」
                // 共用一个降级结果 ⇒ UI 显示「离线，已保留本地副本」、通知在同周期被 Offline 撤掉。
                // 现按起因归类：NetworkError 才 Offline，其余出具体失败文案（口径与首传探测一致）。
                is SyncOpenResult.RemoteRejectedUsingCache ->
                    strings.remoteFailureOutcome(openResult.cause)
                is SyncOpenResult.ConflictDetected -> handleConflictDetected(ctx, openResult)
                // ISSUE-P2-18：远端内容为设备侧曾接受过的旧版本（回退/重放）→
                // 保留本地/基准、不应用远端，并给出明确用户提示
                is SyncOpenResult.RollbackRejected -> SyncOutcome.Error(
                    strings.get(R.string.sync_error_rollback_rejected)
                )
                SyncOpenResult.RecoveryRequired ->
                    SyncOutcome.Error(strings.get(R.string.sync_error_recovery_required))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消必须原样重抛，绝不可归一为同步失败（结构化并发契约）
            throw e
        } catch (e: com.keepasskey.sync.model.SyncException.NetworkError) {
            SyncOutcome.Offline
        } catch (e: Throwable) {
            // ISSUE-P0-09：捕获面扩到 Throwable——provider 的 runCatching 会把 Error
            // （超深 XML 的 StackOverflowError / 超大响应 OOM）包成 Result.failure，
            // 经引擎 getOrThrow 原样重抛；仅捕 Exception 时 Error 在此脱网并杀死进程，
            // 且每个同步周期自动复发。远端可单方面触发该链路，必须在同步边界
            // 遏制为「本次同步失败」，不允许绕过应用自身的错误遏制框架。
            // ISSUE-P3-311 项 3：异常 message 可能携带服务器可控串，UI 只出固定通用文案
            SyncOutcome.Error(strings.get(R.string.sync_error_unknown))
        }
    }
