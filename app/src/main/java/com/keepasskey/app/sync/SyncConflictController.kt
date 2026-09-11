package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.merge.BothModifiedEntryCollector
import com.keepasskey.sync.merge.ConflictDisposition
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictStrategyPolicy
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.cleanEtag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 冲突检测后的决策与提交（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运）。
 *
 * 职责边界：持有「待决冲突会话」上下文（远端引擎/路径、双方快照、三方合并产物、远端 ETag）
 * 与对外冲突流，承载强制策略应用、三方合并与用户决策后的最终回写。
 * 互斥语义：本类不取锁——同步周期（`SyncCycleRunner.runSyncCycle`）与用户决策
 * （`SyncCoordinator.resolveConflicts`）均由调用方持有 `SyncSessionState.mutex` 后进入，
 * 与拆分前 `mutex.withLock { ... }` 的覆盖范围逐行等价（避免二次取锁自死锁）。
 */
@Singleton
class SyncConflictController @Inject constructor(
    private val databaseSession: DatabaseSession,
    private val codec: SyncDatabaseCodec,
    private val strings: StringsProvider,
    private val session: SyncSessionState,
    private val debugLog: DebugLogBuffer
) {

    // 缓存发生冲突时的上下文，供用户确认合并后提交
    private var pendingRemoteEngine: SyncEngine? = null
    private var pendingRemotePath: String? = null
    private var pendingLocalDb: KdbxDatabase? = null
    private var pendingRemoteDb: KdbxDatabase? = null

    // A1 整改：三方合并产物必须保存到用户决策时刻——resolveConflicts 需以
    // mergedRoot（含远端新增条目/分组与非冲突字段级合并）为底版应用用户选择，
    // 若从纯 localDb 重建，合并产物将全部丢失
    private var pendingMergedRoot: KdbxGroup? = null
    private var pendingMergedTombstones: List<DeletedObject> = emptyList()

    // E2 整改：冲突发生时刻的远端 ETag。resolveConflicts 的 If-Match 期望值必须用
    // 该值而非重新探测的当前值，否则用户决策期间远端的再次更新会被静默覆盖
    private var pendingRemoteEtag: String = ""

    private val _conflictFlow = MutableStateFlow<List<ConflictedEntryPair>>(emptyList())

    /** 当前待决冲突清单（由 `SyncCoordinator.conflictFlow` 对外暴露，实例与拆分前同为单一 StateFlow）。 */
    val conflictFlow: StateFlow<List<ConflictedEntryPair>> = _conflictFlow.asStateFlow()

    /**
     * 解决冲突并执行最终提交回写。
     * [resolutions] 为条目级决策（默认兜底）；[fieldResolutions]（TASK-30 整改）为字段级
     * 决策——键为条目 ID，值为「字段键 → 选择」映射，非空时该条目按字段粒度合并
     * （本地为底版、远端仅覆写用户钦点字段），取代整条目二选一的塌缩行为。
     *
     * ISSUE-P3-25：调用方（`SyncCoordinator.resolveConflicts`）已持有会话互斥锁，本方法内不再取锁。
     */
    suspend fun resolveConflicts(
        resolutions: Map<String, ConflictResolutionChoice>,
        fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>> = emptyMap()
    ): SyncOutcome {
        val engine = pendingRemoteEngine ?: return SyncOutcome.Error(strings.get(R.string.sync_error_no_pending_conflict))
        val path = pendingRemotePath ?: return SyncOutcome.Error(strings.get(R.string.sync_error_conflict_path_invalid))
        val localDb = pendingLocalDb ?: return SyncOutcome.Error(strings.get(R.string.sync_error_local_snapshot_lost))
        val remoteDb = pendingRemoteDb ?: return SyncOutcome.Error(strings.get(R.string.sync_error_remote_snapshot_lost))

        return withContext(Dispatchers.Default) {
            val conflicts = _conflictFlow.value
            // A1 整改：以三方合并产物为底版应用用户决策。mergedRoot 含远端新增
            // 条目/分组、非冲突字段级合并与标签并集；若从纯 localDb 重建，
            // 这些合并成果将随冲突决策一并丢失（静默数据丢失）
            var updatedRoot = pendingMergedRoot ?: localDb.rootGroup

            for (pair in conflicts) {
                val choice = resolutions[pair.entryId] ?: ConflictResolutionChoice.KEEP_LOCAL
                val fieldChoice = fieldResolutions[pair.entryId]
                // TASK-30：字段级决策优先——本地为底版、远端仅覆写用户钦点的字段；
                // 无字段级决策时回退整条目二选一
                val resolvedEntries = if (fieldChoice != null) {
                    listOf(KdbxMerger.resolveConflictByFields(pair, fieldChoice))
                } else {
                    KdbxMerger.resolveConflict(pair, choice)
                }
                // 替换当前分组树中的条目
                for (resolved in resolvedEntries) {
                    updatedRoot = applyResolvedEntryToGroup(updatedRoot, resolved)
                }
            }

            val mergedDb = localDb.copy(
                rootGroup = updatedRoot,
                deletedObjects = pendingMergedTombstones
            )
            val mergedBytes = codec.serializeLocalDatabase(mergedDb)
                ?: return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_conflict_serialize_failed))

            // E2 整改：If-Match 期望值取冲突发生时刻的远端 ETag——用户决策期间
            // 远端若被再次修改，上传将 412 失败并暴露新冲突，而不是静默覆盖他端更新
            val uploadResult = engine.markResolvedAndUpload(
                path,
                mergedBytes,
                expectedEtag = pendingRemoteEtag.ifEmpty { null }
            )
            if (uploadResult.isSuccess) {
                databaseSession.updateDatabaseMeta { mergedDb }
                // H3 整改：云端已接收合并版本，本地落盘失败必须如实暴露
                val saveResult = databaseSession.save()
                clearPendingConflictSession()
                if (saveResult is KdbxResult.Failure) {
                    SyncOutcome.Error(
                        strings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
                    )
                } else {
                    SyncOutcome.MergedAndUploaded
                }
            } else {
                val ex = uploadResult.exceptionOrNull()
                if (ex is com.keepasskey.sync.model.SyncException.ConflictError) {
                    // 远端在决策期间再次更新：放弃本次解决会话（本地会话未被改写，
                    // 未同步修改仍保留在缓存中），用户重新 syncNow 将以最新远端重新检测合并
                    clearPendingConflictSession()
                    SyncOutcome.Error(strings.get(R.string.sync_error_remote_changed_during_resolve))
                } else {
                    SyncOutcome.Error(strings.get(R.string.sync_error_upload_resolved_failed, ex?.message))
                }
            }
        }
    }

    /**
     * 冲突触达点上的强制策略应用（ISSUE-P3-03 43a）。
     *
     * 「以云端为准」/「以本地为准」是单方强制策略：不做三方合并，直接落到一侧，
     * 用户在下拉选择时已由设置页文案明示「另一方修改将被覆盖」。
     *
     * @return 已处理的同步结果；返回 null 表示本策略不停机处理
     *   （自动合并 / 每次询问），调用方继续走三方合并流程。
     */
    suspend fun applyForcedConflictStrategy(
        strategy: SyncConflictStrategy,
        syncEngine: SyncEngine,
        remotePath: String,
        localBytes: ByteArray,
        remoteBytes: ByteArray
    ): SyncOutcome? = when (ConflictStrategyPolicy.dispositionOf(strategy)) {
        ConflictDisposition.TakeRemote -> {
            debugLog.warn(SYNC_LOG_TAG, "冲突解决策略=以云端为准：放弃本地未同步修改，采用远端版本")
            val applied = codec.loadAndApplyRemoteBytes(remoteBytes)
            if (!applied) {
                SyncOutcome.Error(strings.get(R.string.sync_error_load_remote_failed))
            } else {
                session.lastSyncedDb = databaseSession.databaseFlow.value
                SyncOutcome.UpToDate
            }
        }
        ConflictDisposition.TakeLocal -> {
            debugLog.warn(SYNC_LOG_TAG, "冲突解决策略=以本地为准：本地版本覆盖云端")
            when (syncEngine.commitLocalForce(remotePath, localBytes)) {
                is SyncCommitResult.Uploaded -> {
                    val saveResult = databaseSession.save()
                    if (saveResult is KdbxResult.Failure) {
                        SyncOutcome.Error(
                            strings.get(
                                R.string.sync_error_remote_updated_local_save_failed,
                                saveResult.message
                            )
                        )
                    } else {
                        session.lastSyncedDb = databaseSession.databaseFlow.value
                        SyncOutcome.UploadedLocal
                    }
                }
                // 强制上传路径无冲突分支：上传失败只可能是远端不可达
                is SyncCommitResult.RemoteUnreachable -> SyncOutcome.Offline
                is SyncCommitResult.ConflictNeedsMerge -> SyncOutcome.Offline
                // commitLocalForce 不下载远端，理论不可达；穷尽分支如实映射为拒绝回退提示（ISSUE-P2-18）
                is SyncCommitResult.RollbackRejected -> SyncOutcome.Error(
                    strings.get(R.string.sync_error_rollback_rejected)
                )
            }
        }
        ConflictDisposition.AutoMerge, ConflictDisposition.PromptUser -> null
    }

    fun clearPendingConflictSession() {
        _conflictFlow.value = emptyList()
        pendingRemoteEngine = null
        pendingRemotePath = null
        pendingLocalDb = null
        pendingRemoteDb = null
        pendingMergedRoot = null
        pendingMergedTombstones = emptyList()
        pendingRemoteEtag = ""
    }

    suspend fun handleConflictMerge(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
        remoteBytes: ByteArray,
        baseSnapshotBytes: ByteArray? = null,
        remoteEtag: String = "",
        strategy: SyncConflictStrategy = SyncConflictStrategy.AUTO_MERGE
    ): SyncOutcome = withContext(Dispatchers.Default) {
        val localDb = codec.parseKdbxBytes(localBytes)
            ?: return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_decrypt_local_conflict_failed))
        val remoteDb = codec.parseKdbxBytes(remoteBytes)
            ?: return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_decrypt_remote_conflict_failed))

        // F2 修复：base 快照必须通过三重可信检验——存在、可解析、且内容与本地字节不同
        // （本地工作副本污染判定：KDBX4 随机 IV 使同一内容的两次序列化字节必然不同，
        // 字节级 contentEquals 命中相同即证明 base 已被本地内容顶替）。
        // 不可信时严禁以本地充当 base——那会把「本地新建」误判为「远端已删除且本地未修改」
        // 而确认删除且不留墓碑，同时远端修改全胜；改为以空库充当 base，
        // 三方合并退化为双方并集语义：单侧新建保留、同 UUID 条目字段级合并、
        // 同字段分叉进入冲突清单交用户决策（宁多冲突不静默丢数据）。
        val parsedBase = baseSnapshotBytes?.let { codec.parseKdbxBytes(it) }
        val trustedBase = parsedBase?.takeIf { !baseSnapshotBytes.contentEquals(localBytes) }

        val trustedBaseLite = trustedBase?.let { KdbxDatabaseLite(it.rootGroup, it.deletedObjects) }
        val baseLite = trustedBaseLite ?: KdbxDatabaseLite(KdbxGroup(name = ""), emptyList())
        val localLite = KdbxDatabaseLite(localDb.rootGroup, localDb.deletedObjects)
        val remoteLite = KdbxDatabaseLite(remoteDb.rootGroup, remoteDb.deletedObjects)

        val mergeResult = KdbxMerger.mergeDatabases(baseLite, localLite, remoteLite)

        // ISSUE-P3-03 (43a)：PROMPT_USER（每次询问）把「双方各自修改过的条目」一并纳入决策清单，
        // 而不是只问同字段分歧的条目；其余策略沿用合并引擎给出的冲突清单
        val decisionConflicts = if (ConflictStrategyPolicy.dispositionOf(strategy) ==
            ConflictDisposition.PromptUser
        ) {
            BothModifiedEntryCollector.collect(
                trustedBase = trustedBaseLite,
                local = localLite,
                remote = remoteLite,
                alreadyConflicted = mergeResult.conflicts
            )
        } else {
            mergeResult.conflicts
        }

        if (decisionConflicts.isNotEmpty()) {
            _conflictFlow.value = decisionConflicts
            pendingRemoteEngine = syncEngine
            pendingRemotePath = remotePath
            pendingLocalDb = localDb
            pendingRemoteDb = remoteDb
            pendingMergedRoot = mergeResult.mergedRoot
            pendingMergedTombstones = mergeResult.mergedDeletedObjects
            pendingRemoteEtag = cleanEtag(remoteEtag)
            SyncOutcome.ConflictNeedsUser(decisionConflicts)
        } else {
            // 无条目级冲突，自动合并
            val mergedDb = localDb.copy(
                rootGroup = mergeResult.mergedRoot,
                deletedObjects = mergeResult.mergedDeletedObjects
            )
            val mergedBytes = codec.serializeLocalDatabase(mergedDb)
                ?: return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_serialize_merged_failed))

            val uploadResult = syncEngine.markResolvedAndUpload(remotePath, mergedBytes)
            if (uploadResult.isSuccess) {
                databaseSession.updateDatabaseMeta { mergedDb }
                val saveResult = databaseSession.save()
                if (saveResult is KdbxResult.Failure) {
                    SyncOutcome.Error(
                        strings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
                    )
                } else {
                    SyncOutcome.MergedAndUploaded
                }
            } else {
                SyncOutcome.Error(
                    strings.get(R.string.sync_error_upload_merged_failed, uploadResult.exceptionOrNull()?.message)
                )
            }
        }
    }

    private fun applyResolvedEntryToGroup(
        group: com.keepasskey.core.model.KdbxGroup,
        entry: KdbxEntry
    ): com.keepasskey.core.model.KdbxGroup {
        val targetParentId = entry.parentGroupId ?: group.id
        if (group.id == targetParentId) {
            val idx = group.entries.indexOfFirst { it.id == entry.id }
            val newEntries = group.entries.toMutableList()
            if (idx >= 0) {
                newEntries[idx] = entry
            } else {
                newEntries.add(entry)
            }
            return group.copy(entries = newEntries)
        }
        val newSubs = group.subgroups.map { applyResolvedEntryToGroup(it, entry) }
        return group.copy(subgroups = newSubs)
    }
}
