package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.database.io.WipableByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Arrays

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
internal class SessionPersistence(
    private val mutex: Mutex,
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val fileWriter: SessionFileWriter
) {

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
                val serialized = serializeToBytes(dbToSave, pwd, credentials.currentKeyFile())
                writer(serialized)
                // 序列化缓冲即整库密文（头部外全加密），写毕即擦，避免缓冲滞留
                serialized.fill(0)
                core.state.value = DatabaseSession.SessionState.OPENED
                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "保存数据库失败: ${t.message}")
            }
        }
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
                KdbxResult.Success(serializeToBytes(db, pwd, credentials.currentKeyFile()))
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "导出数据库失败: ${t.message}")
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
    suspend fun changeCredentials(newPasswordChars: CharArray?): KdbxResult<Unit> {
        val keyFileSnapshot = credentials.keyFileSnapshot()
        return try {
            changeCredentials(newPasswordChars, keyFileSnapshot)
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
            // ISSUE-P3-118：同型第三处（换密路径）——序列化缓冲同样须具名并在用毕后清零
            val serialized = serializeToBytes(db, newPasswordChars, newKeyFileData)
            writer(serialized)
            serialized.fill(0)
            // ISSUE-P2-11 (ZT-16)：凭据轮换后旧密文快照必须失效——
            // 本次写盘可能生成了用「旧凭据」加密的 .bak，历史遗留的 .bak 同理，
            // 旧口令仍可将其解开，故成功换密后一律删除活动文件的滚动备份（失败仅告警）。
            fileWriter.deleteBackupQuietly(core.activeFile)
            core.state.value = DatabaseSession.SessionState.OPENED
            oldPwd?.let { Arrays.fill(it, '0') }
            oldKey?.let { Arrays.fill(it, 0.toByte()) }
            KdbxResult.Success(Unit)
        } catch (t: Throwable) {
            // 失败时回滚既有凭据
            credentials.restoreCredentials(oldPwd, oldKey)
            KdbxResult.Failure(t, "更新主密码失败: ${t.message}")
        }
    }

    /**
     * 整库序列化（三条写盘路径的**唯一**实现）。
     *
     * ISSUE-P3-118：序列化缓冲必须**具名**并在用毕后清零——`toByteArray()` 只返回副本，
     * 内部缓冲是第二份整库密文，等待 GC 不构成擦除（`reset()` 也不清内容）。
     * 返回数组由调用方负责清零。
     */
    private suspend fun serializeToBytes(
        db: KdbxDatabase,
        pwd: CharArray?,
        keyFile: ByteArray?
    ): ByteArray {
        val buffer = WipableByteArrayOutputStream()
        return try {
            withContext(Dispatchers.Default) {
                KdbxFile.save(buffer, db, pwd, keyFile)
                buffer.toByteArray()
            }
        } finally {
            buffer.wipe()
        }
    }
}
