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

    private val mutex = Mutex()

    val currentFile: File?
        get() = activeFile

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
     * 打开并解密已有 KDBX 文件
     */
    suspend fun open(
        file: File,
        passwordChars: CharArray,
        keyFileData: ByteArray? = null
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
                cachePassword(passwordChars)
                if (keyFileData != null) {
                    keyFileCache = keyFileData.clone()
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
        val currentDb = _database.value ?: return@withLock
        val updatedRoot = updateOrAddEntry(currentDb.rootGroup, entry)
        _database.value = currentDb.copy(rootGroup = updatedRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 删除条目
     */
    suspend fun deleteEntry(entryId: KdbxUuid) = mutex.withLock {
        val currentDb = _database.value ?: return@withLock
        val updatedRoot = removeEntry(currentDb.rootGroup, entryId)
        _database.value = currentDb.copy(rootGroup = updatedRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 保存或更新分组
     */
    suspend fun saveGroup(group: KdbxGroup) = mutex.withLock {
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
     * 删除分组
     */
    suspend fun deleteGroup(groupId: KdbxUuid) = mutex.withLock {
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
        val currentDb = _database.value ?: return@withLock
        var currentRoot = currentDb.rootGroup
        for (id in entryIds) {
            currentRoot = removeEntry(currentRoot, id)
        }
        _database.value = currentDb.copy(rootGroup = currentRoot)
        _state.value = SessionState.DIRTY
    }

    /**
     * 锁定当前数据库：保留文件路径引用，但物理销毁内存中的敏感主密码与数据库明文树
     */
    suspend fun lock() = mutex.withLock {
        clearSensitiveCache()
        _database.value?.clearSensitiveData()
        _database.value = null
        _state.value = SessionState.LOCKED
    }

    /**
     * 完全关闭数据库会话并置空一切关联
     */
    suspend fun close() = mutex.withLock {
        clearSensitiveCache()
        _database.value?.clearSensitiveData()
        _database.value = null
        activeFile = null
        _state.value = SessionState.CLOSED
    }

    private fun cachePassword(passwordChars: CharArray) {
        clearSensitiveCache()
        passwordCache = passwordChars.clone()
    }

    private fun clearSensitiveCache() {
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
