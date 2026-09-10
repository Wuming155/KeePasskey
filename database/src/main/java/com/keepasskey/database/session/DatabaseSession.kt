package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.history.HistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 活动数据库会话状态机与生命周期管理者。
 * 维护内存中活动密码库的生命周期：CLOSED ↔ LOCKED ↔ OPENED ↔ DIRTY
 */
class DatabaseSession {

    enum class SessionState {
        CLOSED,
        LOCKED,
        OPENED,
        DIRTY
    }

    private val _state = MutableStateFlow(SessionState.CLOSED)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _database = MutableStateFlow<KdbxDatabase?>(null)
    val databaseFlow: StateFlow<KdbxDatabase?> = _database.asStateFlow()

    private var activeFile: File? = null
    private var activePathIdentifier: String? = null
    private var saveWriter: (suspend (ByteArray) -> Unit)? = null
    private var passwordCache: CharArray? = null
    private var keyFileCache: ByteArray? = null
    private val credentialLock = Any()
    // ISSUE-P1-07：会话终止观察者集合——锁定/关闭时同步通知各派生敏态数据的持有方清理
    private val sessionLockObservers = LinkedHashSet<SessionLockObserver>()
    private val observerLock = Any()
    // H4-只读整改：以只读模式打开的会话，一切落盘写操作硬拒绝
    private var readOnlyMode: Boolean = false
    private val logger = Logger.getLogger(DatabaseSession::class.java.name)

    /**
     * ISSUE-P2-11 (ZT-16)：会话级「保存前创建 .bak 滚动备份」偏好。
     *
     * 默认 true 保持既有行为；关闭时全部落盘路径均不生成 `.bak`，并顺带清理历史遗留的
     * `.bak`。由 app 侧 DI 装配期从持久化偏好注入初值，用户切换开关时实时同步。
     * 以 [Volatile] 保证跨线程可见性（写盘可能发生在 IO 调度线程）。
     */
    @Volatile
    var createBackupBeforeSave: Boolean = true

    val currentFile: File?
        get() = activeFile

    val currentPathIdentifier: String?
        get() = activePathIdentifier

    /** 当前会话是否为只读模式（锁定/关闭后重置为 false） */
    val isReadOnly: Boolean
        get() = readOnlyMode

    private val mutex = Mutex()

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
    fun <T> useCredentials(block: (CharArray?, ByteArray?) -> T): T = synchronized(credentialLock) {
        val pwdClone = passwordCache?.clone()
        val keyClone = keyFileCache?.clone()
        block(pwdClone, keyClone)
    }

    /**
     * 创建全新密码库文件并打开会话
     */
    suspend fun create(
        file: File,
        name: String,
        passwordChars: CharArray,
        useArgon2: Boolean = true
    ): KdbxResult<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                val header = KdbxHeader.createDefault(
                    cipherUuid = KdbxConstants.Cipher.AES_256_CBC,
                    useArgon2 = useArgon2
                )
                val rootGroup = KdbxGroup(
                    name = name.ifBlank { "Root" },
                    iconId = 48
                )
                val db = KdbxDatabase(
                    header = header,
                    databaseName = name,
                    databaseDescription = "Created by KeePasskey",
                    rootGroup = rootGroup
                )

                // 原子写盘落盘（ISSUE-P2-11：按会话备份偏好决定是否生成 .bak）
                withContext(Dispatchers.IO) {
                    writeAtomicByBackupPreference(file) { os ->
                        KdbxFile.save(os, db, passwordChars)
                    }
                }

                // 缓存主凭据供会话期写回使用
                activeFile = file
                activePathIdentifier = file.absolutePath
                saveWriter = { bytes ->
                    withContext(Dispatchers.IO) {
                        writeAtomicByBackupPreference(file) { os ->
                            os.write(bytes)
                        }
                    }
                }
                readOnlyMode = false
                cachePassword(passwordChars)
                _database.value = db
                _state.value = SessionState.OPENED

                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "创建密码库失败: ${t.message}")
            }
        }
    }

    /**
     * 打开并解密已有 KDBX 文件（支持直接传入 File）。
     */
    suspend fun open(
        file: File,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false
    ): KdbxResult<Unit> = openStream(
        pathIdentifier = file.absolutePath,
        inputStreamProvider = {
            if (!file.exists()) {
                throw java.io.FileNotFoundException("文件不存在: ${file.absolutePath}")
            }
            FileInputStream(file)
        },
        saveWriter = { bytes ->
            withContext(Dispatchers.IO) {
                writeAtomicByBackupPreference(file) { os ->
                    os.write(bytes)
                }
            }
        },
        passwordChars = passwordChars,
        keyFileData = keyFileData,
        readOnly = readOnly,
        associatedFile = file
    )

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
    ): KdbxResult<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                val db = inputStreamProvider().use { fis ->
                    KdbxFile.load(fis, passwordChars, keyFileData)
                }

                activeFile = associatedFile
                activePathIdentifier = pathIdentifier
                this@DatabaseSession.saveWriter = saveWriter
                readOnlyMode = readOnly
                cachePassword(passwordChars)
                if (keyFileData != null) {
                    synchronized(credentialLock) {
                        keyFileCache = keyFileData.clone()
                    }
                }

                _database.value = db
                _state.value = SessionState.OPENED
                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "解锁密码库失败: ${t.message}")
            }
        }
    }

    /**
     * 保存当前内存中的活动数据库并写入文件/URI 通道
     */
    suspend fun save(): KdbxResult<Unit> = mutex.withLock {
        if (readOnlyMode) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("数据库以只读模式打开"),
                "数据库以只读模式打开，无法保存"
            )
        }
        withContext(Dispatchers.Default) {
            val writer = saveWriter ?: return@withContext KdbxResult.Failure(
                IllegalStateException("无活动数据库保存通道"),
                "未指定活动数据库保存通道"
            )
            val db = _database.value ?: return@withContext KdbxResult.Failure(
                IllegalStateException("活动数据库为空"),
                "当前无活动数据库"
            )
            // P1-10：仅密钥文件会话（主密码为 null/空）下 passwordCache 可为空，
            // 只要密钥文件缓存仍在即可完成保存；两者皆缺失才视为凭据丢失
            val pwd = passwordCache
            if (pwd == null && keyFileCache == null) {
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
                    db.copy(rootGroup = prunedRoot).also { _database.value = it }
                } else {
                    db
                }

                // TASK-42 整改（P2-2）：Argon2 派生与流加密为 CPU 密集，序列化走 Default；
                // 仅字节落盘（writeAtomic + fsync）走 IO——对齐 exportToBytes 的既有调度先例
                val serialized = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { buffer ->
                        KdbxFile.save(buffer, dbToSave, pwd, keyFileCache)
                    }.toByteArray()
                }
                writer(serialized)
                // 序列化缓冲即整库密文（头部外全加密），写毕即擦，避免缓冲滞留
                serialized.fill(0)
                _state.value = SessionState.OPENED
                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "保存数据库失败: ${t.message}")
            }
        }
    }

    /**
     * 更新或保存条目
     */
    suspend fun saveEntry(entry: KdbxEntry) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        val updatedRoot = updateOrAddEntry(currentDb.rootGroup, entry)
        // ISSUE-P2-06：copy-on-write 替换前定点擦除——身份集合保证不误伤新树仍共享的受保护实例
        currentDb.rootGroup.clearSupersededSensitiveData(updatedRoot)
        _database.value = currentDb.copy(rootGroup = updatedRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 删除条目
     */
    suspend fun deleteEntry(entryId: KdbxUuid) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        val updatedRoot = removeEntry(currentDb.rootGroup, entryId)
        // ISSUE-P2-06 修正：删除路径禁止身份擦除——删除没有「替换树」，而调用方（回收站软删）
        // 会在 deleteEntry 之后用与旧条目**共享同一 ProtectedString 实例**的 moved 副本重新
        // saveEntry；若按「新树未包含 = 已下线」判定，会把即将复用的存活字段一并清空，
        // 条目遂成空壳、后续 save() 序列化抛 IllegalStateException（app 回归实测复现）。
        // 下线实例交由 GC 回收；有替换树的写入路径（saveEntry/saveGroup/updateDatabaseMeta）仍照常擦除。
        _database.value = currentDb.copy(rootGroup = updatedRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 保存或更新分组。
     *
     * P0-1 防御性保护：更新既有分组（含根分组）时，若传入分组不携带任何子项而既有分组含有子项，
     * 经 [preserveChildrenIfMissing] 保留既有子项，防止「重命名/改图标」等仅更新元数据的
     * 调用路径意外清空子条目与子分组。
     */
    suspend fun saveGroup(group: KdbxGroup) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        val updatedRoot = if (group.id == currentDb.rootGroup.id) {
            preserveChildrenIfMissing(currentDb.rootGroup, group)
        } else {
            updateOrAddGroup(currentDb.rootGroup, group)
        }
        // ISSUE-P2-06：分组保存可能下线旧条目/旧字段实例，替换前定点擦除
        currentDb.rootGroup.clearSupersededSensitiveData(updatedRoot)
        _database.value = currentDb.copy(rootGroup = updatedRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 允许受控原子修改数据库顶层元数据与墓碑列表（例如 recycleBinUuid、deletedObjects 追加）。
     * 修改后置为 SessionState.DIRTY 状态，供后续统一 save() 序列化落盘。
     */
    suspend fun updateDatabaseMeta(transform: (KdbxDatabase) -> KdbxDatabase) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        val updated = transform(currentDb)
        // ISSUE-P2-06：元数据变换同样可能下线旧条目实例，替换前定点擦除
        currentDb.rootGroup.clearSupersededSensitiveData(updated.rootGroup)
        _database.value = updated
        _state.value = SessionState.DIRTY
    }

    /**
     * 删除分组
     */
    suspend fun deleteGroup(groupId: KdbxUuid) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        if (groupId == currentDb.rootGroup.id) return@withLock
        val updatedRoot = removeGroup(currentDb.rootGroup, groupId)
        // ISSUE-P2-06 修正：同 deleteEntry——删除路径无替换树，禁止身份擦除（会误伤调用方复用中的共享实例）
        _database.value = currentDb.copy(rootGroup = updatedRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 批量移动条目
     */
    suspend fun batchMoveEntries(entryIds: Set<KdbxUuid>, targetGroupId: KdbxUuid?) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        val entriesToMove = currentDb.rootGroup.allEntries().filter { it.id in entryIds }
        var currentRoot = currentDb.rootGroup
        for (e in entriesToMove) {
            currentRoot = removeEntry(currentRoot, e.id)
        }
        for (e in entriesToMove) {
            val movedEntry = e.copy(parentGroupId = targetGroupId)
            currentRoot = updateOrAddEntry(currentRoot, movedEntry)
        }
        // ISSUE-P2-06：批量移动经 remove+update 重建树，替换前定点擦除中间态下线实例
        currentDb.rootGroup.clearSupersededSensitiveData(currentRoot)
        _database.value = currentDb.copy(rootGroup = currentRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 批量删除条目
     */
    suspend fun batchDeleteEntries(entryIds: Set<KdbxUuid>) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        var currentRoot = currentDb.rootGroup
        for (id in entryIds) {
            currentRoot = removeEntry(currentRoot, id)
        }
        // ISSUE-P2-06 修正：批量删除同样无替换树，禁止身份擦除（同 deleteEntry 说明）
        _database.value = currentDb.copy(rootGroup = currentRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 仅供测试使用：直接注入内存数据库模型（P3-9 整改：@VisibleForTesting 显式约束，
     * 生产代码调用视为契约违规；app 模块单测跨模块注入仍可访问）
     */
    @androidx.annotation.VisibleForTesting
    fun setDatabaseForTesting(db: KdbxDatabase) {
        _database.value = db
        _state.value = SessionState.OPENED
    }

    /**
     * TASK-13 整改：将当前内存数据库序列化为 KDBX 字节流（SAF 导出用）。
     * 与 [save] 相同的凭据要求与序列化管线（含密钥文件复合密钥），但不落盘到活动文件，
     * 字节交由调用方处置；锁定/关闭状态（内存树已销毁）下如实失败。
     */
    suspend fun exportToBytes(): KdbxResult<ByteArray> = mutex.withLock {
        val db = _database.value ?: return@withLock KdbxResult.Failure(
            IllegalStateException("活动数据库为空"),
            "当前无活动数据库（已锁定或未打开）"
        )
        val pwd = passwordCache
        if (pwd == null && keyFileCache == null) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("主密码已被清理"),
                "主密码凭据丢失，无法导出"
            )
        }
        withContext(Dispatchers.Default) {
            try {
                val bytes = ByteArrayOutputStream().also { baos ->
                    KdbxFile.save(baos, db, pwd, keyFileCache)
                }.toByteArray()
                KdbxResult.Success(bytes)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "导出数据库失败: ${t.message}")
            }
        }
    }

    /**
     * TASK-13 整改：导出会话绑定的密钥文件原始字节（SAF 导出用）。
     * [keyFileCache] 缓存的是用户导入时的密钥文件原件字节（克隆语义）；
     * 会话未绑定密钥文件时返回 null，由调用方映射为可理解的错误提示。
     */
    fun exportKeyFileBytes(): ByteArray? = synchronized(credentialLock) {
        keyFileCache?.clone()
    }

    /**
     * 锁定当前数据库：保留文件路径引用，但物理销毁内存中的敏感主密码与数据库明文树
     */
    suspend fun lock() = mutex.withLock {
        readOnlyMode = false
        clearSensitiveCache()
        _database.value?.clearSensitiveData()
        _database.value = null
        _state.value = SessionState.LOCKED
        // ISSUE-P1-07：锁定即销毁——连同派生的敏态产物（同步缓存密文快照）一并终止生命周期
        notifySessionLockObservers()
    }

    /**
     * 完全关闭数据库会话并置空一切关联
     */
    suspend fun close() = mutex.withLock {
        readOnlyMode = false
        clearSensitiveCache()
        _database.value?.clearSensitiveData()
        _database.value = null
        activeFile = null
        activePathIdentifier = null
        saveWriter = null
        _state.value = SessionState.CLOSED
        // ISSUE-P1-07：关闭隐含锁定，派生敏态产物同样必须清理
        notifySessionLockObservers()
    }

    /**
     * P0-3 更改主凭据：更新内存中的主密码/密钥文件缓存，并立即触发全量重加密写盘。
     * KDBX4 规范在每次保存时均生成全新的随机 MasterSeed 与 Salt，因此更换凭据等价于以新凭据重新序列化保存。
     */
    suspend fun changeCredentials(
        newPasswordChars: CharArray?,
        newKeyFileData: ByteArray? = keyFileCache?.clone()
    ): KdbxResult<Unit> = mutex.withLock {
        if (readOnlyMode) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("数据库处于只读模式，无法修改主凭据"),
                "数据库处于只读模式，无法修改主凭据"
            )
        }
        val writer = saveWriter ?: return@withLock KdbxResult.Failure(
            IllegalStateException("无活动数据库保存通道"),
            "当前无活动数据库"
        )
        val db = _database.value ?: return@withLock KdbxResult.Failure(
            IllegalStateException("活动数据库为空"),
            "当前无活动数据库"
        )

        val oldPwd = passwordCache?.clone()
        val oldKey = keyFileCache?.clone()

        synchronized(credentialLock) {
            passwordCache?.let { Arrays.fill(it, '0') }
            passwordCache = newPasswordChars?.clone()
            if (newKeyFileData != null) {
                keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
                keyFileCache = newKeyFileData.clone()
            }
        }

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
            deleteBackupQuietly(activeFile)
            _state.value = SessionState.OPENED
            oldPwd?.let { Arrays.fill(it, '0') }
            oldKey?.let { Arrays.fill(it, 0.toByte()) }
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            // 失败时回滚既有凭据
            synchronized(credentialLock) {
                passwordCache?.let { Arrays.fill(it, '0') }
                passwordCache = oldPwd
                keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
                keyFileCache = oldKey
            }
            KdbxResult.Failure(t, "更新主密码失败: ${t.message}")
        }
    }

    /**
     * ISSUE-P2-11 (ZT-16)：按会话备份偏好执行原子写盘。
     *
     * 关闭偏好时既不再生成 `.bak`，也顺带清理历史遗留的 `.bak`——
     * 用户关闭「保存前备份」后旧密文快照不应继续驻留磁盘。
     * 在写盘前一次性读取偏好，保证同一次写入的「是否备份」与「是否清理」语义一致。
     */
    private fun writeAtomicByBackupPreference(targetFile: File, writer: (OutputStream) -> Unit) {
        val createBackup = createBackupBeforeSave
        AtomicFileWriter.writeAtomic(targetFile, createBackup, writer)
        if (!createBackup) {
            deleteBackupQuietly(targetFile)
        }
    }

    /**
     * ISSUE-P2-11 (ZT-16)：静默删除滚动备份。
     *
     * IO 异常/权限不足一律记录告警，绝不阻断主流程（备份清理失败不影响本次写盘结果）。
     * [targetFile] 为 null 表示当前会话无本地文件（如 SAF 流式通道），无需清理。
     */
    private fun deleteBackupQuietly(targetFile: File?) {
        if (targetFile == null) {
            return
        }
        try {
            AtomicFileWriter.deleteBackup(targetFile)
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "清理滚动备份失败（仅告警，不阻断主流程）: ${targetFile.absolutePath}",
                e
            )
        }
    }

    private fun cachePassword(passwordChars: CharArray?) = synchronized(credentialLock) {
        clearSensitiveCache()
        passwordCache = passwordChars?.clone()
    }

    private fun clearSensitiveCache() = synchronized(credentialLock) {
        passwordCache?.let { Arrays.fill(it, '0') }
        passwordCache = null
        keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
        keyFileCache = null
    }

    private fun updateOrAddEntry(group: KdbxGroup, entry: KdbxEntry): KdbxGroup {
        val targetParentId = entry.parentGroupId ?: group.id
        if (group.id == targetParentId) {
            val existingIndex = group.entries.indexOfFirst { it.id == entry.id }
            val newEntries = group.entries.toMutableList()
            if (existingIndex >= 0) {
                newEntries[existingIndex] = entry
            } else {
                newEntries.add(entry)
            }
            return group.copy(entries = newEntries)
        }

        val newSubgroups = group.subgroups.map { sub ->
            updateOrAddEntry(sub, entry)
        }
        return group.copy(subgroups = newSubgroups)
    }

    private fun removeEntry(group: KdbxGroup, entryId: KdbxUuid): KdbxGroup {
        val newEntries = group.entries.filter { it.id != entryId }
        val newSubgroups = group.subgroups.map { removeEntry(it, entryId) }
        return group.copy(entries = newEntries, subgroups = newSubgroups)
    }

    private fun updateOrAddGroup(parent: KdbxGroup, groupToSave: KdbxGroup): KdbxGroup {
        val targetParentId = groupToSave.parentGroupId ?: parent.id
        if (parent.id == targetParentId) {
            val existingIndex = parent.subgroups.indexOfFirst { it.id == groupToSave.id }
            val newSubgroups = parent.subgroups.toMutableList()
            if (existingIndex >= 0) {
                // P0-1 保护性合并：替换既有分组前保留其子项（详见 preserveChildrenIfMissing）
                newSubgroups[existingIndex] = preserveChildrenIfMissing(parent.subgroups[existingIndex], groupToSave)
            } else {
                newSubgroups.add(groupToSave)
            }
            return parent.copy(subgroups = newSubgroups)
        }

        val newSubgroups = parent.subgroups.map { sub ->
            updateOrAddGroup(sub, groupToSave)
        }
        return parent.copy(subgroups = newSubgroups)
    }

    /**
     * P0-1 防御性合并：更新既有分组时，若调用方传入的新分组不携带任何子项
     * （entries 与 subgroups 均为空）而既有分组含有子项，则把既有子项原样并入新分组，
     * 确保重命名/改图标等仅更新元数据的保存路径不会清空既有分组的子条目与子分组。
     * 若新分组自身携带子项（如回收站移动、合并引擎回写等显式重建场景），则以其为准，不做合并。
     */
    private fun preserveChildrenIfMissing(existing: KdbxGroup, incoming: KdbxGroup): KdbxGroup {
        val incomingHasChildren = incoming.entries.isNotEmpty() || incoming.subgroups.isNotEmpty()
        val existingHasChildren = existing.entries.isNotEmpty() || existing.subgroups.isNotEmpty()
        if (!incomingHasChildren && existingHasChildren) {
            return incoming.copy(entries = existing.entries, subgroups = existing.subgroups)
        }
        return incoming
    }

    private fun removeGroup(parent: KdbxGroup, groupId: KdbxUuid): KdbxGroup {
        val newSubgroups = parent.subgroups
            .filter { it.id != groupId }
            .map { removeGroup(it, groupId) }
        return parent.copy(subgroups = newSubgroups)
    }
}
