package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.NoopSyncIntegrityMac
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncIntegrityMac
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步周期编排（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运 + 巨型函数按步骤拆分）。
 *
 * 职责边界：串联 `DatabaseSession` 状态机、KDBX4 序列化与三哈希字节级 `SyncEngine`，
 * 产出 [SyncOutcome]；冲突决策委派 [SyncConflictController]，编解码委派 [SyncDatabaseCodec]，
 * Provider 构建委派 [SyncProviderResolver]。调度边界与拆分前一致：
 * 加密与合并在 `Dispatchers.Default`（`SyncConflictController` / [SyncDatabaseCodec] 内），
 * 网络与文件写盘在 `Dispatchers.IO`（`sync` 模块内），本类不额外切线程。
 *
 * 互斥语义：本类在 [SyncSessionState.mutex] 内执行整个周期（与拆分前 `mutex.withLock` 覆盖范围一致），
 * 周期内触达 [SyncConflictController] 的各方法不得二次取锁。
 */
@Singleton
class SyncCycleRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession,
    private val session: SyncSessionState,
    private val providerResolver: SyncProviderResolver,
    private val codec: SyncDatabaseCodec,
    private val conflicts: SyncConflictController,
    private val changes: SyncContentChangeDetector,
    private val preferences: SyncPreferences,
    private val strings: StringsProvider,
    // ISSUE-P2-18：防回滚状态认证密钥来源（生产由 Hilt 注入 KeystoreSyncIntegrityMac；
    // 直接构造路径默认空实现 = 禁用防回滚，保持既有单测行为不变）
    private val syncIntegrityMac: SyncIntegrityMac = NoopSyncIntegrityMac
) {

    /**
     * 执行全量同步周期的决策树（原 `SyncCoordinator.runSyncCycle` 主体，逐行搬运）。
     *
     * 周期内四个步骤的判定顺序、早退语义与缓存写入时机均保持不变；
     * 各步骤的独立片段拆为下方私有方法，`return@withLock` 语义由返回值等价承载。
     */
    suspend fun runSyncCycle(): SyncOutcome = session.mutex.withLock {
        val activeFile = databaseSession.currentFile
            ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_no_open_vault_file))

        val currentDb = databaseSession.databaseFlow.value
            ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_vault_not_unlocked))

        // Wave 14 全站强制 HTTPS：遗留的 http:// 端点在 Provider 构造期被拒，
        // 此处将类型化错误上浮为用户可理解的同步失败反馈
        val provider = try {
            session.testSyncProvider ?: providerResolver.resolveProvider()
        } catch (e: SyncException.InvalidEndpointError) {
            return@withLock SyncOutcome.Error(e.message ?: strings.get(R.string.sync_error_invalid_endpoint))
        } ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_no_sync_credentials))

        // ISSUE-P1-06 整改：同步周期结束后显式擦除 S3 凭据 CharArray，
        // 杜绝 Provider 实例被 GC 前凭据长期驻留堆内存（try-finally 保证任何退出路径均擦除）
        try {
            val remotePath = session.testRemotePath ?: providerResolver.resolveRemotePath(activeFile.name)

            // ISSUE-P3-03 (43a)：本次同步周期使用的偏好快照与冲突策略（周期内恒定，避免中途偏好漂移）
            val settings = preferences.currentSettings()
            val conflictStrategy = settings.conflictResolution.toSyncStrategy()

            // ISSUE-P1-07：目录名与 SyncCacheEvictor 共用同一常量，杜绝两处字面量漂移
            val syncDir = File(context.cacheDir, SyncCache.CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
            val syncCache = SyncCache(syncDir)
            // ISSUE-P2-18：本地认证的防回滚守卫（高水位状态与缓存同目录；app 层注入 Keystore MAC）
            val rollbackGuard = SyncRollbackGuard(syncDir, syncIntegrityMac)
            val syncEngine = SyncEngine(provider, syncCache, rollbackGuard)
            // 离线开关联动：设置页开关传导至引擎决策树
            syncEngine.isOffline = session.isOfflineMode
            // ISSUE-P3-03 (43a)：关闭「同步前检查远程变更」= 上传前不比对方版本，本地修改直接覆盖远端
            syncEngine.overwriteRemoteWithoutPrecondition = !settings.checkRemoteChangesBeforeSave
            session.lastSyncEngine = syncEngine

            val isCached = syncCache.isCached(remotePath)
            val cachedSnapshotBytes = if (isCached) syncCache.readCache(remotePath) else null
            preferences.verbose(
                settings,
                "同步周期开始: cached=$isCached, dirty=${databaseSession.state.value}, " +
                    "远端比对=${settings.checkRemoteChangesBeforeSave}, 冲突策略=$conflictStrategy, " +
                    "分块上传=${settings.webdavChunkedUpload}(${settings.webdavChunkSizeMb}MB)"
            )
            // A2 整改：三方合并的 base 必须取"最后确认与远端一致"的独立内容快照（basecache）。
            // 本地缓存会被工作副本反复覆盖，绝不能再兼任 base 内容来源——
            // 否则冲突会话中断后 base 会被本地修改版污染，后续合并退化为远端全胜
            val baseSnapshotBytes = syncCache.readBaseContent(remotePath) ?: cachedSnapshotBytes
            val hasLocalContentChanged = changes.resolveLocalContentChanged(currentDb, cachedSnapshotBytes)

            // 1. 获取本地数据库字节：若无内容变更且已缓存，复用缓存规避 KDBX4 随机 IV 导致的不必要哈希漂移；否则序列化并写缓存
            val localBytes = if (!isCached || hasLocalContentChanged) {
                val bytes = codec.serializeLocalDatabase(currentDb)
                    ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_local_serialize_failed))
                if (isCached) {
                    syncCache.writeCache(remotePath, bytes)
                }
                bytes
            } else {
                cachedSnapshotBytes ?: codec.serializeLocalDatabase(currentDb)!!
            }

            val isDirty = databaseSession.state.value == DatabaseSession.SessionState.DIRTY

            // 2. 首次同步且尚未缓存：若远端尚未创建该文件，直接上传本地库建立基线
            if (!isCached) {
                establishRemoteBaselineIfMissing(
                    provider = provider,
                    syncEngine = syncEngine,
                    remotePath = remotePath,
                    localBytes = localBytes
                )?.let { return@withLock it }
            }

            // 3. 若本地为未落盘的修改态且本地已存在历史缓存基线，尝试快速提交
            if (isDirty && syncCache.isCached(remotePath)) {
                return@withLock tryFastCommitPath(
                    syncEngine = syncEngine,
                    syncCache = syncCache,
                    remotePath = remotePath,
                    localBytes = localBytes,
                    baseSnapshotBytes = baseSnapshotBytes,
                    settings = settings,
                    conflictStrategy = conflictStrategy
                )
            }

            // 4. 执行 openRemote 同步状态机决策
            return@withLock handleOpenRemote(
                syncEngine = syncEngine,
                syncCache = syncCache,
                remotePath = remotePath,
                localBytes = localBytes,
                baseSnapshotBytes = baseSnapshotBytes,
                isDirty = isDirty,
                hasLocalContentChanged = hasLocalContentChanged,
                conflictStrategy = conflictStrategy
            )
        } finally {
            // ISSUE-P1-06：同步周期结束（无论成功/失败/异常），显式擦除 S3 凭据 CharArray。
            // WebDAV 侧密码已在 resolveProvider() 构造完成后即时擦除（passwordChars 借用语义），
            // S3 侧因 Provider 需在整个同步周期内多次签名复用，故延迟至此处统一擦除。
            (provider as? S3SyncProvider)?.clearCredentials()
        }
    }

    /**
     * 步骤 2（原样搬运）：首次同步且尚未缓存时，若远端尚未创建该文件，直接上传本地库建立基线。
     *
     * @return null 表示远端已存在（本步骤无结论，调用方继续后续决策）；非 null 即本步骤的同步结论。
     */
    private suspend fun establishRemoteBaselineIfMissing(
        provider: SyncProvider,
        syncEngine: SyncEngine,
        remotePath: String,
        localBytes: ByteArray
    ): SyncOutcome? {
        val metaResult = provider.getMetadata(remotePath)
        if (metaResult.isFailure) {
            val ex = metaResult.exceptionOrNull()
            if (ex is com.keepasskey.sync.model.SyncException.FileNotFound) {
                val uploadResult = syncEngine.commitLocal(remotePath, localBytes)
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
     */
    private suspend fun tryFastCommitPath(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
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
                    strategy = conflictStrategy
                )
            }
            is SyncCommitResult.RemoteUnreachable -> SyncOutcome.Offline
            // ISSUE-P2-18：远端内容为设备侧曾接受过的旧版本（回退/重放）→ 拒绝应用并提示用户
            is SyncCommitResult.RollbackRejected -> SyncOutcome.Error(
                strings.get(R.string.sync_error_rollback_rejected)
            )
        }
    }

    /**
     * 步骤 4（原样搬运）：openRemote 同步状态机决策与异常归一。
     *
     * 调用方在 [SyncSessionState.mutex] 内调用；异常归一（NetworkError → Offline、
     * 其他异常 → Error）与拆分前 `try { when (openRemote) {...} } catch {...}` 结构逐一对应。
     */
    private suspend fun handleOpenRemote(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
        baseSnapshotBytes: ByteArray?,
        isDirty: Boolean,
        hasLocalContentChanged: Boolean,
        conflictStrategy: SyncConflictStrategy
    ): SyncOutcome {
        return try {
            when (val openResult = syncEngine.openRemote(remotePath)) {
                is SyncOpenResult.RemoteSynced -> {
                    val isIdentical = openResult.remoteBytes.contentEquals(localBytes)
                    if (isIdentical) {
                        session.lastSyncedDb = databaseSession.databaseFlow.value
                        SyncOutcome.UpToDate
                    } else if (isDirty || hasLocalContentChanged) {
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
                        conflicts.handleConflictMerge(
                            syncEngine = syncEngine,
                            syncCache = syncCache,
                            remotePath = remotePath,
                            localBytes = localBytes,
                            remoteBytes = openResult.remoteBytes,
                            baseSnapshotBytes = baseSnapshotBytes,
                            remoteEtag = openResult.etag,
                            strategy = conflictStrategy
                        )
                    } else {
                        val applied = codec.loadAndApplyRemoteBytes(openResult.remoteBytes)
                        if (!applied) return SyncOutcome.Error(strings.get(R.string.sync_error_load_remote_failed))
                        session.lastSyncedDb = databaseSession.databaseFlow.value
                        SyncOutcome.UpToDate
                    }
                }
                is SyncOpenResult.LocalWinAutoUploaded -> {
                    session.lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                is SyncOpenResult.RemoteLostRestored -> {
                    session.lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                is SyncOpenResult.CacheHitOffline -> {
                    SyncOutcome.Offline
                }
                is SyncOpenResult.RemoteUnreachableUsingCache -> {
                    SyncOutcome.Offline
                }
                is SyncOpenResult.ConflictDetected -> {
                    // R3 整改：同 commitLocal 冲突路径，先落盘本地会话再进入合并
                    val preSave = databaseSession.save()
                    if (preSave is KdbxResult.Failure) {
                        return SyncOutcome.Error(
                            strings.get(R.string.sync_error_conflict_presave_failed, preSave.message)
                        )
                    }
                    // ISSUE-P3-03 (43a)：强制策略优先；null 表示继续三方合并
                    conflicts.applyForcedConflictStrategy(
                        strategy = conflictStrategy,
                        syncEngine = syncEngine,
                        remotePath = remotePath,
                        localBytes = openResult.localBytes,
                        remoteBytes = openResult.remoteBytes
                    ) ?: conflicts.handleConflictMerge(
                        syncEngine = syncEngine,
                        syncCache = syncCache,
                        remotePath = remotePath,
                        localBytes = openResult.localBytes,
                        remoteBytes = openResult.remoteBytes,
                        baseSnapshotBytes = baseSnapshotBytes,
                        remoteEtag = openResult.remoteEtag,
                        strategy = conflictStrategy
                    )
                }
                // ISSUE-P2-18：远端内容为设备侧曾接受过的旧版本（回退/重放）→
                // 保留本地/基准、不应用远端，并给出明确用户提示
                is SyncOpenResult.RollbackRejected -> SyncOutcome.Error(
                    strings.get(R.string.sync_error_rollback_rejected)
                )
            }
        } catch (e: com.keepasskey.sync.model.SyncException.NetworkError) {
            SyncOutcome.Offline
        } catch (e: Exception) {
            SyncOutcome.Error(e.message ?: strings.get(R.string.sync_error_unknown))
        }
    }
}
