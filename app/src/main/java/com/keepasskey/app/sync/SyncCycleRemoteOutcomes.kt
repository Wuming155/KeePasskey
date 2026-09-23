package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.merge.SyncConflictStrategy

/**
 * 同步周期「远端裁决分支」（ISSUE-P3-188 第二档：自 [SyncCycleRunner] 同包下沉，**函数体逐字未改**）。
 *
 * 形态沿用本仓既有先例 `AutofillDatasetBuilders.kt`：门面类的分支实现移为**同包 `internal` 扩展函数**，
 * 使 `SyncCycleRunner` 只保留「取锁 + 前置装配 + 决策分派」的编排面，而分支的接缝文档与实现
 * 各自成文件。可见性代价：[SyncCycleRunner] 的 `session` / `databaseSession` / `codec` / `conflicts` /
 * `strings` 五个成员由 `private` 放宽为 `internal`（仅同模块可见，公开 API 与行为零变化）。
 */

/** `openRemote` 决策分支共享的上下文（9 项入参在分支间原样流转，聚合以免逐支透传） */
internal data class RemoteSyncContext(
    val syncEngine: SyncEngine,
    val syncCache: SyncCache,
    val remotePath: String,
    val localBytes: ByteArray,
    val localDbSnapshot: KdbxDatabase,
    val baseSnapshotBytes: ByteArray?,
    val isDirty: Boolean,
    val hasLocalContentChanged: Boolean,
    val conflictStrategy: SyncConflictStrategy
)

/** 远端与本地 ETag 一致：按「是否 identical / 本地是否有未同步修改」三分支裁决 */
internal suspend fun SyncCycleRunner.handleRemoteSynced(
    ctx: RemoteSyncContext,
    openResult: SyncOpenResult.RemoteSynced
): SyncOutcome {
    if (openResult.remoteBytes.contentEquals(ctx.localBytes)) {
        session.lastSyncedDb = databaseSession.databaseFlow.value
        return SyncOutcome.UpToDate
    }
    if (ctx.isDirty || ctx.hasLocalContentChanged) {
        // F1 修复：本地存在未同步修改（缓存被回收导致步骤 3 快速提交被跳过时
        // 尤其危险——Android 官方文档明确 cacheDir 会在存储不足时被系统自动
        // 回收，读取前必须检查存在性）。判据用 isDirty || hasLocalContentChanged：
        // 前者覆盖「内存修改未落盘」，后者覆盖「已落盘但尚未同步」（更常见，
        // isDirty 在 save() 后即复位，绝不能只看它）。严禁以远端整体覆盖会话，
        // 否则本地未上传修改将不可恢复地丢失：先按 R3 落盘本地会话，
        // 再转三方合并（base 缺失时按 F2 修复退化为双方并集合并）
        val preSave = databaseSession.save()
        if (preSave is KdbxResult.Failure) {
            return SyncOutcome.Error(
                strings.get(R.string.sync_error_conflict_presave_failed, preSave.message)
            )
        }
        return conflicts.handleConflictMerge(
            syncEngine = ctx.syncEngine,
            syncCache = ctx.syncCache,
            remotePath = ctx.remotePath,
            localBytes = ctx.localBytes,
            remoteBytes = openResult.remoteBytes,
            baseSnapshotBytes = ctx.baseSnapshotBytes,
            remoteEtag = openResult.etag,
            strategy = ctx.conflictStrategy,
            // ISSUE-P2-278：合并采纳前的会话守卫判据（周期起点快照）
            expectedSessionSnapshot = ctx.localDbSnapshot
        )
    }
    // ISSUE-P2-278：远端整库接管前携带周期起点的会话快照做「校验-采用」——
    // 装配之后（探测 / 下载的网络往返窗口）UI 写路径仍可能编辑并保存，isDirty /
    // hasLocalContentChanged 都是 setup 时的旧值；若会话树已被替换，如实中止本周期，
    // 严禁静默接管（窗口内编辑会从内存与文件同时消失）。下一轮同步按冲突流程收敛。
    return when (codec.loadAndApplyRemoteBytes(openResult.remoteBytes, ctx.localDbSnapshot)) {
        SyncDatabaseCodec.ApplyRemoteResult.APPLIED -> {
            session.lastSyncedDb = databaseSession.databaseFlow.value
            SyncOutcome.UpToDate
        }
        SyncDatabaseCodec.ApplyRemoteResult.SESSION_DIVERGED ->
            SyncOutcome.Error(strings.get(R.string.sync_error_local_changed_during_sync))
        SyncDatabaseCodec.ApplyRemoteResult.PARSE_FAILED,
        SyncDatabaseCodec.ApplyRemoteResult.SAVE_FAILED ->
            SyncOutcome.Error(strings.get(R.string.sync_error_load_remote_failed))
    }
}

/** 引擎判定为冲突：先按 R3 落盘本地会话，再按强制策略 / 三方合并裁决 */
internal suspend fun SyncCycleRunner.handleConflictDetected(
    ctx: RemoteSyncContext,
    openResult: SyncOpenResult.ConflictDetected
): SyncOutcome {
    // R3 整改：同 commitLocal 冲突路径，先落盘本地会话再进入合并
    val preSave = databaseSession.save()
    if (preSave is KdbxResult.Failure) {
        return SyncOutcome.Error(
            strings.get(R.string.sync_error_conflict_presave_failed, preSave.message)
        )
    }
    // ISSUE-P3-03 (43a)：强制策略优先；null 表示继续三方合并
    return conflicts.applyForcedConflictStrategy(
        strategy = ctx.conflictStrategy,
        syncEngine = ctx.syncEngine,
        remotePath = ctx.remotePath,
        localBytes = openResult.localBytes,
        remoteBytes = openResult.remoteBytes
    ) ?: conflicts.handleConflictMerge(
        syncEngine = ctx.syncEngine,
        syncCache = ctx.syncCache,
        remotePath = ctx.remotePath,
        localBytes = openResult.localBytes,
        remoteBytes = openResult.remoteBytes,
        baseSnapshotBytes = ctx.baseSnapshotBytes,
        remoteEtag = openResult.remoteEtag,
        strategy = ctx.conflictStrategy,
        // ISSUE-P3-168 ①：本地侧直接取内存树（localBytes 与本快照内容等价，
        // 见 runSyncCycle 内两条来源的证明），免去一次解析回树
        localDbOverride = ctx.localDbSnapshot,
        // ISSUE-P2-278：合并采纳前的会话守卫判据（周期起点快照）
        expectedSessionSnapshot = ctx.localDbSnapshot
    )
}
