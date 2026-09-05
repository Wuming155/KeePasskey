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
     */
    suspend fun open(
        file: File,
        passwordChars: CharArray,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false
    ): KdbxResult<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
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
            val pwd = passwordCache ?: return@withContext KdbxResult.Failure(
                IllegalStateException("主密码已被清理"),
                "主密码凭据丢失，请重新输入主密码"
            )

            try {
                withContext(Dispatchers.IO) {
                    AtomicFileWriter.writeAtomic(file) { os ->
                        KdbxFile.save(os, db, pwd, keyFileCache)
                    }
                }
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
     * 保存或更新分组
     */
    suspend fun saveGroup(group: KdbxGroup) = mutex.withLock {
        if (readOnlyMode) return@withLock
        val currentDb = _database.value ?: return@withLock
        val updatedRoot = if (group.id == currentDb.rootGroup.id) {
            group
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

    private fun cachePassword(passwordChars: CharArray) = synchronized(credentialLock) {
        clearSensitiveCache()
        passwordCache = passwordChars.clone()
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
                newSubgroups[existingIndex] = groupToSave
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

    private fun removeGroup(parent: KdbxGroup, groupId: KdbxUuid): KdbxGroup {
        val newSubgroups = parent.subgroups
            .filter { it.id != groupId }
            .map { removeGroup(it, groupId) }
        return parent.copy(subgroups = newSubgroups)
    }
}
