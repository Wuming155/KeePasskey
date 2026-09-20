package com.keepasskey.app.sync

import androidx.annotation.VisibleForTesting
import com.keepasskey.database.file.KdbxDatabase
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
    var lastSyncEngine: SyncEngine? = null

    // ISSUE-P1-07：lastSyncedDb 持有整棵 KdbxDatabase 树（含全部 ProtectedString），
    // 必须随会话终止而释放，故在会话锁回调中置空（见 clear()）。
    // ISSUE-P3-235 G2 复核：其**全部**赋值点右侧均为 `databaseSession.databaseFlow.value`
    // ⇒ 它是**会话活动树的引用**（S/B 类），擦除由 DatabaseSession 对活动树的那一次
    // `clearSensitiveData()` 承担，**只丢引用**即为正确形态（勿在此逐树递归擦除——
    // 新树会共享未修改子树引用，见 KdbxGroup.clearSupersededSensitiveData 的红线）。
    // 与之不同，冲突待决期的 pending* 树（SyncConflictController）不属活动树，
    // 由 clearPendingConflictSession() 按身份集合判定擦除。
    var lastSyncedDb: KdbxDatabase? = null

    // 允许单元测试注入模拟 Provider 与测试路径（禁止生产代码赋值）
    @VisibleForTesting
    var testSyncProvider: SyncProvider? = null
        internal set

    @VisibleForTesting
    var testRemotePath: String? = null
        internal set

    /**
     * ISSUE-P1-07：会话终止（锁定/关闭/换库前置释放）时释放协调器持有的周期产物引用。
     * 回调在会话锁内同步触发，此处仅置空引用，不做任何阻塞操作。
     *
     * `ISSUE-P3-235` G2：本方法**不做**擦除——[lastSyncedDb] 是会话活动树的引用（见其字段注释），
     * 擦除由 `DatabaseSession` 对活动树的那一次 `clearSensitiveData()` 承担；
     * 冲突待决期的解析树由 [com.keepasskey.app.sync.SyncConflictController.clearPendingConflictSession]
     * 按身份集合判定擦除。
     */
    fun clear() {
        lastSyncedDb = null
        lastSyncEngine = null
    }
}
