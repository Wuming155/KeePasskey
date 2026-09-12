package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.history.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Arrays

/**
 * 活动数据库会话状态机与生命周期管理者。
 * 维护内存中活动密码库的生命周期：CLOSED ↔ LOCKED ↔ OPENED ↔ DIRTY
 *
 * ISSUE-P3-31 批次 D 结构拆分：本类收敛为会话门面，具体职责下沉到同包协作类——
 * 状态容器 [SessionCore]、凭据缓存 [SessionCredentialCache]、原子写盘 [SessionFileWriter]、
 * 树变换 [SessionTreeEditor]、内容变更 [SessionContentMutations]、会话建立 [SessionOpener]。
 * 公开 API 与行为逐字保持不变。
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

    private val mutex = Mutex()

    private val credentials = SessionCredentialCache()

    /**
     * ISSUE-P2-11 (ZT-16)：会话级「保存前创建 .bak 滚动备份」偏好。
     *
     * 默认 true 保持既有行为；关闭时全部落盘路径均不生成 `.bak`，并顺带清理历史遗留的
     * `.bak`。由 app 侧 DI 装配期从持久化偏好注入初值，用户切换开关时实时同步。
     * 以 [Volatile] 保证跨线程可见性（写盘可能发生在 IO 调度线程）。
     */
    @Volatile
    var createBackupBeforeSave: Boolean = true

    private val fileWriter = SessionFileWriter { createBackupBeforeSave }

    private val opener = SessionOpener(core, credentials, fileWriter, mutex, binaryStore)

    private val mutations = SessionContentMutations(mutex, core.database, core.state) { core.readOnlyMode }

    // ISSUE-P1-07：会话终止观察者集合——锁定/关闭时同步通知各派生敏态数据的持有方清理
    private val sessionLockObservers = LinkedHashSet<SessionLockObserver>()
    private val observerLock = Any()

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
    fun addLockObserver(observer: SessionLockObserver): Boolean = synchronized(observerLock) {
        sessionLockObservers.add(observer)
    }

    /** 注销会话终止观察者；未注册时返回 false */
    fun removeLockObserver(observer: SessionLockObserver): Boolean = synchronized(observerLock) {
        sessionLockObservers.remove(observer)
    }

    /**
     * 通知全部观察者会话已终止。
     *
     * 锁定/关闭是不可失败的原子动作：观察者异常一律隔离吞掉，绝不允许某个派生数据的
     * 清理失败反噬会话锁定本身（观察者须按 [SessionLockObserver] 契约自行记录失败）。
     */
    private fun notifySessionLockObservers() {
        val snapshot = synchronized(observerLock) { sessionLockObservers.toList() }
        for (observer in snapshot) {
            try {
                observer.onSessionLocked()
            } catch (_: Throwable) {
                // 隔离：清理失败不得阻断锁定流程
            }
        }
    }

    /**
     * 在锁保护下获取当前缓存凭据的克隆副本并执行 [block]。
     *
     * 注意事项（敏感数据铁律）：
     * 传入 [block] 的 [CharArray] 与 [ByteArray] 为克隆出的独立副本，
     * 调用方在使用完毕后必须显式清零返回数组（例如 `Arrays.fill(...)`），绝不可长期驻留堆内存。
     */
    fun <T> useCredentials(block: (CharArray?, ByteArray?) -> T): T = credentials.useCredentials(block)

    /**
     * 创建全新密码库文件并打开会话（ISSUE-P3-21 复合密钥三分支）。
     * 详见 [SessionOpener.create]。
     */
    suspend fun create(
        file: File,
        name: String,
        passwordChars: CharArray,
        useArgon2: Boolean = true,
        keyFileData: ByteArray? = null
    ): KdbxResult<Unit> = opener.create(file, name, passwordChars, useArgon2, keyFileData)

    /**
     * 打开并解密已有 KDBX 文件（支持直接传入 File）。
     */
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
        associatedFile: File? = null
    ): KdbxResult<Unit> = opener.openStream(
        pathIdentifier = pathIdentifier,
        inputStreamProvider = inputStreamProvider,
        saveWriter = saveWriter,
        passwordChars = passwordChars,
        keyFileData = keyFileData,
        readOnly = readOnly,
        associatedFile = associatedFile
    )

    /**
     * 保存当前内存中的活动数据库并写入文件/URI 通道
     */
    suspend fun save(): KdbxResult<Unit> = mutex.withLock {
        if (core.readOnlyMode) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("数据库以只读模式打开"),
                "数据库以只读模式打开，无法保存"
            )
        }
        withContext(Dispatchers.Default) {
            val writer = core.saveWriter ?: return@withContext KdbxResult.Failure(
                IllegalStateException("无活动数据库保存通道"),
                "未指定活动数据库保存通道"
            )
            val db = core.database.value ?: return@withContext KdbxResult.Failure(
                IllegalStateException("活动数据库为空"),
                "当前无活动数据库"
            )
            // P1-10：仅密钥文件会话（主密码为 null/空）下 passwordCache 可为空，
            // 只要密钥文件缓存仍在即可完成保存；两者皆缺失才视为凭据丢失
            val pwd = credentials.currentPassword()
            if (pwd == null && credentials.currentKeyFile() == null) {
                return@withContext KdbxResult.Failure(
                    IllegalStateException("主密码已被清理"),
                    "主密码凭据丢失，请重新输入主密码"
                )
            }

            try {
                // ISSUE-P1-03 Retention 维护：按 Meta.maintenanceHistoryDays 自动修剪超期历史快照
                // （官方 KeePass DatabaseOperationsForm「删除 N 天前的历史条目」语义），
                // 使该 Meta 字段真实生效；仅在确有修剪时重建内存树，避免每次保存无谓拷贝。
                val prunedRoot = HistoryManager.pruneGroupHistoryByAge(db.rootGroup, db.maintenanceHistoryDays)
                val dbToSave = if (prunedRoot !== db.rootGroup) {
                    // ISSUE-P2-06：修剪下线了超期历史快照，替换前定点擦除其密文
                    db.rootGroup.clearSupersededSensitiveData(prunedRoot)
                    db.copy(rootGroup = prunedRoot).also { core.database.value = it }
                } else {
                    db
                }

                // TASK-42 整改（P2-2）：Argon2 派生与流加密为 CPU 密集，序列化走 Default；
                // 仅字节落盘（writeAtomic + fsync）走 IO——对齐 exportToBytes 的既有调度先例
                val serialized = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { buffer ->
                        KdbxFile.save(buffer, dbToSave, pwd, credentials.currentKeyFile())
                    }.toByteArray()
                }
                writer(serialized)
                // 序列化缓冲即整库密文（头部外全加密），写毕即擦，避免缓冲滞留
                serialized.fill(0)
                core.state.value = SessionState.OPENED
                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "保存数据库失败: ${t.message}")
            }
        }
    }

    /**
     * 更新或保存条目
     */
    suspend fun saveEntry(entry: KdbxEntry) = mutations.saveEntry(entry)

    /**
     * 删除条目
     */
    suspend fun deleteEntry(entryId: KdbxUuid) = mutations.deleteEntry(entryId)

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
     * 删除分组
     */
    suspend fun deleteGroup(groupId: KdbxUuid) = mutations.deleteGroup(groupId)

    /**
     * 批量移动条目
     */
    suspend fun batchMoveEntries(entryIds: Set<KdbxUuid>, targetGroupId: KdbxUuid?) =
        mutations.batchMoveEntries(entryIds, targetGroupId)

    /**
     * 批量删除条目
     */
    suspend fun batchDeleteEntries(entryIds: Set<KdbxUuid>) = mutations.batchDeleteEntries(entryIds)

    /**
     * 仅供测试使用：直接注入内存数据库模型（P3-9 整改：@VisibleForTesting 显式约束，
     * 生产代码调用视为契约违规；app 模块单测跨模块注入仍可访问）
     */
    @androidx.annotation.VisibleForTesting
    fun setDatabaseForTesting(db: KdbxDatabase) {
        core.database.value = db
        core.state.value = SessionState.OPENED
    }

    /**
     * TASK-13 整改：将当前内存数据库序列化为 KDBX 字节流（SAF 导出用）。
     * 与 [save] 相同的凭据要求与序列化管线（含密钥文件复合密钥），但不落盘到活动文件，
     * 字节交由调用方处置；锁定/关闭状态（内存树已销毁）下如实失败。
     */
    suspend fun exportToBytes(): KdbxResult<ByteArray> = mutex.withLock {
        val db = core.database.value ?: return@withLock KdbxResult.Failure(
            IllegalStateException("活动数据库为空"),
            "当前无活动数据库（已锁定或未打开）"
        )
        val pwd = credentials.currentPassword()
        if (pwd == null && credentials.currentKeyFile() == null) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("主密码已被清理"),
                "主密码凭据丢失，无法导出"
            )
        }
        withContext(Dispatchers.Default) {
            try {
                val bytes = ByteArrayOutputStream().also { baos ->
                    KdbxFile.save(baos, db, pwd, credentials.currentKeyFile())
                }.toByteArray()
                KdbxResult.Success(bytes)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "导出数据库失败: ${t.message}")
            }
        }
    }

    /**
     * TASK-13 整改：导出会话绑定的密钥文件原始字节（SAF 导出用）。
     * 缓存的是用户导入时的密钥文件原件字节（克隆语义）；
     * 会话未绑定密钥文件时返回 null，由调用方映射为可理解的错误提示。
     */
    fun exportKeyFileBytes(): ByteArray? = credentials.exportKeyFileBytes()

    /**
     * 锁定当前数据库：保留文件路径引用，但物理销毁内存中的敏感主密码与数据库明文树
     */
    suspend fun lock() = mutex.withLock {
        core.readOnlyMode = false
        credentials.clear()
        core.database.value?.clearSensitiveData()
        core.database.value = null
        core.state.value = SessionState.LOCKED
        // ISSUE-P1-07：锁定即销毁——连同派生的敏态产物（同步缓存密文快照）一并终止生命周期
        notifySessionLockObservers()
    }

    /**
     * 完全关闭数据库会话并置空一切关联
     */
    suspend fun close() = mutex.withLock {
        core.readOnlyMode = false
        credentials.clear()
        core.database.value?.clearSensitiveData()
        core.database.value = null
        core.activeFile = null
        core.activePathIdentifier = null
        core.saveWriter = null
        core.state.value = SessionState.CLOSED
        // ISSUE-P1-07：关闭隐含锁定，派生敏态产物同样必须清理
        notifySessionLockObservers()
    }

    /**
     * P0-3 更改主凭据：更新内存中的主密码/密钥文件缓存，并立即触发全量重加密写盘。
     * KDBX4 规范在每次保存时均生成全新的随机 MasterSeed 与 Salt，因此更换凭据等价于以新凭据重新序列化保存。
     */
    suspend fun changeCredentials(
        newPasswordChars: CharArray?,
        newKeyFileData: ByteArray? = credentials.keyFileSnapshot()
    ): KdbxResult<Unit> = mutex.withLock {
        if (core.readOnlyMode) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("数据库处于只读模式，无法修改主凭据"),
                "数据库处于只读模式，无法修改主凭据"
            )
        }
        val writer = core.saveWriter ?: return@withLock KdbxResult.Failure(
            IllegalStateException("无活动数据库保存通道"),
            "当前无活动数据库"
        )
        val db = core.database.value ?: return@withLock KdbxResult.Failure(
            IllegalStateException("活动数据库为空"),
            "当前无活动数据库"
        )

        val oldPwd = credentials.passwordSnapshot()
        val oldKey = credentials.keyFileSnapshot()

        credentials.rotateCredentials(newPasswordChars, newKeyFileData)

        try {
            val serialized = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { buffer ->
                    KdbxFile.save(buffer, db, newPasswordChars, newKeyFileData)
                }.toByteArray()
            }
            writer(serialized)
            serialized.fill(0)
            // ISSUE-P2-11 (ZT-16)：凭据轮换后旧密文快照必须失效——
            // 本次写盘可能生成了用「旧凭据」加密的 .bak，历史遗留的 .bak 同理，
            // 旧口令仍可将其解开，故成功换密后一律删除活动文件的滚动备份（失败仅告警）。
            fileWriter.deleteBackupQuietly(core.activeFile)
            core.state.value = SessionState.OPENED
            oldPwd?.let { Arrays.fill(it, '0') }
            oldKey?.let { Arrays.fill(it, 0.toByte()) }
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            // 失败时回滚既有凭据
            credentials.restoreCredentials(oldPwd, oldKey)
            KdbxResult.Failure(t, "更新主密码失败: ${t.message}")
        }
    }
}
