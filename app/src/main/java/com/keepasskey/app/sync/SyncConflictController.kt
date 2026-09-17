package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
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

            // ISSUE-P3-161：先把全部冲突的裁决结果**收集成表**（按目标父组 id 分组），再**单趟**落树。
            // 原实现对每条裁决条目各调一次 applyResolvedEntryToGroup，而后者对**每一层**都执行
            // `subgroups.map { … }` + `copy`（目标不在该子树时也照旧复制）
            // ⇒ O(裁决条目数 × 分组数) 次分组对象与列表分配，且全程在 Dispatchers.Default + 会话锁内。
            val resolvedByParent = mutableMapOf<KdbxUuid, MutableList<KdbxEntry>>()
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
                for (resolved in resolvedEntries) {
                    // 父组缺失时落根组——与逐条实现的 `entry.parentGroupId ?: group.id`（顶层即根组）同语义
                    val parentId = resolved.parentGroupId ?: updatedRoot.id
                    resolvedByParent.getOrPut(parentId) { mutableListOf() }.add(resolved)
                }
            }
            if (resolvedByParent.isNotEmpty()) {
                updatedRoot = applyResolvedEntriesToGroup(updatedRoot, resolvedByParent)
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

    /**
     * 三方合并（`ISSUE-P3-168` ①：本地侧优先取**内存树**，不再把刚序列化出的字节解析回树）。
     *
     * @param localDbOverride 本地树的**内存快照**；非 null 表示调用方保证其内容与 [localBytes]
     *   等价——两种可证情形：① [localBytes] 正是由该树序列化而来；②
     *   [SyncContentChangeDetector] 刚判定「本地内容无变化」⇒ 该树内容 ≡ 缓存快照字节。
     *   此时省去一次 `parseKdbxBytes(localBytes)`（一次 KDF + 一次整树构建）。
     *
     *   **所有权**：传入的树属于调用方 / 会话，本方法**绝不**擦除它（见 [wipeDiscarded] 前提），
     *   且合并产物会**别名复用**其节点（`KdbxMerger` 对单侧独有对象按原实例复用）——成功路径下
     *   由 [DatabaseSession.updateDatabaseMeta] 采用为会话库，与「解析产物被采用」的既有语义一致；
     *   失败路径只丢弃合并产物（其节点仍由会话树持有）。为 null 时回落「解析 [localBytes]」路径，
     *   语义与改动前逐字一致。
     */
    suspend fun handleConflictMerge(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
        remoteBytes: ByteArray,
        baseSnapshotBytes: ByteArray? = null,
        remoteEtag: String = "",
        strategy: SyncConflictStrategy = SyncConflictStrategy.AUTO_MERGE,
        localDbOverride: KdbxDatabase? = null
    ): SyncOutcome = withContext(Dispatchers.Default) {
        val localDb = localDbOverride ?: codec.parseKdbxBytes(localBytes)
            ?: return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_decrypt_local_conflict_failed))
        // 该树是否为本方法**自己解析出的独立副本**：只有这种树才允许擦除。传入的内存快照
        // 属于会话 / 调用方，擦除它等于静默清空活动库（P0 级，见 [wipeDiscarded] 前提）
        val localDbOwned = localDbOverride == null
        val remoteDb = codec.parseKdbxBytes(remoteBytes)
            ?: run {
                // ISSUE-P3-119：远端解析失败即整体放弃，localDb 无处可去（未被任何存活对象引用），
                // 显式擦除而非留给 GC；ISSUE-P3-168：传入的内存快照不属于本方法，绝不擦除
                if (localDbOwned) wipeDiscarded(localDb)
                return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_decrypt_remote_conflict_failed))
            }

        // F2 修复：base 快照必须通过三重可信检验——存在、可解析、且内容与本地字节不同
        // （本地工作副本污染判定：KDBX4 随机 IV 使同一内容的两次序列化字节必然不同，
        // 字节级 contentEquals 命中相同即证明 base 已被本地内容顶替）。
        // 不可信时严禁以本地充当 base——那会把「本地新建」误判为「远端已删除且本地未修改」
        // 而确认删除且不留墓碑，同时远端修改全胜；改为以空库充当 base，
        // 三方合并退化为双方并集语义：单侧新建保留、同 UUID 条目字段级合并、
        // 同字段分叉进入冲突清单交用户决策（宁多冲突不静默丢数据）。
        val parsedBase = baseSnapshotBytes?.let { codec.parseKdbxBytes(it) }
        val trustedBase = parsedBase?.takeIf { !baseSnapshotBytes.contentEquals(localBytes) }
        // ISSUE-P3-119：被判为「不可信 base」的解析产物随即被丢弃（baseSnapshotBytes 已被本地
        // 内容顶替），不再被任何存活对象引用 → 显式擦除。
        if (parsedBase != null && trustedBase == null) wipeDiscarded(parsedBase)

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
                ?: run {
                    // ISSUE-P3-119：序列化失败即整体放弃本次合并（mergedDb / 双方树均不被采用），
                    // 三棵解析产物同批显式擦除（合并产物本身也在此丢弃，不存在共享引用者）。
                    // ISSUE-P3-168：`localDb` 若来自调用方的内存快照则不属于本方法，跳过擦除
                    if (localDbOwned) wipeDiscarded(localDb)
                    wipeDiscarded(remoteDb)
                    wipeDiscarded(trustedBase)
                    return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_serialize_merged_failed))
                }

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

    /**
     * ISSUE-P3-119：丢弃「仅服务本次判定 / 合并、且不被任何存活对象引用」的解析产物前**显式擦除**。
     *
     * 使用前提（**逐点确认，不可套用**）：
     * - 该树的节点**没有**被 [KdbxMerger] 产物或会话库继续引用——合并器对「远端独有 / 本地独有」
     *   条目**复用原对象**（非深拷贝），因此
     *   「已被 [DatabaseSession.updateDatabaseMeta] 采用为会话库的树」与
     *   「其节点进入待决合并底版 / pending 快照的树」**一律不得擦除**，否则会静默清空活动库内容；
     * - 该树也不会在本次调用返回后被读取（如 `pendingLocalDb` / `pendingRemoteDb` 会在用户决策阶段
     *   再次被读取，故其释放只能随冲突会话结束由会话生命周期收口）；
     * - `ISSUE-P3-168` 起 [handleConflictMerge] 的 `localDbOverride` **不是**本类的解析产物——
     *   它属于调用方 / 会话（内存树快照），一律不得擦除（由 `localDbOwned` 判据把关），
     *   否则会静默清空活动库。
     */
    private fun wipeDiscarded(db: KdbxDatabase?) {
        db?.clearSensitiveData()
    }

    /**
     * `ISSUE-P3-161`：批量把已裁决条目落回分组树——**单趟**递归。
     *
     * 原实现（`applyResolvedEntryToGroup`，逐条版）对**每条**裁决条目复制整棵树：
     * 对每一层都执行 `subgroups.map { … }` + `copy`，目标不在该子树时也照旧复制
     * ⇒ O(裁决条目数 × 分组数) 次分组对象与列表分配。本实现按「父组 id → 待落位条目」表
     * 一次递归到底，且**只复制确实含目标的分组**（未命中子树按同一实例复用，
     * 与 `SessionTreeEditor` 的路径复制契约同口径）。
     *
     * 语义与逐条应用等价：同一父组内的条目按**原冲突顺序**逐个「找到即替换、找不到即追加」；
     * `parentGroupId` 指向的分组在树中不存在时同样不落位（与逐条实现一致）。
     */
    private fun applyResolvedEntriesToGroup(
        group: KdbxGroup,
        byParent: Map<KdbxUuid, List<KdbxEntry>>
    ): KdbxGroup {
        val own = byParent[group.id]
        var newEntries: List<KdbxEntry>? = null
        if (!own.isNullOrEmpty()) {
            val working = group.entries.toMutableList()
            own.forEach { entry ->
                val idx = working.indexOfFirst { it.id == entry.id }
                if (idx >= 0) {
                    working[idx] = entry
                } else {
                    working.add(entry)
                }
            }
            newEntries = working
        }

        var referenced: MutableList<KdbxGroup>? = null
        group.subgroups.forEachIndexed { index, sub ->
            val updated = applyResolvedEntriesToGroup(sub, byParent)
            if (updated !== sub) {
                val list = referenced ?: group.subgroups.toMutableList().also { referenced = it }
                list[index] = updated
            }
        }

        if (newEntries == null && referenced == null) return group
        return group.copy(entries = newEntries ?: group.entries, subgroups = referenced ?: group.subgroups)
    }
}
