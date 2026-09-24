package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.engine.RemoteAdoptionSettlement
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户裁决与上传回写（ISSUE-P3-305 自 [SyncConflictController] 同包下沉，函数体逐行未改）。
 *
 * 形态沿用同包既有先例 `SyncConflictRemoteOutcomes` 系列与 `SyncConflictMergeAdjudication.kt`：
 * 门面类的分支实现移为**同包 `internal` 扩展函数**。本文件承接「待决会话的**终结路径**」——
 * 以三方合并产物为底版应用用户决策并上传（[resolveConflicts]）、云端已接收后的本地采纳
 * （`adoptMergedDatabase`）、以及上传 412 的重入收敛（`handleResolveUploadSuperseded`）。
 *
 * 可见性代价：[SyncConflictController] 的 `pending*` 待决字段、`_conflictFlow`、
 * `databaseSession` / `codec` / `strings` 与 `eraseSupersededPendingTrees` 由 `private`
 * 放宽为 `internal`（仅同模块可见）。**归属未变**——`pending*` 的唯一持有者仍是
 * [SyncConflictController]（其 `clearPendingConflictSession` 是唯一的丢弃点，
 * 擦除契约见 `docs/architecture/敏感缓冲所有权契约.md` §3 / §4）；本文件只读取与清空它们。
 */

/**
 * 解决冲突并执行最终提交回写。
 * [resolutions] 为条目级决策（默认兜底）；[fieldResolutions]（TASK-30 整改）为字段级
 * 决策——键为条目 ID，值为「字段键 → 选择」映射，非空时该条目按字段粒度合并
 * （本地为底版、远端仅覆写用户钦点字段），取代整条目二选一的塌缩行为。
 *
 * ISSUE-P3-25：调用方（`SyncCoordinator.resolveConflicts`）已持有会话互斥锁，本方法内不再取锁。
 */
internal suspend fun SyncConflictController.resolveConflicts(
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
        val decisionConflicts = _conflictFlow.value
        // A1 整改：以三方合并产物为底版应用用户决策。mergedRoot 含远端新增
        // 条目/分组、非冲突字段级合并与标签并集；若从纯 localDb 重建，
        // 这些合并成果将随冲突决策一并丢失（静默数据丢失）
        var updatedRoot = pendingMergedRoot ?: localDb.rootGroup

        // ISSUE-P3-161：先把全部冲突的裁决结果**收集成表**（按目标父组 id 分组），再**单趟**落树。
        // 原实现对每条裁决条目各调一次 applyResolvedEntryToGroup，而后者对**每一层**都执行
        // `subgroups.map { … }` + `copy`（目标不在该子树时也照旧复制）
        // ⇒ O(裁决条目数 × 分组数) 次分组对象与列表分配，且全程在 Dispatchers.Default + 会话锁内。
        val resolvedByParent = collectResolvedByParent(decisionConflicts, resolutions, fieldResolutions, updatedRoot.id)
        if (resolvedByParent.isNotEmpty()) {
            updatedRoot = applyResolvedEntriesToGroup(updatedRoot, resolvedByParent)
        }

        // ISSUE-P3-292：合并历史在序列化 / 上传 / 落库之前按库级 Meta 上限截断
        // （上传字节与落库树取自同一产物，不存在「本地截了、远端没截」）
        val mergedDb = truncateMergedHistory(
            localDb.copy(
                rootGroup = updatedRoot,
                deletedObjects = pendingMergedTombstones,
                // ISSUE-P2-280：合并图标池随决策一并采用（同 autoMergeAndUpload 口径）
                customIcons = pendingMergedCustomIcons
            )
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
private fun SyncConflictController.collectResolvedByParent(
    decisionConflicts: List<ConflictedEntryPair>,
    resolutions: Map<String, ConflictResolutionChoice>,
    fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>>,
    rootId: KdbxUuid
): Map<KdbxUuid, List<KdbxEntry>> {
    val resolvedByParent = mutableMapOf<KdbxUuid, MutableList<KdbxEntry>>()
    for (pair in decisionConflicts) {
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

/**
 * 云端已接收合并版本后的本地采纳与落盘。
 *
 * ISSUE-P2-308：[settlement] 非 null（commitLocal 重入路径）时基线前移随采纳结论结算——
 * 校验-采用失败或落盘失败 reject（基线保持原状，下轮按冲突流程收敛），全部成功 accept。
 * null（markResolvedAndUpload 主路径，其基线前移仍在引擎内即时落地）保持既有行为。
 */
private suspend fun SyncConflictController.adoptMergedDatabase(
    mergedDb: KdbxDatabase,
    settlement: RemoteAdoptionSettlement? = null
): SyncOutcome {
    // ISSUE-P2-278：采纳前「校验-采用」——上传的网络往返窗口内会话仍可能被本地编辑
    // 替换（resolveConflicts 入口校验之后的残余窗口），此时合并产物已不覆盖该编辑，
    // 静默整树替换将使其从内存与文件同时消失 ⇒ 如实中止，下轮同步按冲突流程收敛。
    val sessionSnapshot = pendingSessionSnapshot
    val adopted = sessionSnapshot != null &&
        databaseSession.adoptDatabaseIfUnchanged(sessionSnapshot, mergedDb)
    if (!adopted) {
        // 未采用：合并产物与待决树全部失去持有者，按身份集合判定擦除（不含会话快照别名）
        settlement?.reject()
        eraseSupersededPendingTrees(mergedDb)
        clearPendingConflictSession()
        return SyncOutcome.Error(strings.get(R.string.sync_error_local_changed_during_sync))
    }
    // H3 整改：云端已接收合并版本，本地落盘失败必须如实暴露
    val saveResult = databaseSession.save()
    clearPendingConflictSession()
    return if (saveResult is KdbxResult.Failure) {
        // ISSUE-P2-308：落盘失败 ⇒ 基线保持原状不前移
        settlement?.reject()
        SyncOutcome.Error(
            strings.get(R.string.sync_error_merged_upload_local_save_failed, saveResult.message)
        )
    } else {
        settlement?.accept()
        SyncOutcome.MergedAndUploaded
    }
}

/**
 * 用户裁决上传遭 412：先以合并产物为本地侧重取最新远端（远端若已回退到基线内容，
 * 本次上传即成功，落入与既有成功分支同语义的采纳路径）。
 */
private suspend fun SyncConflictController.handleResolveUploadSuperseded(
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
            adoptMergedDatabase(mergedDb, fresh.settlement)
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
