package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxProgress
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
    private val fileWriter: SessionFileWriter,
    /**
     * ISSUE-P3-368：保存链进度上报（0..1 确定进度 / null 分段不确定段）。
     * 由 `DatabaseSession` 注入 StateFlow 写入口；只承载数值，不捕获任何敏感引用（AC③）。
     */
    private val progress: (Float?) -> Unit = {}
) {

    /**
     * 保存当前内存中的活动数据库并写入文件/URI 通道
     */
    suspend fun save(): KdbxResult<Unit> = mutex.withLock {
        if (core.readOnlyMode) {
            return@withLock KdbxResult.Failure(
                IllegalStateException("数据库以只读模式打开"),
                code = KdbxError.SAVE_READ_ONLY
            )
        }
        withContext(Dispatchers.Default) {
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
                // ISSUE-P3-368：失败清进度（不留半程残值）
                progress(null)
                KdbxResult.Failure(t, code = KdbxError.SAVE_FAILED)
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
            } catch (t: Throwable) {
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
                code = KdbxError.CREDENTIALS_READ_ONLY
            )
        }
        val writer = core.saveWriter ?: return@withLock KdbxResult.Failure(
            IllegalStateException("无活动数据库保存通道"),
            code = KdbxError.CREDENTIALS_NO_WRITER
        )
        val db = core.database.value ?: return@withLock KdbxResult.Failure(
            IllegalStateException("活动数据库为空"),
                code = KdbxError.CREDENTIALS_NO_DATABASE
        )

        val oldPwd = credentials.passwordSnapshot()
        val oldKey = credentials.keyFileSnapshot()

        credentials.rotateCredentials(newPasswordChars, newKeyFileData)

        try {
            // ISSUE-P3-118：同型第三处（换密路径）——序列化缓冲同样须具名并在用毕后清零
            val serialized = serializeToBytes(db, newPasswordChars, newKeyFileData)
            try {
                writer(serialized)
                // ISSUE-P2-11 (ZT-16)：凭据轮换后旧密文快照必须失效——
                // 本次写盘可能生成了用「旧凭据」加密的 .bak，历史遗留的 .bak 同理，
                // 旧口令仍可将其解开，故成功换密后一律删除活动文件的滚动备份（失败仅告警）。
                fileWriter.deleteBackupQuietly(core.activeFile)
                core.state.value = DatabaseSession.SessionState.OPENED
                oldPwd?.let { Arrays.fill(it, '0') }
                oldKey?.let { Arrays.fill(it, 0.toByte()) }
                KdbxResult.Success(Unit)
            } finally {
                // ISSUE-P3-551：换密路径同型——写盘异常时整库密文缓冲不得滞留堆上
                serialized.fill(0)
            }
        } catch (t: Throwable) {
            // 失败时回滚既有凭据
            credentials.restoreCredentials(oldPwd, oldKey)
            KdbxResult.Failure(t, code = KdbxError.CREDENTIALS_CHANGE_FAILED)
        }
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
    suspend fun changeKeyFileOnly(newKeyFileData: ByteArray?): KdbxResult<Unit> {
        val pwdSnapshot = credentials.passwordSnapshot()
        if (pwdSnapshot == null && newKeyFileData == null) {
            return KdbxResult.Failure(
                IllegalStateException("会话无主密码分量，解绑密钥文件将不剩任何解锁因子"),
                code = KdbxError.CREDENTIALS_KEYFILE_ONLY_UNBIND
            )
        }
        return try {
            changeCredentials(pwdSnapshot, newKeyFileData)
        } finally {
            pwdSnapshot?.fill('0')
        }
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
        return try {
            withContext(Dispatchers.Default) {
                KdbxFile.save(buffer, db, pwd, keyFile, onProgress)
                buffer.toByteArray()
            }
        } finally {
            buffer.wipe()
        }
    }
}
