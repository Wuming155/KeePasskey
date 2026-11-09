package com.keepasskey.app.sync

import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步会话的跨周期共享状态（ISSUE-P3-25 拆分，纯搬运）。
 *
 * 拆分前这些字段直接挂在 `SyncCoordinator` 上，被「同步周期编排」与「冲突决策」两条链路
 * 共同读写。@Singleton 保证协调器与各协作方共享同一实例，逐字段语义与拆分前等价：
 * 1. [mutex]：原 `SyncCoordinator.mutex`——同一把锁串行化同步周期与用户冲突决策；
 * 2. [lastSyncEngine] / [lastSyncedDb]：周期产物的生命周期锚点，会话锁定经 [clear] 释放；
 * 3. [isOfflineMode] / [testSyncProvider] / [testRemotePath]：原协调器字段原样搬运，
 *    对外可变性仍由 `SyncCoordinator` 的公开属性（private set）收窄，本类以 internal set 承接写入。
 */
@Singleton
class SyncSessionState @Inject constructor() {

    /** 串行互斥锁：同步周期与冲突决策互斥执行（与拆分前 SyncCoordinator.mutex 为同一把锁）。 */
    val mutex = Mutex()

    /** 离线模式开关：开启后同步引擎直接读本地缓存，不触碰网络 */
    @Volatile
    var isOfflineMode: Boolean = false
        internal set

    // 最近一次同步周期使用的引擎实例，用于事后抽取事件 replayCache（每次 syncNow 都会创建新引擎）
    // ENG-20261028-42：新引擎登记一律经 replaceSyncEngine——替换时按「是否仍被待决冲突留存」
    // 决定是否释放旧引擎的 Provider 凭据。读侧仍可直接读取本属性。
    var lastSyncEngine: SyncEngine? = null
        private set

    /** 本轮周期的缓存视图；仅用于在周期结束时持久化已确认的 ETag，锁定时随会话释放。 */
    @Volatile
    var lastSyncCache: SyncCache? = null

    // ISSUE-P1-07：lastSyncedDb 持有整棵 KdbxDatabase 树（含全部 ProtectedString），
    // 必须随会话终止而释放，故在会话锁回调中置空（见 clear()）。
    // ISSUE-P3-235 G2 复核：其**全部**赋值点右侧均为 `databaseSession.databaseFlow.value`
    // ⇒ 它是**会话活动树的引用**（S/B 类），擦除由 DatabaseSession 对活动树的那一次
    // `clearSensitiveData()` 承担，**只丢引用**即为正确形态（勿在此逐树递归擦除——
    // 新树会共享未修改子树引用，见 KdbxGroup.clearSupersededSensitiveData 的红线）。
    // 与之不同，冲突待决期的 pending* 树（SyncConflictController）不属活动树，
    // 由 clearPendingConflictSession() 按身份集合判定擦除。
    var lastSyncedDb: KdbxDatabase? = null

    /**
     * ISSUE-P2-624：本轮经结算确认与远端一致的确切数据库实例。
     * 周期收尾在 DatabaseSession 提交锁内复核此身份后才允许发布文件检查点。
     */
    @Volatile
    var checkpointDatabase: KdbxDatabase? = null

    /**
     * ISSUE-P3-381：上次同步周期记录的远端 ETag（回前台探测的比对基线）。
     * 会话锁定时随 [clear] 一并清空。
     */
    @Volatile
    var lastRemoteEtag: String = ""

    /**
     * ISSUE-P3-381 / `ISSUE-P2-645`：与 [lastRemoteEtag] 配对的目标身份。
     * ETag 只可与建立它时的同一非秘密目标身份比较；会话锁定时随 [clear] 清空。
     */
    @Volatile
    var probeBaselineTargetIdentity: String? = null

    // 允许单元测试注入模拟 Provider 与测试路径（禁止生产代码赋值）
    var testSyncProvider: SyncProvider? = null
        internal set

    var testRemotePath: String? = null
        internal set

    /**
     * ISSUE-P1-07：会话终止（锁定/关闭/换库前置释放）时释放协调器持有的周期产物引用。
     * 回调在会话锁内同步触发，此处仅置空引用，不做任何阻塞操作。
     *
     * `ISSUE-P3-235` G2：本方法**不做**树擦除——[lastSyncedDb] 是会话活动树的引用（见其字段注释），
     * 擦除由 `DatabaseSession` 对活动树的那一次 `clearSensitiveData()` 承担；
     * 冲突待决期的解析树由 [com.keepasskey.app.sync.SyncConflictController.clearPendingConflictSession]
     * 按身份集合判定擦除。
     *
     * ENG-20261028-42：本方法**是**引擎所用 Provider 凭据的确定性失效点——置空
     * [lastSyncEngine] 之前先经 [SyncEngine.releaseProviderCredentials] 释放（幂等）。
     * 会话终止后待决冲突随会话结束丢弃（`SyncCoordinator.onSessionLocked` 紧接着调用
     * `clearPendingConflictSession`），因此此处释放不会切断任何后续裁决上传。
     * 借用来的测试 Provider 不属本会话所有，跳过。
     */
    fun clear() {
        // 会话终止：活动树已被 DatabaseSession 擦除，待决冲突随会话丢弃（随后由
        // SyncCoordinator.onSessionLocked 的 clearPendingConflictSession 收口），
        // 因此此处释放不会切断任何后续裁决上传。
        releaseProviderCredentials()
        lastSyncedDb = null
        checkpointDatabase = null
        lastSyncEngine = null
        lastSyncCache = null
        lastRemoteEtag = ""
        probeBaselineTargetIdentity = null
    }

    /**
     * ENG-20261028-42：确定性释放本会话最近周期引擎所用 Provider 的凭据副本（幂等）。
     *
     * 与 [clear] 分开暴露，因为「配置撤销」只需失效凭据，不需要一并丢弃
     * [lastSyncedDb] / [lastRemoteEtag] 等会话产物语义（那些由 [clear] 负责）。
     * 待决冲突引擎（另一个实例）由 `SyncConflictController` 的同一能力收口。
     */
    fun releaseProviderCredentials() {
        releaseEngineCredentials(lastSyncEngine)
    }

    /**
     * 登记新周期的引擎，并在旧引擎不再被任何人使用时释放其 Provider 凭据（ENG-20261028-42）。
     *
     * 每次同步周期都会新建引擎与 Provider；旧引擎若只被本字段引用，替换后即不可达，
     * 其凭据副本不该继续驻留到会话结束。
     *
     * @param isRetained 旧引擎是否仍被其它存活消费者持有（生产接线为「冲突待决期的引擎」——
     *   该引擎会在用户稍后裁决时继续发网络写，提前失效会让裁决上传以鉴权失败告终）。
     *   判定为 true 时**不释放**，由持有方在丢弃该引擎时收口。
     */
    fun replaceSyncEngine(engine: SyncEngine, isRetained: (SyncEngine) -> Boolean = { false }) {
        val previous = lastSyncEngine
        lastSyncEngine = engine
        if (previous != null && previous !== engine && !isRetained(previous)) {
            releaseEngineCredentials(previous)
        }
    }

    /**
     * 释放引擎的 Provider 凭据。测试注入的 Provider 是**借用**依赖（由测试自己持有与复用），
     * 不属本会话所有，故跳过——与 `SyncProviderResolver.useProvider` 对 testOverride 的既有口径一致。
     */
    private fun releaseEngineCredentials(engine: SyncEngine?) {
        if (engine == null) return
        val borrowed = testSyncProvider
        if (borrowed != null && engine.ownsProvider(borrowed)) return
        engine.releaseProviderCredentials()
    }
}
