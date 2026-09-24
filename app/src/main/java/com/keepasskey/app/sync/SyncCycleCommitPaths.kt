package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.provider.SyncProvider

/**
 * 同步周期步骤 2 / 步骤 3 的提交路径（ISSUE-P3-305 自 [SyncCycleRunner] 同包下沉，函数体逐行未改）。
 *
 * 形态沿用同包既有先例 `SyncCycleRemoteOutcomes.kt`：门面类的分支实现移为**同包 `internal`
 * 扩展函数**。可见性代价：[SyncCycleRunner.preferences] 由 `private` 放宽为 `internal`
 * （仅同模块可见，公开 API 与行为零变化）；`session` / `databaseSession` / `codec` /
 * `conflicts` / `strings` 在 ISSUE-P3-188 已同口径放宽。
 */

/**
 * 步骤 2（原样搬运）：首次同步且尚未缓存时，若远端尚未创建该文件，直接上传本地库建立基线。
 *
 * @return null 表示远端已存在（本步骤无结论，调用方继续后续决策）；非 null 即本步骤的同步结论。
 */
internal suspend fun SyncCycleRunner.establishRemoteBaselineIfMissing(
    provider: SyncProvider,
    syncEngine: SyncEngine,
    remotePath: String,
    localBytes: ByteArray
): SyncOutcome? {
    val metaResult = provider.getMetadata(remotePath)
    if (metaResult.isFailure) {
        val ex = metaResult.exceptionOrNull()
        if (ex is com.keepasskey.sync.model.SyncException.FileNotFound) {
            // ISSUE-P3-180：上面的 getMetadata 已给出「远端不存在」的结论，下传给上传路径，
            // 使首传不必在 Provider 侧再探一次存在性（WebDAV 的 Overwrite 判定）
            val uploadResult = syncEngine.commitLocal(remotePath, localBytes, remoteExists = false)
            return when (uploadResult) {
                is SyncCommitResult.Uploaded -> {
                    session.lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                else -> SyncOutcome.Error(strings.get(R.string.sync_error_first_upload_failed))
            }
        } else {
            return SyncOutcome.Offline
        }
    }
    return null
}

/**
 * 步骤 3（原样搬运）：本地未落盘修改 + 已有缓存基线 → 快速提交路径。
 *
 * 前置判据 `isDirty && syncCache.isCached(remotePath)` 由调用方判定（与拆分前同一表达式）；
 * 进入本方法后三分支（Uploaded / ConflictNeedsMerge / RemoteUnreachable）恒返回结论。
 *
 * @param localDbSnapshot ISSUE-P3-168 ①：与 [localBytes] 内容等价的本地内存树，
 *   转三方合并时直接充当本地侧（免去一次「解析 localBytes 回树」的整库解密）。
 */
internal suspend fun SyncCycleRunner.tryFastCommitPath(
    syncEngine: SyncEngine,
    syncCache: SyncCache,
    remotePath: String,
    localBytes: ByteArray,
    localDbSnapshot: KdbxDatabase,
    baseSnapshotBytes: ByteArray?,
    settings: ExtendedSettings,
    conflictStrategy: SyncConflictStrategy
): SyncOutcome {
    preferences.verbose(settings, "命中快速提交路径（本地未落盘修改 + 已有缓存基线）")
    // ISSUE-P3-03 (43a)：用户关闭「同步前检查远程变更」时走无预条件覆盖上传
    // （不做 ETag 比对，本地版本直接覆盖远端）；默认开启时保持乐观锁语义不变
    val commitResult = if (settings.checkRemoteChangesBeforeSave) {
        syncEngine.commitLocal(remotePath, localBytes)
    } else {
        preferences.verbose(settings, "已关闭上传前远端比对：无 ETag 预条件覆盖上传")
        syncEngine.commitLocalForce(remotePath, localBytes)
    }
    return when (commitResult) {
        is SyncCommitResult.Uploaded -> {
            // H3 整改：缓存已上传云端但本地正式文件保存失败时如实报错，不再静默
            val saveResult = databaseSession.save()
            if (saveResult is KdbxResult.Failure) {
                return SyncOutcome.Error(
                    strings.get(R.string.sync_error_remote_updated_local_save_failed, saveResult.message)
                )
            }
            session.lastSyncedDb = databaseSession.databaseFlow.value
            SyncOutcome.UploadedLocal
        }
        is SyncCommitResult.ConflictNeedsMerge -> {
            // R3 整改：本地未同步修改必须先落盘正式库文件——冲突会话可能在
            // 用户退出/进程被杀时中断，仅存于缓存与内存的本地修改会随重启丢失
            databaseSession.save()
            // ISSUE-P3-03 (43a)：先应用「以云端为准 / 以本地为准」强制策略；
            // 返回 null（自动合并 / 每次询问）时继续走三方合并。
            // 必须整体 return —— 强制策略的结果就是本次同步结论，不得落入后续分支
            conflicts.applyForcedConflictStrategy(
                strategy = conflictStrategy,
                syncEngine = syncEngine,
                remotePath = remotePath,
                localBytes = localBytes,
                remoteBytes = commitResult.remoteBytes
            ) ?: conflicts.handleConflictMerge(
                syncEngine = syncEngine,
                syncCache = syncCache,
                remotePath = remotePath,
                localBytes = localBytes,
                remoteBytes = commitResult.remoteBytes,
                baseSnapshotBytes = baseSnapshotBytes,
                remoteEtag = commitResult.remoteEtag,
                strategy = conflictStrategy,
                // ISSUE-P3-168 ①：本地侧直接取内存树（免去一次解析回树）
                localDbOverride = localDbSnapshot,
                // ISSUE-P2-278：合并采纳前的会话守卫判据（周期起点快照）
                expectedSessionSnapshot = localDbSnapshot
            )
        }
        is SyncCommitResult.RemoteUnreachable -> SyncOutcome.Offline
        // ISSUE-P2-18：远端内容为设备侧曾接受过的旧版本（回退/重放）→ 拒绝应用并提示用户
        is SyncCommitResult.RollbackRejected -> SyncOutcome.Error(
            strings.get(R.string.sync_error_rollback_rejected)
        )
    }
}
