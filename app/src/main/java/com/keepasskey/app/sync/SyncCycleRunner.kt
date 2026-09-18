package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.di.RollbackStateDir
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
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
 * 接缝（§155）：`openRemote` 的两条远端裁决分支（`handleRemoteSynced` / `handleConflictDetected`）
 * 与其共享上下文 [RemoteSyncContext] 已下沉到同包文件 `SyncCycleRemoteOutcomes.kt`（ISSUE-P3-188 第二档）。
 * 互斥语义：本类在 [SyncSessionState.mutex] 内执行整个周期（与拆分前 `mutex.withLock` 覆盖范围一致），
 * 周期内触达 [SyncConflictController] 的各方法不得二次取锁。
 */
@Singleton
class SyncCycleRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val databaseSession: DatabaseSession,
    internal val session: SyncSessionState,
    private val providerResolver: SyncProviderResolver,
    internal val codec: SyncDatabaseCodec,
    internal val conflicts: SyncConflictController,
    private val changes: SyncContentChangeDetector,
    private val preferences: SyncPreferences,
    internal val strings: StringsProvider,
    /**
     * F-23 整改：防回滚状态目录，生产由 DI 注入 `filesDir/<SyncRollbackGuard.STATE_DIR_NAME>`
     * （**跨会话锁定保留**，与可丢弃的 `cacheDir/sync` 语义彻底分离）。
     *
     * 可空 + 默认 null 沿用本仓既有模式（见 [SyncCoordinator] 的可空协作者），此处**只为惰性**：
     * 手动装配路径（[SyncCoordinator] 的 `syncCycle == null` 回退分支）的假 Context 未必实现
     * `getFilesDir()`，若在构造期求值 `context.filesDir` 会直接 NPE。null 时在 [runSyncCycle]
     * 内按**同一生产落点**惰性解析；Hilt 恒注入真实目录（该路径同时禁用防回滚，见下）。
     */
    @RollbackStateDir
    private val rollbackStateDir: File? = null,
    // ISSUE-P2-18：防回滚状态认证密钥来源（生产由 Hilt 注入 KeystoreSyncIntegrityMac；
    // 直接构造路径默认空实现 = 禁用防回滚，保持既有单测行为不变）
    private val syncIntegrityMac: SyncIntegrityMac = NoopSyncIntegrityMac
) {

    /**
     * [setupCycleContext] 的产出：本次同步周期的上下文（引擎、缓存、路径与内容快照）。
     */
    private data class SyncCycleContext(
        val provider: SyncProvider,
        val syncEngine: SyncEngine,
        val syncCache: SyncCache,
        val remotePath: String,
        val isCached: Boolean,
        val settings: ExtendedSettings,
        val conflictStrategy: SyncConflictStrategy,
        val isDirty: Boolean,
        val localBytes: ByteArray,
        val baseSnapshotBytes: ByteArray?,
        val hasLocalContentChanged: Boolean,
        val currentDb: KdbxDatabase
    )

    /**
     * 周期前置装配（原 [runSyncCycle] 主体前段逐行搬运）：Provider 解析、引擎与缓存构造、
     * 偏好快照、基线内容读取与本地字节获取。
     *
     * @return [SyncOutcome] 表示前置步骤即有结论（错误早退）；null 表示装配完成，见 [outcome]。
     */
    private data class CycleSetup(val outcome: SyncOutcome?, val context: SyncCycleContext? = null)

    private suspend fun setupCycleContext(activeFile: File, currentDb: KdbxDatabase): CycleSetup {
        val provider = try {
            session.testSyncProvider ?: providerResolver.resolveProvider()
        } catch (e: SyncException.InvalidEndpointError) {
            return CycleSetup(SyncOutcome.Error(e.message ?: strings.get(R.string.sync_error_invalid_endpoint)))
        } ?: return CycleSetup(SyncOutcome.Error(strings.get(R.string.sync_error_no_sync_credentials)))

        val remotePath = session.testRemotePath ?: providerResolver.resolveRemotePath(activeFile.name)

        // ISSUE-P3-03 (43a)：本次同步周期使用的偏好快照与冲突策略（周期内恒定，避免中途偏好漂移）
        val settings = preferences.currentSettings()
        val conflictStrategy = settings.conflictResolution.toSyncStrategy()

        // ISSUE-P1-07：目录名与 SyncCacheEvictor 共用同一常量，杜绝两处字面量漂移
        val syncDir = File(context.cacheDir, SyncCache.CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
        val syncCache = SyncCache(syncDir)
        // F-23 整改：防回滚状态**不得**与可丢弃缓存同目录——此前它落在 cacheDir/sync，
        // 而 SyncCache.clear() 把它列入删除清单且由锁库 / 凭据清空触发，导致「用户锁定一次
        // 即可被云端重放旧库」。现注入 filesDir 下的持久目录（跨锁定保留），
        // 状态仅含 SHA-256 摘要 + Keystore HMAC（无明文）。
        // 注入缺失（手动装配路径）时按同一落点惰性兜底，保证两种装配方式落点一致。
        val rollbackGuard = SyncRollbackGuard(
            rollbackStateDir ?: File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
            syncIntegrityMac
        )
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
                ?: return CycleSetup(SyncOutcome.Error(strings.get(R.string.sync_error_local_serialize_failed)))
            if (isCached) {
                syncCache.writeCache(remotePath, bytes)
            }
            bytes
        } else {
            cachedSnapshotBytes ?: codec.serializeLocalDatabase(currentDb)!!
        }

        return CycleSetup(
            outcome = null,
            context = SyncCycleContext(
                provider = provider,
                syncEngine = syncEngine,
                syncCache = syncCache,
                remotePath = remotePath,
                isCached = isCached,
                settings = settings,
                conflictStrategy = conflictStrategy,
                isDirty = databaseSession.state.value == DatabaseSession.SessionState.DIRTY,
                localBytes = localBytes,
                baseSnapshotBytes = baseSnapshotBytes,
                hasLocalContentChanged = hasLocalContentChanged,
                currentDb = currentDb
            )
        )
    }

    /**
     * 执行全量同步周期的决策树（原 `SyncCoordinator.runSyncCycle` 主体，逐行搬运）。
     *
     * 周期内四个步骤的判定顺序、早退语义与缓存写入时机均保持不变；
     * 前置装配见 [setupCycleContext]，各步骤的独立片段拆为下方私有方法，
     * `return@withLock` 语义由返回值等价承载。
     */
    suspend fun runSyncCycle(): SyncOutcome = session.mutex.withLock {
        val activeFile = databaseSession.currentFile
            ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_no_open_vault_file))

        val currentDb = databaseSession.databaseFlow.value
            ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_vault_not_unlocked))

        // Wave 14 全站强制 HTTPS：遗留的 http:// 端点在 Provider 构造期被拒，
        // 此处将类型化错误上浮为用户可理解的同步失败反馈
        // ISSUE-P1-06 整改：同步周期结束后显式擦除 S3 凭据 CharArray，
        // 杜绝 Provider 实例被 GC 前凭据长期驻留堆内存（try-finally 保证任何退出路径均擦除）
        // 取 provider 与装配统一在 setupCycleContext 内完成，此处 finally 擦除持有引用
        var providerForErase: SyncProvider? = null
        try {
            val setup = setupCycleContext(activeFile, currentDb)
            if (setup.outcome != null) return@withLock setup.outcome
            val ctx = setup.context!!
            providerForErase = ctx.provider

            // ISSUE-P3-168 ①：下面的三方合并可直接以 `currentDb`（**本周期起点的内存树快照**）
            // 充当「本地侧」，无需把 localBytes 重新解析回树——两条路径都已证明二者内容等价：
            // ① `!isCached || hasLocalContentChanged`（setupCycleContext 步骤 1）：
            //    localBytes 刚由 currentDb 序列化而来；
            // ② 缓存命中且判定本地内容无变化：currentDb 内容 ≡ 缓存快照字节，而 localBytes 即该快照。
            // 由此每轮冲突少一次 `parse(localBytes)`（一次 KDF + 一次整树构建）。
            // 故意**不**在合并内重读 `databaseFlow`：UI 写路径不取本周期持有的 SyncSessionState.mutex，
            // 重读可能拿到与 localBytes 不对应的树（会与即将上传的字节产生分歧）。

            // 2. 首次同步且尚未缓存：若远端尚未创建该文件，直接上传本地库建立基线
            if (!ctx.isCached) {
                establishRemoteBaselineIfMissing(
                    provider = ctx.provider,
                    syncEngine = ctx.syncEngine,
                    remotePath = ctx.remotePath,
                    localBytes = ctx.localBytes
                )?.let { return@withLock it }
            }

            // 3. 若本地为未落盘的修改态且本地已存在历史缓存基线，尝试快速提交
            if (ctx.isDirty && ctx.syncCache.isCached(ctx.remotePath)) {
                return@withLock tryFastCommitPath(
                    syncEngine = ctx.syncEngine,
                    syncCache = ctx.syncCache,
                    remotePath = ctx.remotePath,
                    localBytes = ctx.localBytes,
                    localDbSnapshot = ctx.currentDb,
                    baseSnapshotBytes = ctx.baseSnapshotBytes,
                    settings = ctx.settings,
                    conflictStrategy = ctx.conflictStrategy
                )
            }

            // 4. 执行 openRemote 同步状态机决策
            return@withLock handleOpenRemote(
                syncEngine = ctx.syncEngine,
                syncCache = ctx.syncCache,
                remotePath = ctx.remotePath,
                localBytes = ctx.localBytes,
                localDbSnapshot = ctx.currentDb,
                baseSnapshotBytes = ctx.baseSnapshotBytes,
                isDirty = ctx.isDirty,
                hasLocalContentChanged = ctx.hasLocalContentChanged,
                conflictStrategy = ctx.conflictStrategy
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消原样重抛（结构化并发契约）
            throw e
        } catch (e: Throwable) {
            // ISSUE-P0-09：openRemote 之外的周期步骤（本地序列化 / 首传基线 / 快速提交与冲突合并）
            // 同样存在 provider-引擎重抛链未覆盖的 Error 面（如合并超深结构栈溢出），
            // 一律遏制为「本次同步失败」而非进程崩溃，与 handleOpenRemote 的归一口径一致
            SyncOutcome.Error(e.message ?: strings.get(R.string.sync_error_unknown))
        } finally {
            // ISSUE-P1-06：同步周期结束（无论成功/失败/异常），显式擦除 S3 凭据 CharArray。
            // WebDAV 侧密码已在 resolveProvider() 构造完成后即时擦除（passwordChars 借用语义），
            // S3 侧因 Provider 需在整个同步周期内多次签名复用，故延迟至此处统一擦除。
            (providerForErase as? S3SyncProvider)?.clearCredentials()
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
    private suspend fun tryFastCommitPath(
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
                    localDbOverride = localDbSnapshot
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
     *
     * @param localDbSnapshot ISSUE-P3-168 ①：与本次冲突涉及的本地字节内容等价的本地内存树，
     *   转三方合并时直接充当本地侧（免去一次「解析回树」的整库解密）。
     */
    private suspend fun handleOpenRemote(
        syncEngine: SyncEngine,
        syncCache: SyncCache,
        remotePath: String,
        localBytes: ByteArray,
        localDbSnapshot: KdbxDatabase,
        baseSnapshotBytes: ByteArray?,
        isDirty: Boolean,
        hasLocalContentChanged: Boolean,
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
            conflictStrategy = conflictStrategy
        )
        return try {
            when (val openResult = syncEngine.openRemote(remotePath)) {
                is SyncOpenResult.RemoteSynced -> handleRemoteSynced(ctx, openResult)
                is SyncOpenResult.LocalWinAutoUploaded -> {
                    session.lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                is SyncOpenResult.RemoteLostRestored -> {
                    session.lastSyncedDb = databaseSession.databaseFlow.value
                    SyncOutcome.UploadedLocal
                }
                is SyncOpenResult.CacheHitOffline -> SyncOutcome.Offline
                is SyncOpenResult.RemoteUnreachableUsingCache -> SyncOutcome.Offline
                is SyncOpenResult.ConflictDetected -> handleConflictDetected(ctx, openResult)
                // ISSUE-P2-18：远端内容为设备侧曾接受过的旧版本（回退/重放）→
                // 保留本地/基准、不应用远端，并给出明确用户提示
                is SyncOpenResult.RollbackRejected -> SyncOutcome.Error(
                    strings.get(R.string.sync_error_rollback_rejected)
                )
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
            SyncOutcome.Error(e.message ?: strings.get(R.string.sync_error_unknown))
        }
    }
}