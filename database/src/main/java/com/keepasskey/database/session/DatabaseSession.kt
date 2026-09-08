package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
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
import java.util.Arrays

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
    private var passwordCache: CharArray? = null
    private var keyFileCache: ByteArray? = null
    private val credentialLock = Any()
    // H4-只读整改：以只读模式打开的会话，一切落盘写操作硬拒绝
    private var readOnlyMode: Boolean = false

    val currentFile: File?
        get() = activeFile

    /** 当前会话是否为只读模式（锁定/关闭后重置为 false） */
    val isReadOnly: Boolean
        get() = readOnlyMode

    private val mutex = Mutex()

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

                // 原子写盘落盘
                withContext(Dispatchers.IO) {
                    AtomicFileWriter.writeAtomic(file) { os ->
                        KdbxFile.save(os, db, passwordChars)
                    }
                }

                // 缓存主凭据供会话期写回使用
                activeFile = file
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
     * 打开并解密已有 KDBX 文件。
     * [readOnly] 为 true 时进入只读会话：后续 save() 硬拒绝、内存树变更方法 no-op。
     *
     * [passwordChars] 允许为 null 或空数组（P1-10：仅密钥文件解锁，
     * 对齐官方 KeyUtil.CreateKey 对空密码不添加密码分量的语义）。
     */
    suspend fun open(
        file: File,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false
    ): KdbxResult<Unit> = mutex.withLock {
        // 全链路流式读取与 CPU 密集段（KDF 派生 / 解密 / XML 解析）交织，统一在 Default 执行，
        // 防止 Argon2 长时间占死 IO 线程池挤占磁盘/网络任务（save 侧对称：CPU 在 Default、写盘在 IO）
        withContext(Dispatchers.Default) {
            try {
                if (!file.exists()) {
                    return@withContext KdbxResult.Failure(
                        IllegalArgumentException("文件不存在: ${file.absolutePath}"),
                        "数据库文件不存在"
                    )
                }

                val db = FileInputStream(file).use { fis ->
                    KdbxFile.load(fis, passwordChars, keyFileData)
                }

                activeFile = file
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
     * 保存当前内存中的活动数据库并写入文件
     */
    suspend fun save(): KdbxResult<Unit> = mutex.withLock {
        if (readOnlyMode) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("数据库以只读模式打开"),
                "数据库以只读模式打开，无法保存"
            )
        }
        withContext(Dispatchers.Default) {
            val file = activeFile ?: return@withContext KdbxResult.Failure(
                IllegalStateException("无活动数据库文件"),
                "未指定活动数据库文件"
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
                // TASK-42 整改（P2-2）：Argon2 派生与流加密为 CPU 密集，序列化走 Default；
                // 仅字节落盘（writeAtomic + fsync）走 IO——对齐 exportToBytes 的既有调度先例
                val serialized = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { buffer ->
                        KdbxFile.save(buffer, db, pwd, keyFileCache)
                    }.toByteArray()
                }
                withContext(Dispatchers.IO) {
                    AtomicFileWriter.writeAtomic(file) { os ->
                        os.write(serialized)
                    }
                }
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
        _database.value = transform(currentDb)
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
        _database.value = currentDb.copy(rootGroup = currentRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 仅供测试使用：直接注入内存数据库模型
     */
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
        _state.value = SessionState.CLOSED
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
        val file = activeFile ?: return@withLock KdbxResult.Failure(
            IllegalStateException("无活动数据库文件"),
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
            withContext(Dispatchers.IO) {
                AtomicFileWriter.writeAtomic(file) { os ->
                    KdbxFile.save(os, db, newPasswordChars, newKeyFileData)
                }
            }
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
