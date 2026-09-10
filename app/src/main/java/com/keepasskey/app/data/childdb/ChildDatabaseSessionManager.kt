package com.keepasskey.app.data.childdb

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子库（复合数据库 / child database）挂载与只读会话的统一编排入口（ISSUE-P3-20 核心层）。
 *
 * 本类是本特性的**唯一对外门面**：设置页对话框、`childDatabasesCount` 与库列表的只读分区
 * 都接在这里；子库条目的只读投影经 [projectedEntries] 暴露，由库列表以**并列的只读分区**
 * 展示（ISSUE-P3-30 已接线，见本类 KDoc 末节「生命周期语义」）。
 *
 * ## 挂载点抽象（设计要点 1）
 *
 * 首版把挂载点定义在**应用侧注册表**（见 [ChildDatabaseMountStore] 的类 KDoc：
 * 已核实根库 `KdbxGroup.customData` 读写通道确实存在，但来源是设备本地量、
 * 设置动作不应改动用户库、挂载时根库可能锁定，故有意不写入根库）。
 * 隔离目标是**结构性达成**的：
 *
 * - 子库条目只以 [ChildDatabaseEntryProjection] 形式存在于本进程内存，**从不写入根库对象树**，
 *   因此与 `sync` 模块 `KdbxMerger` 的 UUID / 墓碑（`DeletedObject`）语义零交集，
 *   不存在「子库条目被根库同步误删」或「子库 UUID 污染根库合并池」的路径；
 * - 会话只用独立通道打开子库，**不调用**根库 `DatabaseSession` 的任何写方法
 *   （唯一交互是注册锁定观察者），根库状态与对象树不因挂载而改变。
 *
 * 后续收敛路径（不在本阶段）：若将来需要「挂载点随库同步」，
 * 应由拥有合并/同步语义的一方在 `database` 层定义保留命名空间
 * （建议根分组 `CustomData` 键前缀 `KeePasskey.ChildDatabase.`，只承载别名 + 凭据引用标识 +
 * 逻辑锚点，**不写设备本地 URI**），并对 `KdbxMerger` 明确该命名空间的合并规则后接入；
 * 此时应用侧注册表降级为「本机来源解析缓存」。
 *
 * ## 只读边界（设计要点 2）
 *
 * 首版**只读挂载**：子库条目在根库中呈现为只读投影（[projectedEntries] 只读、无任何写入口）。
 * 「一次保存写两个文件」的原子性超出 `DatabaseSession` 单文件事务模型，
 * 本阶段明确不做可写；[ChildDatabaseMount.readOnly] 恒为 [CHILD_DATABASE_READ_ONLY]。
 *
 * ## 凭据隔离与锁库联动（设计要点 3）
 *
 * 子库凭据走 [ChildDatabaseCredentialStore] 独立通道（与根库 `useCredentials` 无共享结构、
 * 独立清零路径）。本类在构造期即把自身注册为 [SessionLockObserver]——与
 * `SyncCacheEvictor` 复用**同一个熔断触发点**（`AutoLockSessionGuard.triggerLock` →
 * `DatabaseSession.lock()` → 观察者回调），因此手动锁定、熄屏熔断、后台超时、切换密码库
 * 一律会同步终止全部子库会话并清零凭据。回调只做内存清零，无 IO、无挂起、不回调会话 API
 * （遵守 [SessionLockObserver] 的非阻塞 / 幂等 / 自容错契约）。
 *
 * 除会话自身世代号外，本类另持「根库锁定世代」（[rootLockEpoch]）：
 * 锁定会把**在途**的子库打开判为过期（防止「先锁定、后解密完成」把凭据重新写回内存）。
 *
 * ## 同步交互不变量（设计要点 4）
 *
 * **根库同步绝不隐式上传子库文件。** `SyncCoordinator.syncNow` 只处理
 * `DatabaseSession.currentFile`（当前根库），本类既不注册任何子库路径到同步候选集合，
 * 也不写 `keepasskey_vault_meta`（库列表偏好），子库来源只落在独立的
 * [ChildDatabaseMountStore.PREFS_NAME] 中；`DatabaseSession.currentFile` /
 * `currentPathIdentifier` 在挂载前后保持原值。该不变量由 `ChildDatabaseSyncIsolationTest` 断言守护。
 *
 * ## 生命周期语义（诚实边界）
 *
 * - 挂载记录（非敏感配置）**跨重启保留**；凭据与已解密会话**永不持久化**，
 *   故进程重启或根库锁定后，挂载处于 [ChildDatabaseMountState.Closed]，
 *   需上层重新提供子库主密码才能打开（[refresh] 在凭据缺失时如实返回
 *   [ChildDatabaseFailureReason.CREDENTIAL_MISSING]，不做任何隐式尝试）。
 * - 首次挂载是**原子**的：只有真正解密成功才登记挂载；失败（含密码错误）不留半成品记录，
 *   且失败路径不保留凭据。
 * - `content://` 来源的**持久化读授权**由 UI 层在 SAF 选择时申请（见 [mount] KDoc），
 *   本层只负责读取与如实报错，不代替调用方扩权。
 * - 子库条目经 [projectedEntries] 在库列表中以**只读分区**展示（ISSUE-P3-30 已接线），
 *   但**不进入根库条目流**：列表侧只把投影转成只读展示行
 *   （`ChildVaultEntryRow`，与根库 `UiVaultEntry` 分属两个类型），
 *   既不参与搜索过滤与自动填充链路，也永不写入根库对象树——本类不因此新增任何写通道。
 */
@Singleton
class ChildDatabaseSessionManager @Inject constructor(
    private val mountStore: ChildDatabaseMountStore,
    private val credentials: ChildDatabaseCredentialStore,
    private val streamSource: ChildDatabaseStreamSource,
    private val databaseSession: DatabaseSession,
    private val debugLog: DebugLogBuffer
) : SessionLockObserver {

    /** 序列化全部挂起型变更（挂载 / 打开 / 卸载 / 刷新），避免并发挂载竞态 */
    private val mutex = Mutex()

    /** 保护会话表与根库锁定世代（同步回调也会用到，故用监视器而非协程锁） */
    private val stateLock = Any()

    private val sessions = LinkedHashMap<String, ChildReadOnlySession>()

    /** 根库锁定世代：锁定即自增，令在途子库操作及其结果作废 */
    private var rootLockEpoch: Long = 0L

    private val stateFlow = MutableStateFlow<Map<String, ChildDatabaseMountState>>(emptyMap())

    private val entriesFlow = MutableStateFlow<List<ChildDatabaseEntryProjection>>(emptyList())

    /** 已登记挂载（非敏感元数据），直接供设置页观察 */
    val mounts: StateFlow<List<ChildDatabaseMount>> get() = mountStore.mounts

    /**
     * 已挂载子库数量（`childDatabasesCount` 的**真实数据源**，替代原先硬编码的 `0`）。
     *
     * 语义：注册表长度（已挂载），与「当前已解密打开」的会话数不同——锁定后仍计入已挂载。
     */
    val mountedCount: StateFlow<Int> get() = mountStore.mountedCount

    /** 每个挂载的运行时状态（未登记的挂载 ID 不在表中，[stateOf] 回落为 [ChildDatabaseMountState.Closed]） */
    val mountStates: StateFlow<Map<String, ChildDatabaseMountState>> = stateFlow.asStateFlow()

    /** 全部已打开子库的条目只读投影（扁平合并，条目自带挂载 ID 与别名，便于根库界面分组展示） */
    val projectedEntries: StateFlow<List<ChildDatabaseEntryProjection>> = entriesFlow.asStateFlow()

    init {
        // 与 SyncCacheEvictor 同一熔断触发点：锁定/关闭根库即终止全部子库会话
        databaseSession.addLockObserver(this)
        // 进程重启后注册表已有挂载但无会话：状态如实回落 Closed（无凭据、无会话）
        publish()
    }

    /** 查询单个挂载的运行时状态；未登记或尚未纳入状态表时返回 [ChildDatabaseMountState.Closed] */
    fun stateOf(mountId: String): ChildDatabaseMountState =
        stateFlow.value[mountId] ?: ChildDatabaseMountState.Closed

    /** 查询单个挂载的当前投影（未打开时为 null） */
    fun snapshotOf(mountId: String): ChildDatabaseSnapshot? =
        (stateOf(mountId) as? ChildDatabaseMountState.Opened)?.snapshot

    /**
     * 挂载并**首次打开**一个子库（原子语义）。
     *
     * [passwordChars] / [keyFileData] 为借用语义（本类经独立通道克隆，调用方持有者负责清零）；
     * 二者皆为 null 表示「无凭据」——不会用空复合密钥试探，直接以
     * [ChildDatabaseFailureReason.CREDENTIAL_MISSING] 失败。
     *
     * **UI 层前置义务（本层刻意不做）**：`content://` 来源须在 SAF 选择后立即申请
     * **持久化读授权**（`ContentResolver.takePersistableUriPermission`，或在解锁特性侧复用
     * `com.keepasskey.app.ui.screens.unlock.KeyFileAccess.persistReadPermission(uri)`），
     * 否则进程重启后授权失效，本层只能如实返回
     * [ChildDatabaseFailureReason.SOURCE_UNAVAILABLE]——挂载能力本身无法代替调用方去申请授权。
     *
     * @return 成功返回登记后的挂载记录；失败返回 [ChildDatabaseException]
     *   （分型经 [ChildDatabaseFailureReason.of] 取回），且**不登记任何记录、不保留凭据**
     */
    suspend fun mount(
        alias: String,
        sourceUri: String,
        passwordChars: CharArray?,
        keyFileData: ByteArray?
    ): KdbxResult<ChildDatabaseMount> = mutex.withLock {
        val normalizedAlias = ChildDatabaseSourceValidator.normalizeAlias(alias)
            ?: return@withLock mountFailure(ChildDatabaseFailureReason.INVALID_ALIAS)
        val sourceKind = ChildDatabaseSourceValidator.resolveKind(sourceUri)
            ?: return@withLock mountFailure(ChildDatabaseFailureReason.INVALID_SOURCE)
        val normalizedSource = sourceUri.trim()
        if (mountStore.findBySource(normalizedSource) != null) {
            return@withLock mountFailure(ChildDatabaseFailureReason.DUPLICATE_MOUNT)
        }

        val epoch = currentRootEpoch()
        val mount = ChildDatabaseMount(
            id = newIdentifier(),
            alias = normalizedAlias,
            sourceUri = normalizedSource,
            sourceKind = sourceKind,
            credentialRefId = newIdentifier(),
            mountedAtEpochMillis = System.currentTimeMillis()
        )
        // 先入会话表：使并发锁定能命中该会话并立即清零其凭据
        val session = putSession(ChildReadOnlySession(mount, streamSource, credentials))
        val opened = session.open(passwordChars, keyFileData)
        if (opened is KdbxResult.Failure) {
            dropSession(session.mountId)
            publish()
            return@withLock KdbxResult.Failure(opened.error)
        }
        if (isRootEpochStale(epoch)) {
            // 解密期间根库已锁定：本次挂载作废（凭据由 terminate 清零，记录不登记）
            dropSession(session.mountId)
            publish()
            return@withLock mountFailure(ChildDatabaseFailureReason.TERMINATED)
        }
        if (!mountStore.register(mount)) {
            // 注册表拒绝（同身份或同来源已存在）：回收会话，如实失败，绝不产生重复挂载
            dropSession(session.mountId)
            publish()
            return@withLock mountFailure(ChildDatabaseFailureReason.DUPLICATE_MOUNT)
        }
        publish()
        debugLog.info(TAG, "子库挂载成功: ${mount.alias}")
        KdbxResult.Success(mount)
    }

    /**
     * 打开（或按显式凭据重开）一个已登记挂载。
     *
     * 用于：进程重启后重新输入子库主密码、凭据被拒后重试、来源恢复后重试。
     * 失败时挂载记录保持登记（注册表非敏感配置不因解密失败而丢失），状态如实流转。
     */
    suspend fun open(
        mountId: String,
        passwordChars: CharArray?,
        keyFileData: ByteArray?
    ): KdbxResult<ChildDatabaseSnapshot> = mutex.withLock {
        val registered = mountStore.findById(mountId)
            ?: return@withLock snapshotFailure(ChildDatabaseFailureReason.MOUNT_NOT_FOUND)
        val epoch = currentRootEpoch()
        val session = sessionFor(registered)
        val result = session.open(passwordChars, keyFileData)
        return@withLock settleWithEpoch(epoch, session, result)
    }

    /**
     * 用独立通道中已存的凭据刷新投影（不接收新凭据；无凭据时如实失败）。
     * 锁库终止后调用会得到 [ChildDatabaseFailureReason.CREDENTIAL_MISSING]。
     */
    suspend fun refresh(mountId: String): KdbxResult<ChildDatabaseSnapshot> = mutex.withLock {
        val registered = mountStore.findById(mountId)
            ?: return@withLock snapshotFailure(ChildDatabaseFailureReason.MOUNT_NOT_FOUND)
        val epoch = currentRootEpoch()
        val session = sessionFor(registered)
        val result = session.openWithStoredCredentials()
        return@withLock settleWithEpoch(epoch, session, result)
    }

    /**
     * 卸载：终止会话、清零凭据、摘除登记（幂等）。
     *
     * @return 挂载存在并已摘除返回成功；不存在返回
     *   [ChildDatabaseFailureReason.MOUNT_NOT_FOUND]
     */
    suspend fun unmount(mountId: String): KdbxResult<Unit> = mutex.withLock {
        val registered = mountStore.findById(mountId)
        val session = dropSession(mountId)
        val existed = mountStore.remove(mountId)
        publish()
        if (session == null && !existed) {
            KdbxResult.Failure(ChildDatabaseException(ChildDatabaseFailureReason.MOUNT_NOT_FOUND))
        } else {
            registered?.let { debugLog.info(TAG, "子库已卸载: ${it.alias}") }
            KdbxResult.Success(Unit)
        }
    }

    /**
     * 终止全部子库会话并清零凭据，**保留**登记（用户未卸载，仅会话下线）。
     * 锁定联动走 [onSessionLocked]；上层如需主动下线会话可直接调用本方法。
     */
    suspend fun closeAll() = mutex.withLock {
        terminateAllSessions()
    }

    /**
     * 根库锁定 / 关闭回调：同步终止全部子库会话并清零独立凭据通道。
     *
     * 契约遵守：只做内存清零与状态发布（无 IO、无挂起、不回调会话 API），幂等可重入。
     */
    override fun onSessionLocked() {
        markRootLocked()
        terminateAllSessions()
    }

    /** 打开成功后若根库已锁定，则本次结果作废（终止会话，如实返回过期） */
    private fun settleWithEpoch(
        epoch: Long,
        session: ChildReadOnlySession,
        result: KdbxResult<ChildDatabaseSnapshot>
    ): KdbxResult<ChildDatabaseSnapshot> {
        if (result.isSuccess && isRootEpochStale(epoch)) {
            session.terminate()
            publish()
            return snapshotFailure(ChildDatabaseFailureReason.TERMINATED)
        }
        publish()
        return result
    }

    /** 终止全部会话（注册表不动）并清零凭据通道；同步路径，供锁定回调直接调用 */
    private fun terminateAllSessions() {
        val snapshot = synchronized(stateLock) { sessions.values.toList() }
        snapshot.forEach { it.terminate() }
        credentials.clearAll()
        publish()
    }

    private fun sessionFor(mount: ChildDatabaseMount): ChildReadOnlySession {
        val existing = synchronized(stateLock) { sessions[mount.id] }
        if (existing != null) return existing
        return putSession(ChildReadOnlySession(mount, streamSource, credentials))
    }

    private fun putSession(session: ChildReadOnlySession): ChildReadOnlySession {
        synchronized(stateLock) { sessions[session.mountId] = session }
        return session
    }

    private fun dropSession(mountId: String): ChildReadOnlySession? {
        val removed = synchronized(stateLock) { sessions.remove(mountId) }
        removed?.terminate()
        return removed
    }

    private fun currentRootEpoch(): Long = synchronized(stateLock) { rootLockEpoch }

    private fun isRootEpochStale(epoch: Long): Boolean = synchronized(stateLock) { rootLockEpoch != epoch }

    private fun markRootLocked() {
        synchronized(stateLock) { rootLockEpoch += 1 }
    }

    /** 依注册表与会话表重建对外状态：未登记的挂载 ID 不出现，未打开一律 [ChildDatabaseMountState.Closed] */
    private fun publish() {
        val registered = mountStore.mounts.value
        val states = synchronized(stateLock) {
            val built = LinkedHashMap<String, ChildDatabaseMountState>(registered.size)
            registered.forEach { mount ->
                built[mount.id] = sessions[mount.id]?.state?.value ?: ChildDatabaseMountState.Closed
            }
            built.toMap()
        }
        stateFlow.value = states
        entriesFlow.value = states.values
            .filterIsInstance<ChildDatabaseMountState.Opened>()
            .flatMap { it.snapshot.entries }
    }

    private fun mountFailure(reason: ChildDatabaseFailureReason): KdbxResult<ChildDatabaseMount> =
        KdbxResult.Failure(ChildDatabaseException(reason))

    private fun snapshotFailure(reason: ChildDatabaseFailureReason): KdbxResult<ChildDatabaseSnapshot> =
        KdbxResult.Failure(ChildDatabaseException(reason))

    private fun newIdentifier(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val TAG = "ChildDatabaseSessionManager"
    }
}
