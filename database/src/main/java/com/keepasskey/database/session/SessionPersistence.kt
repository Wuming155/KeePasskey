package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxProgress
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.database.io.WipableByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 会话落盘与凭据轮换（ISSUE-P3-305 结构拆分，逐行搬运）。
 *
 * 承接拆分前内联在 [DatabaseSession] 内的三条**整库序列化写盘**路径——[save]（保存活动库）、
 * [exportToBytes]（SAF 导出字节流）、[changeCredentials]（换密后立即以新凭据重加密写盘）。
 * 三者的共同形态是「具名序列化缓冲 → `Dispatchers.Default` 加密 → 落盘 → 缓冲清零」，
 * 故共享 [serializeToBytes] 单一实现（原为三处逐字重复的同一段代码）。
 *
 * 可见性与锁语义：类为 `internal`，由 [DatabaseSession] 门面持有；`save` / `changeCredentials`
 * 仍在 [mutex] 临界区内执行（与拆分前 `DatabaseSession` 自持该锁的覆盖范围逐行等价），
 * 只读态判定、失败回滚顺序与凭据擦除时机一律未改。
 */
/**
 * `ISSUE-P1-631`（生命周期收口）：预序列化产物——密文整库字节 + 修剪后的候选树根（**未发布**）。
 *
 * [bytes] 即整库密文（头部外全加密），任何退出路径（提交消耗 / 失败 / 取消 / 放弃）都必须
 * [wipe] 清零，与 `saveLocked` 对 `serialized` 的 P3-551 义务同型；[prunedRoot] 仅在
 * `SessionPersistence.commitPreparedSave` 成功路径被发布。实例只能由预备段创建（构造器 internal），
 * 调用方（漂移处置）拥有并负责用毕清零。
 */
class PreparedSerializedSave internal constructor(
    internal val bytes: ByteArray,
    internal val prunedRoot: KdbxGroup
) {
    /** 密文缓冲就地清零（幂等；提交段与调用方 finally 都可调用） */
    fun wipe() {
        bytes.fill(0)
    }
}

internal class SessionPersistence(
    private val mutex: Mutex,
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val fileWriter: SessionFileWriter,
    /**
     * ISSUE-P3-368：保存链进度上报（0..1 确定进度 / null 分段不确定段）。
     * 由 `DatabaseSession` 注入 StateFlow 写入口；只承载数值，不捕获任何敏感引用（AC③）。
     */
    private val progress: (Float?) -> Unit = {},
    /** 成功落盘后在同一会话 Mutex 内发布候选；失败不触碰活动树。 */
    private val publishDatabaseMeta: (KdbxDatabase, KdbxDatabase) -> Boolean = { _, _ -> false },
    /** `ISSUE-P1-631`（生命周期收口）：锁内「校验 + 采用」由内容变更单元提供（同一实现，不复制判据）。 */
    private val mutations: SessionContentMutations? = null
) {
    private val metadataCommitter = DatabaseMetaCommitter(
        mutex,
        core,
        credentials,
        progress,
        publishDatabaseMeta
    ) { database, password, keyFile, onProgress ->
        serializeToBytes(database, password, keyFile, onProgress)
    }

    private val rotation = SessionCredentialRotation(core, credentials, fileWriter) { db, pwd, key ->
        serializeToBytes(db, pwd, key)
    }

    /**
     * 保存当前内存中的活动数据库并写入文件/URI 通道
     */
    suspend fun save(): KdbxResult<Unit> = mutex.withLock {
        try { saveLocked() }
        catch (changed: com.keepasskey.database.exception.KdbxSessionChangedException) {
            KdbxResult.Failure(changed, code = KdbxError.SAVE_SESSION_CHANGED)
        }
    }

    /**
     * `ISSUE-P1-631`（原子采纳 + 落盘）：[save] 的**无锁内核**（已在会话临界区内调用）。
     *
     * 抽出原因同 `SessionContentMutations.adoptDatabaseIfUnchangedLocked`：采纳与落盘必须在
     * **同一次**会话临界区内完成，否则窗口内的换库会让 `save()` 把「后来会话」的内容写到
     * 「后来会话」的目标上，而调用方据返回值误判「本次合并已保存」。
     *
     * 语义与 [save] **逐字相同**，仅去掉取锁（`Mutex` 不可重入，带锁版在锁内再调即自锁）。
     */
    internal suspend fun saveLocked(): KdbxResult<Unit> {
        requireCurrentSessionRequest(core.credentialTicket)
        if (core.credentialWriteUncertain.value) {
            return KdbxResult.Failure(
                IllegalStateException("凭据写入结果不确定，须先核实或恢复文件"),
                code = KdbxError.SAVE_WRITE_UNCERTAIN
            )
        }
        if (core.readOnlyMode) {
            return KdbxResult.Failure(
                IllegalStateException("数据库以只读模式打开"),
                code = KdbxError.SAVE_READ_ONLY
            )
        }
        return withContext(Dispatchers.Default) {
            val writer = core.saveWriter ?: return@withContext KdbxResult.Failure(
                IllegalStateException("无活动数据库保存通道"),
                code = KdbxError.SAVE_NO_WRITER
            )
            val db = core.database.value ?: return@withContext KdbxResult.Failure(
                IllegalStateException("活动数据库为空"),
                code = KdbxError.SAVE_NO_DATABASE
            )
            // P1-10：仅密钥文件会话（主密码为 null/空）下 passwordCache 可为空，
            // 只要密钥文件缓存仍在即可完成保存；两者皆缺失才视为凭据丢失
            val pwd = credentials.currentPassword()
            if (pwd == null && credentials.currentKeyFile() == null) {
                return@withContext KdbxResult.Failure(
                    IllegalStateException("主密码已被清理"),
                    code = KdbxError.SAVE_CREDENTIALS_LOST
                )
            }

            // ISSUE-P3-368：进入保存链先清陈旧进度（null = 不确定段），修剪/准备段一并覆盖
            progress(null)
            try {
                // ISSUE-P1-03 Retention 维护：按 Meta.maintenanceHistoryDays 自动修剪超期历史快照
                // （官方 KeePass DatabaseOperationsForm「删除 N 天前的历史条目」语义），
                // 使该 Meta 字段真实生效；仅在确有修剪时重建内存树，避免每次保存无谓拷贝。
                val prunedRoot = HistoryManager.pruneGroupHistoryByAge(db.rootGroup, db.maintenanceHistoryDays)
                val dbToSave = if (prunedRoot !== db.rootGroup) {
                    // ISSUE-P0-531：与 SessionContentMutations 同口径——**先发布修剪后的树、再擦下线历史**。
                    // 整改前「先擦 → 再发布」时，`db` 仍是发布中的活动树，而 UI 投影链正读它的历史
                    // 条目（`VaultEntryMapper` 的 `h.userName`，即真机崩溃堆栈命中点）⇒ 读到已清零实例 ⇒ 闪退。
                    val pruned = db.copy(rootGroup = prunedRoot)
                    core.database.value = pruned
                    // ISSUE-P2-06：修剪下线了超期历史快照，**发布后**定点擦除其密文（身份集合判定不变）
                    db.rootGroup.clearSupersededSensitiveData(prunedRoot)
                    pruned
                } else {
                    db
                }

                // TASK-42 整改（P2-2）：Argon2 派生与流加密为 CPU 密集，序列化走 Default；
                // 仅字节落盘（writeAtomic + fsync）走 IO——对齐 exportToBytes 的既有调度先例
                val serialized = serializeToBytes(dbToSave, pwd, credentials.currentKeyFile(), progress)
                try {
                    writer(serialized)
                    // ISSUE-P1-631：写盘确认成功 ⇒ 登记本版本为持久化基线（漂移合并的真实共同基线；
                    // 克隆归会话，序列化缓冲由下方 finally 照常清零）。失败 / 取消路径不前移基线。
                    core.retainPersistedBaselineBestEffort(serialized, core.activePathIdentifier)
                    // ISSUE-P3-368：落盘完成即终态 1.0（序列化侧进度已在 KdbxFile.save 内发到 0.9）
                    progress(KdbxProgress.DONE)
                    core.state.value = DatabaseSession.SessionState.OPENED
                    KdbxResult.Success(Unit)
                } finally {
                    // ISSUE-P3-551：序列化缓冲即整库密文（头部外全加密），
                    // **写盘异常路径同样必须清零**——整改前 `fill(0)` 在 `try` 体内，
                    // `writer` 抛异常时整库密文副本滞留堆上等 GC（同文件换密路径同型）。
                    serialized.fill(0)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // ISSUE-P3-368：失败清进度（不留半程残值）
                progress(null)
                KdbxResult.Failure(t, code = KdbxError.SAVE_FAILED)
            }
        }
    }

    /**
     * `ISSUE-P1-631`（生命周期收口）：**可取消预备段**——修剪候选根并把候选树序列化为密文
     * （含保存侧 KDF 派生 / Argon2 重算，真机秒级 CPU），**不取会话互斥锁、不发布任何树、
     * 不触碰基线与状态**；取消 / 失败 ⇒ 密文缓冲清零后如实失败，会话无痕。
     *
     * 与 [saveLocked] 的职责切分：本段只做「计算」，把「Argon2 + 序列化」从原
     * `adoptAndSaveIfUnchanged` 的不可取消临界区内移出——数秒级 CPU 不得因防取消而
     * 全部包进 `NonCancellable`（生命周期整改裁决）；原子性由 [commitPreparedSave] 的
     * 「同一锁内复检 → 校验实例身份 → 发布 → 写盘 → 基线」保证：预备与提交之间会话若发生
     * 任何确认变更（树实例替换 / 基线修订前移 / 只读化 / 凭据丢失），提交一律 fail-closed，
     * 预备产物整份作废清零——序列化的是**调用方持有的候选树**而非活动树，活动树在预备段内只读，
     * 候选树在预备与提交之间不可达于任何其它写路径（由调用方所有权保证）。
     *
     * 前置校验与 [saveLocked] 同清单（凭据写入不确定 / 只读 / 无写回通道 / 凭据丢失）；
     * 提交段会**复检**同一清单（防预备与提交之间凭据被自动锁定清掉）。
     */
    suspend fun prepareSerializedSave(
        candidate: KdbxDatabase
    ): KdbxResult<PreparedSerializedSave> {
        if (core.credentialWriteUncertain.value) {
            return KdbxResult.Failure(
                IllegalStateException("凭据写入结果不确定，须先核实或恢复文件"),
                code = KdbxError.SAVE_WRITE_UNCERTAIN
            )
        }
        if (core.readOnlyMode) {
            return KdbxResult.Failure(
                IllegalStateException("数据库以只读模式打开"),
                code = KdbxError.SAVE_READ_ONLY
            )
        }
        if (core.saveWriter == null) {
            return KdbxResult.Failure(
                IllegalStateException("无活动数据库保存通道"),
                code = KdbxError.SAVE_NO_WRITER
            )
        }
        val pwd = credentials.currentPassword()
        if (pwd == null && credentials.currentKeyFile() == null) {
            return KdbxResult.Failure(
                IllegalStateException("主密码已被清理"),
                code = KdbxError.SAVE_CREDENTIALS_LOST
            )
        }
        // 修剪计算是候选树上的纯函数（不发布、不擦除——擦除仍按 P0-531 在提交段「先发布后擦」）
        val prunedRoot = HistoryManager.pruneGroupHistoryByAge(
            candidate.rootGroup,
            candidate.maintenanceHistoryDays
        )
        val bytes = serializeToBytes(
            candidate.copy(rootGroup = prunedRoot),
            pwd,
            credentials.currentKeyFile(),
            null
        )
        return KdbxResult.Success(PreparedSerializedSave(bytes, prunedRoot))
    }

    /**
     * `ISSUE-P1-631`（生命周期收口）：**最小提交临界区**——调用方（漂移处置）已把本调用包在
     * 其 `NonCancellable` 段内，本段不再叠加，避免双层语义。在同一会话互斥锁内完成
     * 「复检凭据 / 只读 / 写回通道 → 校验实例身份与持久化基线修订 → 采用候选（直接发布
     * 修剪后树）→ 写预备密文 → 前移持久化基线 → 终态」。
     *
     * 为什么校验与写盘必须同锁（自原 `adoptAndSaveIfUnchanged` 的 KDoc 移入）：两者各自取锁
     * 会释放会话锁——窗口内的换库 / 关闭会让写盘把**后来会话**的内容写到**后来会话**的目标上，
     * 而调用方据返回值判定「本次合并已保存」，合并产物却被静默丢弃、漂移提示也被清掉。
     *
     * 与原 `adoptAndSaveIfUnchanged` 的**唯一**差异：序列化（Argon2 / 密文生成）已在
     * [prepareSerializedSave] 可取消段完成，本段写的是**预备密文**——临界区内只剩
     * 校验 / 发布 / 写 IO / 基线登记（真机实测毫秒级）。语义等价性论证：
     * - 原「采用候选 → saveLocked 内修剪 → 发布修剪树 → 修剪下线擦除」在此并为**单步采用
     *   修剪候选**（`adoptDatabaseIfUnchangedLocked(expected, prunedCandidate)`）——修剪只
     *   影响条目 history 快照，且被修剪掉的快照实例必然同时可达于旧活动树（本地侧历史与
     *   活动树共享实例；磁盘侧独有实例由合并产物的 `eraseDiscardedDatabase` 回收），
     *   故单步「旧树 → 修剪候选」的身份擦除是原两步擦除（旧树→候选、候选→修剪）的并集超集，
     *   不会漏擦；图标池 / 二进制池修剪不触及，判据与原实现一致。
     * - 预备密文与提交段发布的树来自**同一候选**（预备段序列化的就是
     *   `candidate.copy(rootGroup = prunedRoot)`），磁盘内容与会话树不出现口径分叉。
     *
     * 预备与提交之间会话发生任何确认变更（树实例替换 / 基线修订前移 / 只读化 / 凭据丢失）
     * ⇒ fail-closed（`SAVE_SESSION_CHANGED` 等类型化失败），预备产物由外层 finally 清零。
     */
    suspend fun commitPreparedSave(
        expectedAtCycleStart: KdbxDatabase,
        candidate: KdbxDatabase,
        prepared: PreparedSerializedSave,
        expectedPersistedBaselineRevision: Long? = null
    ): KdbxResult<Unit> = mutex.withLock {
        try {
            if (core.credentialWriteUncertain.value) {
                return@withLock KdbxResult.Failure(
                    IllegalStateException("凭据写入结果不确定，须先核实或恢复文件"),
                    code = KdbxError.SAVE_WRITE_UNCERTAIN
                )
            }
            if (core.readOnlyMode) {
                return@withLock KdbxResult.Failure(
                    IllegalStateException("数据库以只读模式打开"),
                    code = KdbxError.SAVE_READ_ONLY
                )
            }
            val pwd = credentials.currentPassword()
            if (pwd == null && credentials.currentKeyFile() == null) {
                return@withLock KdbxResult.Failure(
                    IllegalStateException("主密码已被清理"),
                    code = KdbxError.SAVE_CREDENTIALS_LOST
                )
            }
            val writer = core.saveWriter
            if (writer == null) {
                return@withLock KdbxResult.Failure(
                    IllegalStateException("无活动数据库保存通道"),
                    code = KdbxError.SAVE_NO_WRITER
                )
            }
            val mutated = requireNotNull(mutations) {
                "commitPreparedSave 需要 SessionContentMutations（构造器注入），不得在无 mutations 的实例上调用"
            }
            // 修剪在预备段已计算；采用即发布「修剪后的候选」，擦除判据见上 KDoc
            val prunedCandidate = candidate.copy(rootGroup = prepared.prunedRoot)
            if (!mutated.adoptDatabaseIfUnchangedLocked(
                    expectedAtCycleStart,
                    prunedCandidate,
                    expectedPersistedBaselineRevision
                )
            ) {
                return@withLock KdbxResult.Failure(
                    IllegalStateException("合并窗口内会话树或持久化基线已变化"),
                    code = KdbxError.SAVE_SESSION_CHANGED
                )
            }
            progress(null)
            writer(prepared.bytes)
            // ISSUE-P1-631：写盘确认成功 ⇒ 登记本版本为持久化基线（与 saveLocked 同口径；
            // 失败 / 取消路径不前移基线）
            core.retainPersistedBaselineBestEffort(prepared.bytes, core.activePathIdentifier)
            progress(KdbxProgress.DONE)
            core.state.value = DatabaseSession.SessionState.OPENED
            KdbxResult.Success(Unit)
        } finally {
            // P3-551 同型：预备密文即整库密文，成功 / 失败 / 取消任何路径都必须清零
            prepared.wipe()
        }
    }

    /**
     * `ISSUE-P2-602`：库级 Meta 的「序列化候选 → 落盘 → 发布」事务。
     *
     * 普通 UI Meta 更新此前先写入 `databaseFlow` 再调用 [save]；writer 返回 Failure 时，磁盘仍旧而
     * StateFlow 已发布新值。此入口在共享会话 Mutex 内先构造候选，真实 writer 成功后才由
     * [publishDatabaseMeta] 发布；提交前的 Failure / Cancellation 保持原树、状态和 UI 回显不变。
     * writer 已确认提交后若取消，则先同步发布，再传播取消，避免磁盘与内存分叉。
     */
    suspend fun commitDatabaseMeta(
        expectedDatabase: KdbxDatabase,
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxResult<Unit> = metadataCommitter.commit(expectedDatabase, transform)

    /**
     * TASK-13 整改：将当前内存数据库序列化为 KDBX 字节流（SAF 导出用）。
     * 与 [save] 相同的凭据要求与序列化管线（含密钥文件复合密钥），但不落盘到活动文件，
     * 字节交由调用方处置；锁定/关闭状态（内存树已销毁）下如实失败。
     */
    suspend fun exportToBytes(): KdbxResult<ByteArray> = mutex.withLock {
        val db = core.database.value ?: return@withLock KdbxResult.Failure(
            IllegalStateException("活动数据库为空"),
            code = KdbxError.EXPORT_NO_DATABASE
        )
        val pwd = credentials.currentPassword()
        if (pwd == null && credentials.currentKeyFile() == null) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("主密码已被清理"),
                code = KdbxError.EXPORT_CREDENTIALS_LOST
            )
        }
        withContext(Dispatchers.Default) {
            try {
                KdbxResult.Success(serializeToBytes(db, pwd, credentials.currentKeyFile()))
            } catch (t: Throwable) { // cancel-n/a: 保护段为非挂起序列化（serializeToBytes）
                KdbxResult.Failure(t, code = KdbxError.EXPORT_FAILED)
            }
        }
    }

    /**
     * P0-3 更改主凭据：更新内存中的主密码/密钥文件缓存，并立即触发全量重加密写盘。
     * KDBX4 规范在每次保存时均生成全新的随机 MasterSeed 与 Salt，因此更换凭据等价于以新凭据重新序列化保存。
     *
     * **密钥文件保持不变**（沿用当前会话的密钥文件快照）。
     *
     * ISSUE-P3-99（审计 L2）：此前快照以**默认参数表达式**（`= credentials.keyFileSnapshot()`）
     * 形态注入调用栈——该克隆副本归本方法所有却**无处可擦**，换密后随局部变量出栈静默留存至 GC。
     * 现改为显式重载：快照由本方法自持，并在返回前 `finally` 清零。
     * **顺序硬约束**：清零只发生在**写盘与可能回滚之后**——写前擦会静默写出「用全零密钥文件加密」的库。
     */
    suspend fun changeCredentials(newPasswordChars: CharArray?): KdbxResult<Unit> = withRotationTicket {
        val keyFileSnapshot = credentials.keyFileSnapshot()
        try {
            rotation.execute(newPasswordChars, keyFileSnapshot)
        } finally {
            keyFileSnapshot?.fill(0)
        }
    }

    /**
     * 更换主凭据（显式指定新密钥文件）。
     *
     * @param newPasswordChars 新主密码（null = 仅密钥文件会话）
     * @param newKeyFileData 新密钥文件字节（null = 不使用密钥文件）；
     *   **该数组归调用方所有**——本方法只读取它（[SessionCredentialCache.rotateCredentials] 内部克隆写入缓存、
     *   `KdbxFile.save` 读取用于派生），不持有引用、**不擦除**；调用方可在返回后安全复用或自行清零。
     */
    suspend fun changeCredentials(
        newPasswordChars: CharArray?,
        newKeyFileData: ByteArray?
    ): KdbxResult<Unit> = withRotationTicket {
        rotation.execute(newPasswordChars, newKeyFileData)
    }

    /**
     * 仅更换密钥文件因子，**主密码分量原样保留**（ISSUE-P3-430）。
     *
     * 语义＝以 [SessionCredentialCache.passwordSnapshot] 克隆出的当前主密码 + [newKeyFileData]
     * 走双参 [changeCredentials]（密码分量不变，密钥文件改绑 / 解绑）。会话无主密码分量
     * （仅密钥文件）且 [newKeyFileData] 为 `null` 时，改绑后将**不剩任何因子**，fail-closed 拒绝。
     *
     * @param newKeyFileData 新密钥文件字节（null = 解绑）；借用语义同双参 [changeCredentials]。
     */
    suspend fun changeKeyFileOnly(newKeyFileData: ByteArray?): KdbxResult<Unit> = withRotationTicket {
        val pwdSnapshot = credentials.passwordSnapshot()
        try {
            rotation.execute(pwdSnapshot, newKeyFileData)
        } finally {
            pwdSnapshot?.fill('0')
        }
    }

    /** 在第一处挂起之前捕获身份；等待 mutex 期间换库或换密均拒绝，绝不改写后来会话。 */
    private suspend fun withRotationTicket(block: suspend () -> KdbxResult<Unit>): KdbxResult<Unit> {
        val request = currentCoroutineContext()[SessionCredentialRequest]
        val nestedRequest = currentCoroutineContext()[RotationRequestContext]?.request
        if (request != null && nestedRequest === request) {
            return if (core.credentialTicket === request.expected) block() else KdbxResult.Failure(
                IllegalStateException("受理后的会话或凭据已改变"),
                code = KdbxError.CREDENTIALS_CHANGE_FAILED
            )
        }
        val expected = request?.expected ?: core.credentialTicket
        return mutex.withLock {
            if ((request != null && (request.expected == null || !request.consumeOnce())) || core.credentialTicket !== expected) {
                KdbxResult.Failure(IllegalStateException("受理后的会话或凭据已改变"),
                    code = KdbxError.CREDENTIALS_CHANGE_FAILED)
            } else if (request == null) block() else withContext(RotationRequestContext(request)) { block() }
        }
    }

    private class RotationRequestContext(val request: SessionCredentialRequest) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RotationRequestContext>
    }

    /**
     * 从滚动备份（.bak）恢复指定文件（`ISSUE-P2-521`）。
     *
     * 解锁失败（文件损坏分型）时由 app 层在用户显式确认后调用；恢复不改凭据，成功仍须重新解锁。
     * 文件读写挂 IO 调度（恢复的是整库字节，不得占用调用方线程）；
     * 语义与失败口径见 `SessionFileWriter.restoreFromRollingBackup`。
     */
    suspend fun restoreFromRollingBackup(targetFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        fileWriter.restoreFromRollingBackup(targetFile)
    }


    /**
     * 整库序列化（三条写盘路径的**唯一**实现）。
     *
     * ISSUE-P3-118：序列化缓冲必须**具名**并在用毕后清零——`toByteArray()` 只返回副本，
     * 内部缓冲是第二份整库密文，等待 GC 不构成擦除（`reset()` 也不清内容）。
     * 返回数组由调用方负责清零。
     *
     * ISSUE-P3-368：[onProgress] 透传至 `KdbxFile.save`（序列化侧进度）；
     * 仅 [save] 主链路接线，导出 / 换密路径维持无进度的既有行为。
     */
    private suspend fun serializeToBytes(
        db: KdbxDatabase,
        pwd: CharArray?,
        keyFile: ByteArray?,
        onProgress: ((Float?) -> Unit)? = null
    ): ByteArray {
        val buffer = WipableByteArrayOutputStream()
        var pending: ByteArray? = null
        return try {
            withContext(Dispatchers.Default) {
                KdbxFile.save(buffer, db, pwd, keyFile, onProgress)
                pending = buffer.toByteArray()
            }
            pending!!.also { pending = null }
        } finally {
            pending?.fill(0)
            buffer.wipe()
        }
    }
}
