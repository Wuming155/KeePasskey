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
import com.keepasskey.sync.merge.ConflictDisposition
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictStrategyPolicy
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.cleanEtag
import kotlinx.coroutines.CancellationException
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
 *
 * `ISSUE-P3-188` §166：四类**无实例状态**的合并判据（base 三重可信检验 [resolveTrustedBase]、
 * PROMPT_USER 的决策清单归集 [decisionConflictsOf]、丢弃树显式擦除 [wipeDiscarded]、已裁决条目
 * 单趟落树 [applyResolvedEntriesToGroup]）下沉到同包 `SyncConflictMergeAdjudication.kt`；
 * `ISSUE-P3-235` G2 的**身份集合判定擦除原语**（[eraseDiscardedDatabase] / [eraseDiscardedGroup] /
 * [eraseDiscardedParseResults]）同置该文件，本类只保留**待决冲突会话上下文**与上传 / 落库编排。
 *
 * §280：无条目级冲突的合并上传（原 `autoMergeAndUpload`）下沉同包 `SyncConflictAutoMerge.kt`；
 * 本类继续持有待决会话字段与用户裁决回写，并保持 [applyResolvedEntriesToGroup] 的单趟落树调用。
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

    // ISSUE-P2-280：待决合并的图标池（A1 同型——用户决策时须随合并产物一并采用，
    // 否则合并带入的远端图标在决策落库时丢失，customIconId 沦为悬空引用）。
    // 图标为公开素材（非敏感数据），无擦除义务
    private var pendingMergedCustomIcons: List<com.keepasskey.core.model.CustomIcon> = emptyList()

    // E2 整改：冲突发生时刻的远端 ETag。resolveConflicts 的 If-Match 期望值必须用
    // 该值而非重新探测的当前值，否则用户决策期间远端的再次更新会被静默覆盖
    private var pendingRemoteEtag: String = ""

    // ISSUE-P1-275 AC③：待决会话的同步缓存。合并上传遭 412 重新进入冲突流程时，
    // 需以缓存基准快照（basecache）作三方合并底版——待决期间基线未动，快照仍可信
    private var pendingRemoteCache: SyncCache? = null

    // ISSUE-P2-278：进入待决时刻的会话树实例（`databaseFlow.value` 的身份快照，仅作
    // 「校验-采用」判据，**绝不擦除**——它就是（或曾是）活动会话树）。用户决策窗口内
    // UI 写路径不取会话互斥锁，若该实例已被 copy-on-write 替换，说明窗口内发生了本地
    // 编辑，采纳合并产物将静默吞掉该编辑 ⇒ 必须如实中止（见 resolveConflicts 与
    // adoptMergedDatabase 两处守卫）
    private var pendingSessionSnapshot: KdbxDatabase? = null

    private val _conflictFlow = MutableStateFlow<List<ConflictedEntryPair>>(emptyList())

    /** 当前待决冲突清单（由 `SyncCoordinator.conflictFlow` 对外暴露，实例与拆分前同为单一 StateFlow）。 */
    val conflictFlow: StateFlow<List<ConflictedEntryPair>> = _conflictFlow.asStateFlow()

    /**
     * ISSUE-P1-275 AC①：冲突上传 If 预条件期望值的**唯一判据**（实现在同包
     * [conflictUploadExpectedEtag]，用户裁决与自动合并共用）。保留成员形态以便既有调用点。
     */
    internal fun expectedEtagForConflictUpload(conflictMomentEtag: String): String? =
        conflictUploadExpectedEtag(conflictMomentEtag)

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

        // ISSUE-P2-278：用户决策窗口内会话已被本地编辑替换 ⇒ 待决合并产物已不覆盖该编辑，
        // 继续上传 / 采纳将静默吞掉它。如实废弃本轮冲突会话（保留本地编辑），由用户重新同步收敛。
        val sessionSnapshot = pendingSessionSnapshot
        if (sessionSnapshot == null || databaseSession.databaseFlow.value !== sessionSnapshot) {
            clearPendingConflictSession()
            return SyncOutcome.Error(strings.get(R.string.sync_error_local_changed_during_sync))
        }

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
            val resolvedByParent = collectResolvedByParent(conflicts, resolutions, fieldResolutions, updatedRoot.id)
            if (resolvedByParent.isNotEmpty()) {
                updatedRoot = applyResolvedEntriesToGroup(updatedRoot, resolvedByParent)
            }

            val mergedDb = localDb.copy(
                rootGroup = updatedRoot,
                deletedObjects = pendingMergedTombstones,
                // ISSUE-P2-280：合并图标池随决策一并采用（同 autoMergeAndUpload 口径）
                customIcons = pendingMergedCustomIcons
            )
            val mergedBytes = codec.serializeLocalDatabase(mergedDb)
                ?: return@withContext SyncOutcome.Error(strings.get(R.string.sync_error_conflict_serialize_failed))

            // E2 整改：If-Match 期望值取冲突发生时刻的远端 ETag——用户决策期间
            // 远端若被再次修改，上传将 412 失败并暴露新冲突，而不是静默覆盖他端更新。
            // ISSUE-P1-275 AC①：期望值经唯一判据产出（与自动合并路径共用）
            val uploadResult = engine.markResolvedAndUpload(
                path,
                mergedBytes,
                expectedEtag = expectedEtagForConflictUpload(pendingRemoteEtag)
            )
            if (uploadResult.isSuccess) {
                return@withContext adoptMergedDatabase(mergedDb)
            }
            val ex = uploadResult.exceptionOrNull()
            if (ex is com.keepasskey.sync.model.SyncException.ConflictError) {
                // ISSUE-P1-275 AC③：合并上传 412 ⇒ 重新进入冲突流程，而非归一为一次性错误。
                return@withContext handleResolveUploadSuperseded(
                    engine = engine,
                    path = path,
                    mergedDb = mergedDb,
                    mergedBytes = mergedBytes
                )
            }
            SyncOutcome.Error(strings.get(R.string.sync_error_upload_resolved_failed, ex?.message))
        }
    }

    /** 将用户裁决结果按目标父组 id 归集（ISSUE-P3-161：单趟落树的前置收集）。 */
    private fun collectResolvedByParent(
        conflicts: List<ConflictedEntryPair>,
        resolutions: Map<String, ConflictResolutionChoice>,
        fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>>,
        rootId: KdbxUuid
    ): Map<KdbxUuid, List<KdbxEntry>> {
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
                val parentId = resolved.parentGroupId ?: rootId
                resolvedByParent.getOrPut(parentId) { mutableListOf() }.add(resolved)
            }
        }
        return resolvedByParent
    }

    /** 云端已接收合并版本后的本地采纳与落盘。 */
    private suspend fun adoptMergedDatabase(mergedDb: KdbxDatabase): SyncOutcome {
        // ISSUE-P2-278：采纳前「校验-采用」——上传的网络往返窗口内会话仍可能被本地编辑
        // 替换（resolveConflicts 入口校验之后的残余窗口），此时合并产物已不覆盖该编辑，
        // 静默整树替换将使其从内存与文件同时消失 ⇒ 如实中止，下轮同步按冲突流程收敛。
        val sessionSnapshot = pendingSessionSnapshot
        val adopted = sessionSnapshot != null &&
            databaseSession.adoptDatabaseIfUnchanged(sessionSnapshot, mergedDb)
        if (!adopted) {
            // 未采用：合并产物与待决树全部失去持有者，按身份集合判定擦除（不含会话快照别名）
            eraseSupersededPendingTrees(mergedDb)
            clearPendingConflictSession()
            return SyncOutcome.Error(strings.get(R.string.sync_error_local_changed_during_sync))
        }
        // H3 整改：云端已接收合并版本，本地落盘失败必须如实暴露
        val saveResult = databaseSession.save()
        clearPendingConflictSession()
        return if (saveResult is KdbxResult.Failure) {
            SyncOutcome.Error(
                strings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
            )
        } else {
            SyncOutcome.MergedAndUploaded
        }
    }

    /**
     * 用户裁决上传遭 412：先以合并产物为本地侧重取最新远端（远端若已回退到基线内容，
     * 本次上传即成功，落入与既有成功分支同语义的采纳路径）。
     */
    private suspend fun handleResolveUploadSuperseded(
        engine: SyncEngine,
        path: String,
        mergedDb: KdbxDatabase,
        mergedBytes: ByteArray
    ): SyncOutcome {
        val fresh = try {
            engine.commitLocal(path, mergedBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            eraseSupersededPendingTrees(mergedDb)
            clearPendingConflictSession()
            return SyncOutcome.Error(
                strings.get(R.string.sync_error_upload_resolved_failed, e.message)
            )
        }
        return when (fresh) {
            is SyncCommitResult.Uploaded -> {
                eraseSupersededPendingTrees(mergedDb)
                adoptMergedDatabase(mergedDb)
            }
            is SyncCommitResult.ConflictNeedsMerge -> {
                val cache = pendingRemoteCache
                // ISSUE-P2-278：重入侧的「校验-采用」判据——clearPendingConflictSession 会置空
                // 字段，须先取出；该快照即 resolveConflicts 入口校验过的会话树实例
                val sessionSnapshot = pendingSessionSnapshot
                eraseSupersededPendingTrees(mergedDb)
                clearPendingConflictSession()
                if (cache == null) {
                    SyncOutcome.Error(strings.get(R.string.sync_error_remote_changed_during_resolve))
                } else {
                    // 重入侧全新解析（localDbOverride = null），与已擦除的待决树零实例共享；
                    // base 取缓存基准快照——待决期间基线未动，仍为本轮合并所用底版
                    handleConflictMerge(
                        syncEngine = engine,
                        syncCache = cache,
                        remotePath = path,
                        localBytes = mergedBytes,
                        remoteBytes = fresh.remoteBytes,
                        baseSnapshotBytes = cache.readBaseContent(path),
                        remoteEtag = fresh.remoteEtag,
                        localDbOverride = null,
                        isReentry = true,
                        expectedSessionSnapshot = sessionSnapshot
                    )
                }
            }
            is SyncCommitResult.RemoteUnreachable -> {
                eraseSupersededPendingTrees(mergedDb)
                clearPendingConflictSession()
                SyncOutcome.Offline
            }
            is SyncCommitResult.RollbackRejected -> {
                // ISSUE-P2-18：最新远端为设备侧曾接受过的历史版本（回放），拒绝其参与合并
                eraseSupersededPendingTrees(mergedDb)
                clearPendingConflictSession()
                SyncOutcome.Error(strings.get(R.string.sync_error_rollback_rejected))
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
            // ISSUE-P2-278：用户显式「以云端为准」策略（覆盖语义已在设置页声明，不计入该条
            // 整改面），不带校验快照直采
            when (codec.loadAndApplyRemoteBytes(remoteBytes)) {
                SyncDatabaseCodec.ApplyRemoteResult.APPLIED -> {
                    session.lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UpToDate
                }
                else -> SyncOutcome.Error(strings.get(R.string.sync_error_load_remote_failed))
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

    /**
     * 丢弃待决冲突会话（`ISSUE-P3-235` G2 / `RC-02` 收口）。
     *
     * 本类是 `pendingLocalDb` / `pendingRemoteDb` / `pendingMergedRoot` 的**唯一持有者**：
     * 丢弃引用之前必须**先按身份集合判定擦除**（[eraseDiscardedDatabase] / [eraseDiscardedGroup]，
     * 存活侧 = `databaseSession.databaseFlow.value` 即当前活动会话树），
     * 不得留给 GC（契约见 `docs/architecture/敏感缓冲所有权契约.md` §3 R1 ~ R5、§4）：
     * - **决策路径**（[resolveConflicts]）：活动树即刚被 `updateDatabaseMeta` 采用（或未被改写的）
     *   会话库；`KdbxMerger` 复用进合并树的实例按身份判定为存活 ⇒ 不会误擦刚上线的活动库
     *   （无判定的裸 `clearSensitiveData()` 即 §9.6 #3 同型的 P0 级数据损坏，
     *   这正是 §52 此前对 `pending*` 只丢引用的原因）；
     * - **会话终止路径**（`SyncCoordinator.onSessionLocked`）：`DatabaseSession.lock()` 已先行擦除
     *   并置空活动树 ⇒ 存活侧为空 ⇒ 全量擦除，冲突待决期解析出的远端整树不再滞留至 GC。
     *
     * **池内二进制**（`ISSUE-P3-258` / 契约 Step 4 起）：[eraseDiscardedDatabase] 对丢弃库的
     * `binaries` 同步做**身份集合判定**擦除（存活侧共享的池条目跳过、独立解析池全量清零）——
     * 本方法由此同时收口 pending 树与 pending 池，不再把 ≤ 落盘阈值的附件明文留给 GC。
     */
    fun clearPendingConflictSession() {
        val live = databaseSession.databaseFlow.value
        pendingLocalDb?.let { eraseDiscardedDatabase(it, live) }
        pendingRemoteDb?.let { eraseDiscardedDatabase(it, live) }
        pendingMergedRoot?.let { eraseDiscardedGroup(it, live?.rootGroup) }
        _conflictFlow.value = emptyList()
        pendingRemoteEngine = null
        pendingRemotePath = null
        pendingLocalDb = null
        pendingRemoteDb = null
        pendingMergedRoot = null
        pendingMergedTombstones = emptyList()
        pendingMergedCustomIcons = emptyList()
        pendingRemoteEtag = ""
        pendingRemoteCache = null
        // ISSUE-P2-278：仅丢引用——该快照是活动会话树的身份别名，绝不列入擦除面
        pendingSessionSnapshot = null
    }

    /**
     * ISSUE-P1-275 AC③：合并上传 412 后丢弃待决会话前的**身份集合判定擦除**。
     * 此路径上合并产物从未被采纳（`updateDatabaseMeta` 未发生），待决四树相对存活侧
     * （当前活动会话树）只存在「本就该共活的别名」（如 `localDbOverride` 即会话树快照），
     * 其余实例已全部失去持有者——按 [eraseDiscardedDatabase] 的判据定点擦除，不留给 GC。
     * [mergedDb] 的实例包含字段级裁决新建的副本（不 alias 待决三树），必须一并列 Erasure 面。
     */
    private fun eraseSupersededPendingTrees(mergedDb: KdbxDatabase?) {
        val live = databaseSession.databaseFlow.value
        pendingLocalDb?.let { eraseDiscardedDatabase(it, live) }
        pendingRemoteDb?.let { eraseDiscardedDatabase(it, live) }
        pendingMergedRoot?.let { eraseDiscardedGroup(it, live?.rootGroup) }
        mergedDb?.let { eraseDiscardedDatabase(it, live) }
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
     * @param isReentry ISSUE-P1-275 AC③：是否为「合并上传 412 重入」轮。重入轮再次 412 时
     *   如实上浮类型化错误（深度界限 1 次），不得无限循环。
     * @param expectedSessionSnapshot ISSUE-P2-278：「校验-采用」判据——**同步周期起点的
     *   会话树实例**（`SyncCycleRunner` 的 `ctx.currentDb`，重入轮原样透传；用户决策 412
     *   重入侧传待决快照）。非 null 时自动合并采纳前在会话 Mutex 内校验会话树未被窗口内
     *   编辑替换，已替换则如实中止（禁静默以过期合并产物整树覆盖）；null 保持既有直采行为。
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
        localDbOverride: KdbxDatabase? = null,
        isReentry: Boolean = false,
        expectedSessionSnapshot: KdbxDatabase? = null
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

        val base = resolveTrustedBase(codec, baseSnapshotBytes, localBytes)
        // ISSUE-P2-280：镜像带图标池——合并产物含对端新增图标，落库时一并采用
        val localLite = KdbxDatabaseLite(localDb.rootGroup, localDb.deletedObjects, localDb.customIcons)
        val remoteLite = KdbxDatabaseLite(remoteDb.rootGroup, remoteDb.deletedObjects, remoteDb.customIcons)
        val mergeResult = KdbxMerger.mergeDatabases(base.trustedLite, localLite, remoteLite)
        val decisionConflicts = decisionConflictsOf(
            strategy = strategy,
            base = base,
            local = localLite,
            remote = remoteLite,
            mergeResult = mergeResult
        )

        if (decisionConflicts.isNotEmpty()) {
            beginPendingConflict(
                syncEngine, syncCache, remotePath, localDb, remoteDb, mergeResult, decisionConflicts, remoteEtag
            )
        } else {
            when (val upload = autoMergeAndUpload(
                codec = codec,
                strings = strings,
                databaseSession = databaseSession,
                syncEngine = syncEngine,
                remotePath = remotePath,
                localDb = localDb,
                localDbOwned = localDbOwned,
                remoteDb = remoteDb,
                trustedBase = base.trusted,
                mergeResult = mergeResult,
                conflictEtag = remoteEtag,
                expectedSessionSnapshot = expectedSessionSnapshot
            )) {
                is AutoMergeUploadResult.Completed -> upload.outcome
                is AutoMergeUploadResult.Superseded -> {
                    if (isReentry) {
                        // ISSUE-P1-275 AC③ 深度界限：重入后远端仍再次变更 ⇒ 如实上浮类型化错误，
                        // 不无限循环（四棵树已由 autoMergeAndUpload 在返回前擦除）
                        SyncOutcome.Error(strings.get(R.string.sync_error_remote_changed_during_resolve))
                    } else {
                        // ISSUE-P1-275 AC③：合并上传 412 ⇒ 重新进入冲突流程——以「合并产物字节」
                        // 为本地侧**全新解析**（localDbOverride = null，与已擦除的首轮树零实例共享），
                        // base 取缓存基准快照（上传失败 ⇒ 基线未动，仍为本轮合并所用底版）
                        handleConflictMerge(
                            syncEngine = syncEngine,
                            syncCache = syncCache,
                            remotePath = remotePath,
                            localBytes = upload.mergedBytes,
                            remoteBytes = upload.freshRemoteBytes,
                            baseSnapshotBytes = syncCache.readBaseContent(remotePath),
                            remoteEtag = upload.freshEtag,
                            strategy = strategy,
                            localDbOverride = null,
                            isReentry = true,
                            // ISSUE-P2-278：重入轮透传周期起点快照，校验窗口覆盖整个重入过程
                            expectedSessionSnapshot = expectedSessionSnapshot
                        )
                    }
                }
            }
        }
    }

    /** 有条目级分叉：留存本次合并底版与双方树，交用户决策（决策阶段由 pending 通道复用） */
    private fun beginPendingConflict(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localDb: KdbxDatabase,
        remoteDb: KdbxDatabase,
        mergeResult: MergeResult,
        decisionConflicts: List<ConflictedEntryPair>,
        remoteEtag: String
    ): SyncOutcome {
        // ISSUE-P3-258：新一轮待决**覆盖**旧 pending 字段前先按身份集合判定擦除旧会话
        // （对齐下方 conflictFlow 的覆盖语义）。否则被覆盖的 `pendingRemoteDb` 独立解析树
        // 与旧 `pendingMergedRoot` 的远端独有节点会在**任何会话终止事件之前**失去唯一持有者，
        // 树密文与池内附件明文只能等 GC——三事件永远追不上已不可达的对象。
        // live = 当前活动会话树：旧 `pendingLocalDb` 恒为其实例（或 copy 共享）⇒ 判定护栏不误擦。
        clearPendingConflictSession()
        _conflictFlow.value = decisionConflicts
        pendingRemoteEngine = syncEngine
        pendingRemotePath = remotePath
        pendingLocalDb = localDb
        pendingRemoteDb = remoteDb
        pendingMergedRoot = mergeResult.mergedRoot
        pendingMergedTombstones = mergeResult.mergedDeletedObjects
        pendingMergedCustomIcons = mergeResult.mergedCustomIcons
        pendingRemoteEtag = cleanEtag(remoteEtag)
        pendingRemoteCache = syncCache
        // ISSUE-P2-278：待决窗口的起点快照（活动会话树身份，非擦除对象）
        pendingSessionSnapshot = databaseSession.databaseFlow.value
        return SyncOutcome.ConflictNeedsUser(decisionConflicts)
    }
}
