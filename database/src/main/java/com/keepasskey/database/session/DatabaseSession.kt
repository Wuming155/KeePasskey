package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.file.KdbxDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 活动数据库会话状态机与生命周期管理者。
 * 维护内存中活动密码库的生命周期：CLOSED ↔ LOCKED ↔ OPENED ↔ DIRTY
 * 实现下沉同包协作类；会话建立见 [SessionOpener]，持久化见 [SessionPersistence]。
 */
class DatabaseSession(
    /**
     * ISSUE-P2-24：大附件落盘存储（可选）。非空时，超过阈值的附件在解析期即落盘，
     * 内层二进制池只保留引用，不再整批常驻内存；为空时行为与既往逐字一致。
     * 由 app 侧 DI 注入（`cacheDir/attachments`），并同时注册为 [SessionLockObserver]。
     */
    private val binaryStore: BinaryStore? = null
) {
    enum class SessionState {
        CLOSED,
        LOCKED,
        OPENED,
        DIRTY
    }

    private val core = SessionCore()
    val state: StateFlow<SessionState> = core.state.asStateFlow()
    val databaseFlow: StateFlow<KdbxDatabase?> = core.database.asStateFlow()
    /** 流式/降级写入结果不确定：当前会话禁止继续保存，须重新打开核实或恢复文件。 */
    val credentialWriteUncertain: StateFlow<Boolean> = core.credentialWriteUncertain.asStateFlow()

    private val mutex = Mutex()
    private val credentials = SessionCredentialCache()

    /**
     * ISSUE-P2-11 (ZT-16)：会话级「保存前创建 .bak 滚动备份」偏好。
     *
     * 默认 true 保持既有行为；关闭时全部落盘路径均不生成 `.bak`，并顺带清理历史遗留的
     * `.bak`。由 app 侧 DI 装配期从持久化偏好注入初值，用户切换开关时实时同步。
     * 以 [Volatile] 保证跨线程可见性（写盘可能发生在 IO 调度线程）。
     *
     * **语义与保留期（ISSUE-P3-107 明确化）**：开启时，每次**成功写入**都会把写入前的稳定版本
     * 另存为**同目录**的 `<库文件名>.kdbx.bak`——即滚动保留**恰好一份**（上一次成功写入的版本），
     * 每次写入覆盖之，不做多代累积、也**不迁移到其它目录**。该 `.bak` 是用**写入当时生效的凭据**
     * 加密的**完整库副本**，故：
     * - 成功 [changeCredentials]（换主密码 / 换密钥文件）后**一律删除**——否则旧口令仍可解开它
     *   （见 [changeCredentials] 内注释与 `DatabaseSessionBackupPreferenceTest`）；
     * - 用户若在**本应用之外**（如其它 KDBX 客户端）更换口令，本应用无从知晓，该 `.bak` 会在
     *   下次在本应用内写入前一直可用**旧口令**解开（如实登记为残余，
     *   见 `docs/architecture/已知工程限界.md` §1.4）。
     */
    @Volatile
    var createBackupBeforeSave: Boolean = true

    private val fileWriter = SessionFileWriter { createBackupBeforeSave }

    /**
     * ISSUE-P3-368 AC①：打开 / 保存链路进度（Flow 形态）。
     * 0..1 为确定进度；null = 无进行中操作或分段不确定段（KDF 派生等不可细分段）。
     * 事件只承载数值（Float?），即发即弃——不持有流 / 字节 / 密钥引用（AC③）。
     */
    private val _ioProgress = MutableStateFlow<Float?>(null)

    val ioProgress: StateFlow<Float?> = _ioProgress.asStateFlow()

    /** ISSUE-P3-368：进度写入唯一入口（打开 / 保存两链共用） */
    private fun publishProgress(value: Float?) {
        _ioProgress.value = value
    }

    private val mutations = SessionContentMutations(
        mutex, core.database, core.state, { core.readOnlyMode },
        persistedBaselineRevision = { core.persistedBaselineRevision() },
        credentialTicket = { core.credentialTicket }
    )
    private val persistence = SessionPersistence(mutex, core, credentials, fileWriter, ::publishProgress, mutations::publishCommittedDatabaseMeta, mutations)
    /** Atomic target snapshot and Meta commit entry points. */
    val writeIntents = DatabaseSessionWriteIntents(mutex, core, persistence)

    private val opener = SessionOpener(core, credentials, fileWriter, mutex, binaryStore, ::publishProgress) {
        // ISSUE-P2-77：换库前置释放（由本次调用持有互斥锁，故不能走 lock()——会重入死锁）
        releaseSessionStateForReplacement()
    }

    // ISSUE-P1-07：会话终止观察者集合——锁定/关闭时同步通知各派生敏态数据的持有方清理
    private val lockRegistry = SessionLockRegistry()

    val currentFile: File?
        get() = core.activeFile

    val currentPathIdentifier: String?
        get() = core.activePathIdentifier

    /** 当前会话是否为只读模式（锁定/关闭后重置为 false） */
    val isReadOnly: Boolean
        get() = core.readOnlyMode

    /**
     * 注册会话终止观察者（ISSUE-P1-07）。
     *
     * 典型用途：同步缓存在 `cacheDir` 落盘了完整 KDBX 密文快照，必须在锁库/关闭时
     * 一并销毁，否则「锁定」之后密文仍可被离线无限期爆破。观察者由 app 侧在 DI 装配期注册。
     *
     * @return 观察者此前未注册时返回 true（重复注册为幂等无操作，返回 false）
     */
    fun addLockObserver(observer: SessionLockObserver): Boolean = lockRegistry.add(observer)

    /** 注销会话终止观察者；未注册时返回 false */
    fun removeLockObserver(observer: SessionLockObserver): Boolean = lockRegistry.remove(observer)

    /** 受理时同步固定当前会话及凭据代次；请求不持有密码或数据库树。 */
    fun captureCredentialRequest(
        ticket: SessionCredentialTicket? = core.credentialTicket
    ): SessionCredentialRequest = SessionCredentialRequest(ticket)

    /** 传播票据而不获取 mutex；实际改树和写盘各自在原有临界区内复核。 */
    suspend fun <T> withCredentialRequest(request: SessionCredentialRequest, block: suspend () -> T): T {
        val enclosing = currentCoroutineContext()[SessionCredentialRequest]
        check(enclosing == null || enclosing === request) { "禁止嵌套覆盖已受理凭据请求" }
        return withContext(request) { block() }
    }

    /**
     * 本地收尾的窄临界区；调用者须限时。闭包只可同步维护本地封印存储，禁止重入数据库、
     * 获取同步 mutex、弹提示、启动网络或等待生物识别。
     */
    suspend fun useCredentialTicket(ticket: SessionCredentialTicket, block: suspend () -> Unit): Boolean = mutex.withLock {
        if (core.credentialTicket !== ticket) return@withLock false
        block()
        true
    }

    /** 无挂起点的前置探测只用于跳过过期工作，最终发布仍须 [useCredentialTicket]。 */
    fun isCredentialTicketCurrent(ticket: SessionCredentialTicket): Boolean = core.credentialTicket === ticket

    /**
     * ISSUE-P2-77：换库前置释放——语义与 [lock] 的清理部分**对齐**
     * （擦除旧库明文树与二进制池、清空凭据缓存、置 `LOCKED`、通知锁观察者驱逐派生数据），
     * 但不取互斥锁（由 [SessionOpener] 在其临界区内调用，避免重入死锁）。
     *
     * 触发点：`SessionOpener.openStream` 在解析之前调用，新建空库在排他提交成功之后调用，
     * 使「切换 / 新建密码库」与「锁库」在数据生命周期上取得一致——旧库的
     * `ProtectedString` 密文、`cacheDir/sync` 快照与明文附件缓存不再滞留至 GC。
     *
     * **顺序硬约束**：打开必须在 `KdbxFile.load` 之前执行；新建空库不解析附件，可提交后释放，否则
     * `FileBinaryStore.onSessionLocked()` 会删除刚为新库落盘的附件（静默数据损坏）。
     */
    private fun releaseSessionStateForReplacement() {
        core.invalidateSessionTicket()
        core.readOnlyMode = false
        credentials.clear()
        // ISSUE-P1-631：旧库基线随会话更换作废（先清零密文快照再置空）
        core.clearPersistedBaseline()
        core.database.value?.clearSensitiveData()
        core.database.value = null
        core.state.value = SessionState.LOCKED
        lockRegistry.notifySessionLocked()
    }

    /**
     * 在锁保护下获取当前缓存凭据的克隆副本并执行 [block]。
     *
     * 注意事项（敏感数据铁律）：
     * 传入 [block] 的 [CharArray] 与 [ByteArray] 为克隆出的独立副本，
     * **本方法返回前（含 [block] 抛异常路径）由 [SessionCredentialCache] 显式清零**
     * （ISSUE-P2-312）——调用方无需也不得依赖「借出副本在本方法返回后仍可读」。
     */
    fun <T> useCredentials(block: (CharArray?, ByteArray?) -> T): T = credentials.useCredentials(block)

    /**
     * 用会话附件存储解析外部密文；不取会话 mutex，凭据副本由 useCredentials 擦除。
     * 返回树归调用者；解析事务将 spill key 移交给池条目所有者。调用者须把树采用为活动库，
     * 或在丢弃前调用 `clearSensitiveData()`（按存活池判定释放 spill），避免附件明文滞留。
     */
    suspend fun parseExternalDatabase(bytes: ByteArray): KdbxResult<KdbxDatabase> =
        parseExternalKdbxBytes(bytes, ::useCredentials, binaryStore)

    /**
     * 排他创建不存在的密码库文件并打开会话；碰撞或写入失败保留原活动会话及备份。
     * 详见 [SessionOpener.create]。
     * [cipherUuid] 为外层加密算法，带用户预设时必须显式传入。
     */
    suspend fun create(
        file: File,
        name: String,
        passwordChars: CharArray,
        useArgon2: Boolean = true,
        keyFileData: ByteArray? = null,
        cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
    ): KdbxResult<Unit> = opener.create(file, name, passwordChars, useArgon2, keyFileData, cipherUuid)

    /** SAF 新建：writeNew 仅写本次文档且返回前验证读回；失败保留旧会话。 */
    suspend fun createStream(
        pathIdentifier: String, name: String, passwordChars: CharArray,
        useArgon2: Boolean, keyFileData: ByteArray?, cipherUuid: KdbxUuid,
        writeNew: suspend (ByteArray) -> Unit, saveWriter: suspend (ByteArray) -> Unit,
        trackedSaveWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null
    ): KdbxResult<Unit> = opener.createStream(
        pathIdentifier, name, passwordChars, useArgon2, keyFileData, cipherUuid, writeNew, saveWriter,
        trackedSaveWriter
    )

    /** 打开并解密已有 KDBX 文件（支持直接传入 File）。 */
    suspend fun open(
        file: File,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false
    ): KdbxResult<Unit> = opener.open(file, passwordChars, keyFileData, readOnly)

    /**
     * 打开并解密 KDBX 流（支持系统 SAF Uri、网络缓存及普通 File 等多元数据源）。
     * [inputStreamProvider] 每次按需提供新鲜可读输入流；
     * [saveWriter] 保存时的二进制写出通道（如 ContentResolver.openOutputStream 或原子写盘）。
     */
    suspend fun openStream(
        pathIdentifier: String,
        inputStreamProvider: suspend () -> java.io.InputStream,
        saveWriter: (suspend (ByteArray) -> Unit)? = null,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false,
        associatedFile: File? = null,
        trackedSaveWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null
    ): KdbxResult<Unit> = opener.openStream(
        pathIdentifier = pathIdentifier,
        inputStreamProvider = inputStreamProvider,
        saveWriter = saveWriter,
        passwordChars = passwordChars,
        keyFileData = keyFileData,
        readOnly = readOnly,
        associatedFile = associatedFile,
        trackedSaveWriter = trackedSaveWriter
    )

    /**
     * 保存当前内存中的活动数据库并写入文件/URI 通道
     *
     * ISSUE-P3-305：实现下沉 [SessionPersistence.save]（逐行搬运），本方法仅为门面转发。
     */
    suspend fun save(): KdbxResult<Unit> = persistence.save()

    /** 更新或保存条目 */
    suspend fun saveEntry(entry: KdbxEntry) = mutations.saveEntry(entry)

    /**
     * ISSUE-P3-157：单条条目**原子读-改-写**——在会话 Mutex 内的单次受控变换中完成
     * 「按 id 定位 → [transform] 变换 → 落树 → 增量定点擦除」。
     *
     * 与 [saveEntry] 的分工：`saveEntry` 按 `parentGroupId` 落树（新增 / 替换皆可），
     * 本入口**只更新已存在的条目**（按 id 定位，绝不新增），供「读库内现值 → 计算 → 写回」
     * 这类必须原子完成的调用方使用（典型：passkey 签名计数器的并发递增）。
     * 树变换由 [SessionTreeEditor] 完成，擦除候选只取自被替换的那一条旧条目。
     *
     * @return 落树上线的条目实例（条目不存在 / 只读态 / 无活动库时为 null，且不写入、不置 DIRTY）
     */
    suspend fun updateEntryById(entryId: KdbxUuid, transform: (KdbxEntry) -> KdbxEntry): KdbxEntry? =
        mutations.updateEntryById(entryId, transform)

    /** 删除条目 */
    suspend fun deleteEntry(entryId: KdbxUuid) = mutations.deleteEntry(entryId)

    /**
     * `ISSUE-P2-635`：条目**换址保存**——在会话 Mutex 内的单次变换中完成
     * 「从原位置摘除 → 落到 [targetParentId]」，取代调用方自行 `deleteEntry + saveEntry`
     * 的两步中间态（中间态下条目对并发读不可见）。
     *
     * @return 目标分组存在且写入已发布时为 true；只读态 / 无活动库 / 目标分组不存在为 false，
     *   此时**不做任何写入**。详见 [SessionContentMutations.saveEntryInGroup]。
     */
    suspend fun saveEntryInGroup(entry: KdbxEntry, targetParentId: KdbxUuid): Boolean =
        mutations.saveEntryInGroup(entry, targetParentId)

    /**
     * 保存或更新分组。
     *
     * P0-1 防御性保护：更新既有分组（含根分组）时，若传入分组不携带任何子项而既有分组含有子项，
     * 经 [SessionTreeEditor.preserveChildrenIfMissing] 保留既有子项，防止「重命名/改图标」等仅更新元数据的
     * 调用路径意外清空子条目与子分组。
     */
    suspend fun saveGroup(group: KdbxGroup) = mutations.saveGroup(group)

    /**
     * 允许受控原子修改数据库顶层元数据与墓碑列表（例如 recycleBinUuid、deletedObjects 追加）。
     * 修改后置为 SessionState.DIRTY 状态，供后续统一 save() 序列化落盘。
     */
    suspend fun updateDatabaseMeta(transform: (KdbxDatabase) -> KdbxDatabase) = mutations.updateDatabaseMeta(transform)

    /**
     * ISSUE-P2-278：「校验-采用」原子落库（同步周期接管 / 合并专用）——仅当会话树仍是
     * [expectedAtCycleStart]（周期起点的 `databaseFlow.value` 实例）时才采用 [replacement]；
     * 会话在窗口内已被写路径替换（或只读 / 无活动库）时返回 false 且不做任何改动。
     * 详见 [SessionContentMutations.adoptDatabaseIfUnchanged]。
     *
     * `ISSUE-P1-631`：[expectedPersistedBaselineRevision] 非 null 时在同一临界区内追加
     * 「持久化基线修订仍为登记值」的条件——漂移合并以长窗口（解析 / 合并）计算产物，
     * 窗口内任何确认写盘 / 会话变化都必须让本次采用 fail-closed 中止。
     */
    suspend fun adoptDatabaseIfUnchanged(
        expectedAtCycleStart: KdbxDatabase,
        replacement: KdbxDatabase,
        expectedPersistedBaselineRevision: Long? = null
    ): Boolean = mutations.adoptDatabaseIfUnchanged(
        expectedAtCycleStart,
        replacement,
        expectedPersistedBaselineRevision
    )

    /**
     * `ISSUE-P1-631`（生命周期收口）：**可取消预备段**——修剪候选并把候选树序列化为密文
     * （含保存侧 KDF / Argon2 重算）。不取会话锁、不发布、不触碰基线与状态；详见
     * [SessionPersistence.prepareSerializedSave] 的 KDoc（职责切分与 fail-closed 论证）。
     *
     * 返回的 [PreparedSerializedSave] 由调用方拥有：提交消耗或放弃后
     * 必须 [PreparedSerializedSave.wipe] 清零（整库密文不得滞留）。
     */
    suspend fun prepareSerializedSave(
        candidate: KdbxDatabase
    ): KdbxResult<PreparedSerializedSave> = persistence.prepareSerializedSave(candidate)

    /**
     * `ISSUE-P1-631`（生命周期收口）：**最小提交临界区**（调用方包在其 `NonCancellable` 内）——
     * 同一会话锁内「复检 → 校验实例身份与基线修订 → 采用 → 写预备密文 → 基线前移 → 终态」。
     * 取代原 `adoptAndSaveIfUnchanged`（序列化已移至 [prepareSerializedSave] 可取消段），
     * 语义等价性论证见 [SessionPersistence.commitPreparedSave] 的 KDoc。
     */
    suspend fun commitPreparedSave(
        expectedAtCycleStart: KdbxDatabase,
        candidate: KdbxDatabase,
        prepared: PreparedSerializedSave,
        expectedPersistedBaselineRevision: Long? = null
    ): KdbxResult<Unit> = persistence.commitPreparedSave(
        expectedAtCycleStart,
        candidate,
        prepared,
        expectedPersistedBaselineRevision
    )

    /** 删除分组 */
    suspend fun deleteGroup(groupId: KdbxUuid) = mutations.deleteGroup(groupId)

    /** 批量移动条目 */
    suspend fun batchMoveEntries(entryIds: Set<KdbxUuid>, targetGroupId: KdbxUuid?) =
        mutations.batchMoveEntries(entryIds, targetGroupId)

    /** 批量删除条目 */
    suspend fun batchDeleteEntries(entryIds: Set<KdbxUuid>) = mutations.batchDeleteEntries(entryIds)

    /**
     * 仅供测试使用：直接注入内存数据库模型（P3-9 整改：@VisibleForTesting 显式约束，
     * 生产代码调用视为契约违规；app 模块单测跨模块注入仍可访问）
     */
    @androidx.annotation.VisibleForTesting
    fun setDatabaseForTesting(db: KdbxDatabase) {
        core.establishSessionTicket(core.activePathIdentifier.orEmpty())
        core.database.value = db
        core.state.value = SessionState.OPENED
    }

    /**
     * `ISSUE-P1-631`：最后确认持久化版本快照（**克隆副本**；调用方拥有，用毕必须
     * [PersistedDatabaseBaseline.wipe] 清零）。
     *
     * 未持久化 / 超出留存上限 / 会话已终止时为 null——调用方（漂移合并）据此走
     * 「无可信基线 ⇒ 保守并集语义」的既有失败安全分支，不得借用当前可变树充作基线。
     * 取快照与修订在同一临界区完成，供 [adoptDatabaseIfUnchanged] 的修订校验配对使用。
     */
    suspend fun persistedBaselineSnapshot(): PersistedDatabaseBaseline? = mutex.withLock {
        val bytes = core.persistedBaselineClone() ?: return@withLock null
        PersistedDatabaseBaseline(
            revision = core.persistedBaselineRevision(),
            pathIdentifier = core.persistedBaselinePathIdentifier().orEmpty(),
            bytes = bytes
        )
    }

    /**
     * 仅供测试使用：清除持久化基线（模拟「无可信基线」场景，覆盖 `ISSUE-P1-631`
     * 缺失基线下的保守合并路径）。
     */
    @androidx.annotation.VisibleForTesting
    fun clearPersistedBaselineForTesting() {
        core.clearPersistedBaseline()
    }

    /**
     * 仅供测试使用：暴露基线内部缓冲的**引用本身**（非克隆），用于断言清除 / 替换 / 会话
     * 终止后旧密文缓冲是否已被就地清零（`ISSUE-P1-631` 的擦除证据）。生产代码不得调用。
     */
    @androidx.annotation.VisibleForTesting
    internal fun persistedBaselineInternalBytesForTesting(): ByteArray? =
        core.persistedBaselineInternalBytesForTesting()

    /**
     * TASK-13 整改：将当前内存数据库序列化为 KDBX 字节流（SAF 导出用）。
     * 与 [save] 相同的凭据要求与序列化管线（含密钥文件复合密钥），但不落盘到活动文件，
     * 字节交由调用方处置；锁定/关闭状态（内存树已销毁）下如实失败。
     *
     * ISSUE-P3-305：实现下沉 [SessionPersistence.exportToBytes]（逐行搬运）。
     */
    suspend fun exportToBytes(): KdbxResult<ByteArray> = persistence.exportToBytes()

    /**
     * TASK-13 整改：导出会话绑定的密钥文件原始字节（SAF 导出用）。
     * 缓存的是用户导入时的密钥文件原件字节（克隆语义）；
     * 会话未绑定密钥文件时返回 null，由调用方映射为可理解的错误提示。
     */
    fun exportKeyFileBytes(): ByteArray? = credentials.exportKeyFileBytes()

    /** 锁定当前数据库：保留文件路径引用，但物理销毁内存中的敏感主密码、数据库明文树与二进制池 */
    suspend fun lock() = mutex.withLock {
        core.invalidateSessionTicket()
        core.readOnlyMode = false
        credentials.clear()
        // ISSUE-P1-631：锁定即销毁持久化基线密文快照（与「同步缓存密文快照」同一生命周期口径：
        // 锁定后残留的整库密文可被离线无限期爆破，不得留给 GC）
        core.clearPersistedBaseline()
        core.database.value?.clearSensitiveData()
        core.database.value = null
        core.state.value = SessionState.LOCKED
        // ISSUE-P1-07：锁定即销毁——连同派生的敏态产物（同步缓存密文快照）一并终止生命周期
        lockRegistry.notifySessionLocked()
    }

    /** 完全关闭数据库会话并置空一切关联 */
    suspend fun close() = mutex.withLock {
        core.invalidateSessionTicket()
        core.readOnlyMode = false
        credentials.clear()
        // ISSUE-P1-631：关闭同锁定——持久化基线随会话终止销毁
        core.clearPersistedBaseline()
        core.database.value?.clearSensitiveData()
        core.database.value = null
        core.activeFile = null
        core.activePathIdentifier = null
        core.saveWriter = null
        core.trackedSaveWriter = null
        core.credentialWriteUncertain.value = false
        core.state.value = SessionState.CLOSED
        // ISSUE-P1-07：关闭隐含锁定，派生敏态产物同样必须清理
        lockRegistry.notifySessionLocked()
    }

    /** 凭据事务由 SessionPersistence 持锁执行，提交证据决定缓存采用及取消收尾。 */
    suspend fun changeCredentials(newPasswordChars: CharArray?): KdbxResult<Unit> =
        persistence.changeCredentials(newPasswordChars)

    /**
     * 更换主凭据（显式指定新密钥文件）。
     *
     * @param newPasswordChars 新主密码（null = 仅密钥文件会话）
     * @param newKeyFileData 新密钥文件字节（null = 不使用密钥文件）；
     *   **该数组归调用方所有**——本方法只读取它（`rotateCredentials` 内部克隆写入缓存、
     *   `KdbxFile.save` 读取用于派生），不持有引用、**不擦除**；调用方可在返回后安全复用或自行清零。
     */
    suspend fun changeCredentials(
        newPasswordChars: CharArray?,
        newKeyFileData: ByteArray?
    ): KdbxResult<Unit> = persistence.changeCredentials(newPasswordChars, newKeyFileData)

    /**
     * 当前会话主密码快照（ISSUE-P3-430）：克隆副本，**调用方用毕必须 `fill('0')` 清零**；
     * 会话无主密码分量（仅密钥文件会话）返回 `null`。绝不落地为 `String`、不写日志。
     */
    fun passwordSnapshot(): CharArray? = credentials.passwordSnapshot()

    /**
     * 仅更换密钥文件因子，主密码分量原样保留（ISSUE-P3-430）。
     * 语义与 fail-closed 边界见 [SessionPersistence.changeKeyFileOnly]；
     * [newKeyFileData] 借用语义同双参 [changeCredentials]。
     */
    suspend fun changeKeyFileOnly(newKeyFileData: ByteArray?): KdbxResult<Unit> =
        persistence.changeKeyFileOnly(newKeyFileData)

    /** 活动文件滚动备份（`.bak`）当前是否存在（ISSUE-P2-520 换密残余面读数；语义见 `SessionFileWriter.rollingBackupExists`）。 */
    fun rollingBackupExists(): Boolean = fileWriter.rollingBackupExists(core.activeFile)

    /** 指定文件的滚动备份是否存在（ISSUE-P2-521：解锁失败时会话 activeFile 未设置，须按目标文件探测）。 */
    fun rollingBackupExistsFor(targetFile: java.io.File): Boolean = fileWriter.rollingBackupExists(targetFile)

    /** 从滚动备份恢复指定文件（ISSUE-P2-521；语义与失败口径见 `SessionPersistence.restoreFromRollingBackup`）。 */
    suspend fun restoreFromRollingBackup(targetFile: java.io.File): Boolean = persistence.restoreFromRollingBackup(targetFile)
}
