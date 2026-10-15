package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.di.RollbackStateDir
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.SyncConflictStrategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步周期编排（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运 + 巨型函数按步骤拆分）。
 *
 * 职责边界：串联 `DatabaseSession` 状态机、KDBX4 序列化与三哈希字节级 `SyncEngine`，
 * 产出 [SyncOutcome]；冲突决策委派 [SyncConflictController]，编解码委派 [SyncDatabaseCodec]，
 * Provider 构建委派 [SyncProviderResolver]。调度边界（`ISSUE-P2-277` 整改后已与实况对齐）：
 * - **装配段整体下沉 `Dispatchers.IO`**（`setupCycleContext`：凭据与偏好读取、整库缓存读 / 写与
 *   `fd.sync()`、tmp 写 + 原子 rename、全库内容比较）。调用方可能是 `Main.immediate`——前台入口
 *   （下拉刷新 / 解锁后自动同步 / 设置页触发）持 `viewModelScope`；装配段内的同步调用**绕开**
 *   `SyncEngine` 直调 `SyncCache`，故引擎内的 `Dispatchers.IO` 兜底覆盖不到它；
 * - 加密与合并在 `Dispatchers.Default`（`SyncConflictController` / [SyncDatabaseCodec] 内）；
 * - 网络与文件写盘在 `Dispatchers.IO`（`sync` 模块内）。
 *
 * 接缝（§155）：`openRemote` 的两条远端裁决分支（`handleRemoteSynced` / `handleConflictDetected`）
 * 与其共享上下文 `RemoteSyncContext` 已下沉到同包文件 `SyncCycleRemoteOutcomes.kt`（ISSUE-P3-188 第二档）。
 * 互斥语义：本类在 [SyncSessionState.mutex] 内执行整个周期（与拆分前 `mutex.withLock` 覆盖范围一致），
 * 周期内触达 [SyncConflictController] 的各方法不得二次取锁。
 *
 * ISSUE-P2-278：UI 写路径**不取**该互斥锁，周期起点之后用户仍可能编辑并保存——故所有
 * 「整树替换会话」的落库点（远端接管 [SyncDatabaseCodec.loadAndApplyRemoteBytes]、自动合并
 * [SyncConflictController.handleConflictMerge]、用户裁决采纳 `SyncConflictController.adoptMergedDatabase`）一律经
 * [DatabaseSession.adoptDatabaseIfUnchanged] 做「校验-采用」：会话树已非周期起点实例时
 * 如实中止本周期（`sync_error_local_changed_during_sync`），严禁静默覆盖窗口内编辑。
 *
 * ISSUE-P3-305：本文件继续按容量面分列——前置装配（`SyncCycleSetup.kt`）与步骤 2 / 3
 * 提交路径（`SyncCycleCommitPaths.kt`）各自成文件，函数体逐行搬运；本类只保留
 * 「取锁 + 装配调用 + 决策分派 + 绑定接管」。可见性代价：七个成员由 `private` 放宽为
 * `internal`（仅同模块可见，公开 API 与行为零变化）。
 */
@Singleton
class SyncCycleRunner @Inject constructor(
    @ApplicationContext internal val context: Context,
    internal val databaseSession: DatabaseSession,
    internal val session: SyncSessionState,
    internal val providerResolver: SyncProviderResolver,
    internal val codec: SyncDatabaseCodec,
    internal val conflicts: SyncConflictController,
    internal val changes: SyncContentChangeDetector,
    internal val preferences: SyncPreferences,
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
    internal val rollbackStateDir: File? = null,
    /**
     * `ISSUE-P2-291`：同步目标 ↔ 库身份绑定登记（生产由 Hilt 注入）。
     * 为 null（既有手工装配路径）时**关闭绑定闸且缓存 / 防回滚沿用旧键**（`SHA-256(remotePath)`），
     * 行为与整改前逐字节一致；非 null 时缓存 / 基线 / 防回滚键含库身份命名空间，
     * 且绑定不符的同步中止于任何网络写之前。
     */
    internal val vaultBindingStore: SyncVaultBindingStore? = null,
    /**
     * `ISSUE-P3-528`：主凭据轮换后的「云端副本待替换」持久标记（生产由 Hilt 注入）。
     * 为 null（既有手工装配路径 / 单测）时本机制整体旁路，装配期判定与整改前逐字节一致。
     */
    internal val credentialRotationStore: SyncCredentialRotationStore? = null,
    /**
     * `ISSUE-P2-536`：「本地库文件自上次成功同步以来未被重写」的进程内检查点（生产由 Hilt 注入）。
     * 为 null（既有手工装配路径 / 单测）时本机制整体旁路，占位态判定与整改前逐字节一致。
     */
    internal val localFileCheckpoint: SyncLocalFileCheckpoint? = null,
    /** ISSUE-P2-625：只在本地快照准备段挂锁；网络等待不延迟自动锁定。 */
    internal val autoLockGuard: AutoLockSessionGuard? = null
) {

    /**
     * `ISSUE-P2-536`：本轮周期已解析的远端路径（检查点记录与判定的归属键）。
     * 由 `setupCycleContext` 写入；`takeoverVaultBinding` 独立解析、不写——该路径的成功结论
     * （整库覆盖上传后两端一致）由 §372 兜底的「两端一致」臂覆盖，无须检查点参与。
     */
    @Volatile
    internal var lastCycleRemotePath: String? = null

    @Volatile
    internal var lastCycleTargetIdentity: String? = null

    /** Deterministic race seam for the post-settlement checkpoint boundary. */
    @androidx.annotation.VisibleForTesting
    internal var beforeCheckpointRecord: (suspend () -> Unit)? = null

    /** 确定性挂锁截止时间测试缝；生产路径保持为空。 */
    @androidx.annotation.VisibleForTesting
    internal var beforeLocalSnapshot: (suspend () -> Unit)? = null

    /** 可注入的本地快照挂锁预算，生产默认值为五秒。 */
    @androidx.annotation.VisibleForTesting
    internal var localSnapshotHoldoffBudgetMillis: Long = LOCAL_SNAPSHOT_HOLDOFF_BUDGET_MS

    /**
     * 测试钩子（`ISSUE-P2-277` AC③）：注入「记录调用线程」的 [SyncCache] 子类，用于断言缓存
     * 整库读 / 原子写确不在主线程执行。生产恒为 null ⇒ 就地构造真实实例，行为零变化。
     *
     * 与 [SyncSessionState.testSyncProvider] 同一口径——本仓既有的 `@VisibleForTesting` 注入模式，
     * 并以 `internal` 收窄到本模块单测可见（生产 DI 不注入，外部调用方不可见、不可写）。
     * 之所以需要注入点：本类在 `setupCycleContext` 内**自行**构造 `SyncCache`（落点与
     * `SyncCache.CACHE_DIR_NAME` 绑定），而记录型子类只有经此入口才能进入周期。
     */
    internal var syncCacheFactory: ((File) -> SyncCache)? = null

    /**
     * 执行全量同步周期的决策树（原 `SyncCoordinator.runSyncCycle` 主体，逐行搬运）。
     *
     * 周期内四个步骤的判定顺序、早退语义与缓存写入时机均保持不变；
     * 前置装配见 `setupCycleContext`（ISSUE-P3-305 起实现位于同包 `SyncCycleSetup.kt`），
     * 步骤 2 / 3 的实现位于同包 `SyncCycleCommitPaths.kt`，
     * `return@withLock` 语义由返回值等价承载。
     *
     * `ISSUE-P2-536`：决策在 [runCycleUnderLock] 内完成后，于锁外统一执行检查点记录面
     * （仅「已与远端收敛」的结论记录，判据见 [SyncOutcome.isInSyncWithRemote]）——
     * 记录放在锁外是因为它只读本地文件并写进程内摘要，不触碰任何周期共享状态。
     */
    suspend fun runSyncCycle(): SyncOutcome {
        session.checkpointDatabase = null
        var confirmedDatabase: KdbxDatabase? = null
        val outcome = try {
            runCycleUnderLock().also { confirmedDatabase = session.checkpointDatabase }
        } finally {
            // A canceled or failed cycle cannot leave a candidate for a later cycle to consume.
            session.checkpointDatabase = null
        }
        if (outcome.isInSyncWithRemote()) {
            if (confirmedDatabase != null) {
                beforeCheckpointRecord?.invoke()
                val stableRevision = databaseSession.writeIntents.digestStableFileFor(confirmedDatabase)
                localFileCheckpoint?.record(
                    lastCycleRemotePath.orEmpty(),
                    lastCycleTargetIdentity,
                    stableRevision
                )
            }
        }
        return outcome
    }

    private suspend fun runCycleUnderLock(): SyncOutcome = session.mutex.withLock {
        val currentDb = databaseSession.databaseFlow.value
            ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_vault_not_unlocked))
        autoLockGuard?.beginLongTask()
        var holdoffActive = autoLockGuard != null
        // §411 走查（ISSUE-P3-447 局部收口）：SAF（自选位置）通道的库 `associatedFile == null`，
        // 此前在首行即早退报「当前无打开的密码库文件」，既掩盖真实原因又使 SAF 库完全无法云同步。
        // 同步周期对本地文件的唯一依赖是「远端路径默认名」——本地字节恒由内存树序列化而来，
        // 故以活动文件名优先、库名兜底（`<库名>.kdbx`）解析远端路径，SAF 库得以进入同一周期。
        val vaultFileName = databaseSession.currentFile?.name ?: "${currentDb.databaseName}.kdbx"

        try {
            val result = providerResolver.useProviderWithTarget(
                defaultFileName = vaultFileName,
                testOverride = session.testSyncProvider
            ) { provider, providerTarget ->
                // ISSUE-P2-277 AC①：装配段整体下沉 `Dispatchers.IO`。整改前该段在调用方线程上执行，
                // 而前台入口（下拉刷新 / 解锁后自动 / 设置页）持 `viewModelScope`（`Main.immediate`）
                // ⇒ Keystore 解密、整库密文读 / 写 + `fd.sync()`、全库逐字段比较全部压在主线程上，
                // 大库下为数百毫秒至秒级，并与凭据提供者的应答预算争用同一条主线程队列。
                // 下沉点选在**调用处整体包裹**而非逐个函数改造：装配段内任何后续新增的同步 IO 调用
                // 自动获得同一保障（对本条四类缺陷一次性收口，且不扩散 `SyncCache` 的 API 变更面）。
                val setupWithinLockBudget = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(localSnapshotHoldoffBudgetMillis) {
                        beforeLocalSnapshot?.invoke()
                        setupCycleContext(vaultFileName, currentDb, provider, providerTarget)
                    }
                }
                if (holdoffActive) {
                    // 上下文现在只持有供网络阶段使用的不可变密文快照。
                    // 在任何 Provider 元数据、下载或上传调用前释放挂锁，避免排队和慢响应延迟用户锁定。
                    holdoffActive = false
                    autoLockGuard?.endLongTask()
                }
                val setup = setupWithinLockBudget ?: return@useProviderWithTarget SyncOutcome.Error(
                    strings.get(R.string.sync_error_snapshot_prepare_timeout)
                )
                if (setup.outcome != null) return@useProviderWithTarget setup.outcome
                val ctx = setup.context!!

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
                    val baselineOutcome = establishRemoteBaselineIfMissing(
                        provider = ctx.provider,
                        syncEngine = ctx.syncEngine,
                        remotePath = ctx.remotePath,
                        localBytes = ctx.localBytes,
                        // ISSUE-P1-591：上传的即周期起点快照，基线结算须以它为准
                        localDbSnapshot = ctx.currentDb
                    )
                    if (baselineOutcome != null) return@useProviderWithTarget baselineOutcome
                }

            // 3. 若本地为未落盘的修改态且本地已存在历史缓存基线，尝试快速提交
            // ISSUE-P3-301：本判据 `SyncCache.isCached` = `exists() && length() > 0`，是**一次 stat
            // 级系统调用**，且位于装配段 `withContext(Dispatchers.IO)` **之外**——前台入口
            // （下拉刷新 / 解锁后自动 / 设置页）持 `viewModelScope`（`Main.immediate`），
            // 故此前它在主线程上执行。按 AC①(b) 保留重取并以 `Dispatchers.IO` 包裹：
            // 语义等价（同一位置、同一表达式、同一时刻求值），只换执行线程。
            //
            // **为何不改读装配段取样值 `ctx.isCached`**（AC①(b) 要求写明）：该取样发生在
            // **步骤 2 之前**，而步骤 2 的 `establishRemoteBaselineIfMissing` **仅在**
            // `FileNotFound` 分支 `commitLocal` 之后写入缓存（其余分支返回 null 且**不写缓存**）。
            // 即「步骤 2 与步骤 3 之间缓存状态不变」依赖一条尚未显式论证 / 断言的不变量——
            // 一旦该分支行为变化，改用 `ctx.isCached` 会让快速提交路径在「刚建立基线」的同一轮
            // 被误判进入，属状态机判定改变（AC② 禁止）。本次不动状态机。
                val cachedBeforeFastCommit = withContext(Dispatchers.IO) {
                    ctx.syncCache.isCached(ctx.remotePath)
                }
                if (ctx.isDirty && cachedBeforeFastCommit) {
                    return@useProviderWithTarget tryFastCommitPath(
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
                handleOpenRemote(
                    syncEngine = ctx.syncEngine,
                    syncCache = ctx.syncCache,
                    remotePath = ctx.remotePath,
                    localBytes = ctx.localBytes,
                    localDbSnapshot = ctx.currentDb,
                    baseSnapshotBytes = ctx.baseSnapshotBytes,
                    isDirty = ctx.isDirty,
                    hasLocalContentChanged = ctx.hasLocalContentChanged,
                    localChangeState = ctx.localChangeState,
                    localFileUnchangedSinceSync = ctx.localFileUnchangedSinceSync,
                    conflictStrategy = ctx.conflictStrategy
                )
            } ?: return@withLock SyncOutcome.Error(strings.get(R.string.sync_error_no_sync_credentials))
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消原样重抛（结构化并发契约）
            throw e
        } catch (e: com.keepasskey.sync.model.SyncException.InvalidEndpointError) {
            SyncOutcome.Error(strings.get(R.string.sync_error_invalid_endpoint))
        } catch (e: Throwable) {
            // ISSUE-P0-09：openRemote 之外的周期步骤（本地序列化 / 首传基线 / 快速提交与冲突合并）
            // 同样存在 provider-引擎重抛链未覆盖的 Error 面（如合并超深结构栈溢出），
            // 一律遏制为「本次同步失败」而非进程崩溃，与 handleOpenRemote 的归一口径一致
            // ISSUE-P3-311 项 3：异常 message 可能携带服务器可控串，UI 只出固定通用文案
            SyncOutcome.Error(strings.get(R.string.sync_error_unknown))
        } finally {
            if (holdoffActive) {
                holdoffActive = false
                autoLockGuard?.endLongTask()
            }
        }
    }

    private companion object {
        /** 本地快照阶段对自动锁定的延期不得超过此预算。 */
        const val LOCAL_SNAPSHOT_HOLDOFF_BUDGET_MS = 5_000L
    }

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
    suspend fun takeoverVaultBinding(): SyncOutcome = session.mutex.withLock {
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
                val rollbackGuard = SyncRollbackGuard(
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
    private suspend fun handleOpenRemote(
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
}
