package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxProgress
import com.keepasskey.database.history.HistoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 库级 Meta 的「序列化候选 → 落盘 → 发布」事务。 */
internal class DatabaseMetaCommitter(
    private val mutex: Mutex,
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val progress: (Float?) -> Unit,
    private val publishCommittedDatabaseMeta: (KdbxDatabase, KdbxDatabase) -> Boolean,
    private val serialize: suspend (
        KdbxDatabase,
        CharArray?,
        ByteArray?,
        ((Float?) -> Unit)?
    ) -> ByteArray
) {
    /** 候选在 writer 确认之前不会发布到活动会话。 */
    suspend fun commit(
        expectedDatabase: KdbxDatabase,
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxResult<Unit> = mutex.withLock {
        if (core.credentialWriteUncertain.value) return@withLock failure(
            KdbxError.SAVE_WRITE_UNCERTAIN, "数据库写入结果不确定，须先核实或恢复文件"
        )
        if (core.readOnlyMode) return@withLock failure(KdbxError.SAVE_READ_ONLY, "数据库以只读模式打开")
        val writer = core.saveWriter ?: return@withLock failure(KdbxError.SAVE_NO_WRITER, "无活动数据库保存通道")
        val current = core.database.value ?: return@withLock failure(KdbxError.SAVE_NO_DATABASE, "活动数据库为空")
        if (current !== expectedDatabase) return@withLock failure(
            KdbxError.SAVE_SESSION_CHANGED, "提交期间活动数据库已切换"
        )
        val password = credentials.currentPassword()
        val keyFile = credentials.currentKeyFile()
        if (password == null && keyFile == null) return@withLock failure(
            KdbxError.SAVE_CREDENTIALS_LOST, "主密码已被清理"
        )
        executeWrite(current, writer, password, keyFile, transform)
    }

    private suspend fun executeWrite(
        current: KdbxDatabase,
        writer: suspend (ByteArray) -> Unit,
        password: CharArray?,
        keyFile: ByteArray?,
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxResult<Unit> {
        progress(null)
        val pending = PendingMetaWrite()
        try {
            pending.candidate = buildCandidate(current, transform)
            persistCandidate(requireNotNull(pending.candidate), writer, password, keyFile, pending.receipt)
            if (pending.receipt.status != SessionWriteReceipt.Status.COMMITTED) {
                pending.receipt.uncertain()
                markWriteUncertain()
                return failure(KdbxError.SAVE_WRITE_UNCERTAIN, "写出通道未确认库设置提交")
            }
            pending.published = publishCommittedDatabaseMeta(current, requireNotNull(pending.candidate))
            if (!pending.published) {
                markWriteUncertain()
                return failure(KdbxError.SAVE_WRITE_UNCERTAIN, "库设置已写出，但活动会话无法确认采用")
            }
            return KdbxResult.Success(Unit)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // 先按 writer 回执收尾；若已提交，须在重抛取消前发布磁盘对应候选。
                return handleFailure(current, pending, error)
            }
            return handleFailure(current, pending, error)
        } finally {
            if (!pending.published && pending.receipt.status != SessionWriteReceipt.Status.COMMITTED) {
                pending.candidate?.let { clearUncommittedCandidate(current, it) }
            }
        }
    }

    private fun buildCandidate(
        current: KdbxDatabase,
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxDatabase {
        val transformed = transform(current)
        val root = HistoryManager.pruneGroupHistoryByAge(
            transformed.rootGroup,
            transformed.maintenanceHistoryDays
        )
        return if (root !== transformed.rootGroup) transformed.copy(rootGroup = root) else transformed
    }

    private suspend fun persistCandidate(
        candidate: KdbxDatabase,
        writer: suspend (ByteArray) -> Unit,
        password: CharArray?,
        keyFile: ByteArray?,
        receipt: SessionWriteReceipt
    ) = withContext(Dispatchers.Default) {
        val serialized = serialize(candidate, password, keyFile, progress)
        try {
            val trackedWriter = core.trackedSaveWriter
            if (trackedWriter != null) {
                trackedWriter(serialized, receipt)
            } else {
                // 旧 writer 无法报告原子提交点；异常时必须 fail-closed。
                receipt.writing()
                writer(serialized)
                receipt.committed()
            }
            progress(KdbxProgress.DONE)
        } finally {
            serialized.fill(0)
        }
    }

    private suspend fun handleFailure(
        current: KdbxDatabase,
        pending: PendingMetaWrite,
        error: Throwable
    ): KdbxResult<Unit> {
        pending.receipt.failed()
        if (pending.receipt.status == SessionWriteReceipt.Status.COMMITTED) {
            withContext(NonCancellable) {
                if (!pending.published) {
                    pending.published = publishCommittedDatabaseMeta(current, requireNotNull(pending.candidate))
                }
            }
            if (pending.published) progress(KdbxProgress.DONE) else {
                markWriteUncertain()
                progress(null)
            }
            if (error is CancellationException) throw error
            return if (pending.published) KdbxResult.Success(Unit) else failure(
                KdbxError.SAVE_WRITE_UNCERTAIN, "写入已提交但会话发布失败", error
            )
        }
        if (pending.receipt.status == SessionWriteReceipt.Status.UNKNOWN) {
            markWriteUncertain()
            progress(null)
            if (error is CancellationException) throw error
            return failure(KdbxError.SAVE_WRITE_UNCERTAIN, "写入状态不确定", error)
        }
        if (error is CancellationException) {
            progress(null)
            throw error
        }
        progress(null)
        return failure(KdbxError.SAVE_FAILED, "数据库设置保存失败", error)
    }

    private fun failure(code: String, message: String, error: Throwable? = null) =
        KdbxResult.Failure(error ?: IllegalStateException(message), code = code)

    private data class PendingMetaWrite(
        val receipt: SessionWriteReceipt = SessionWriteReceipt(),
        var candidate: KdbxDatabase? = null,
        var published: Boolean = false
    )

    private fun markWriteUncertain() {
        core.credentialWriteUncertain.value = true
        core.readOnlyMode = true
    }

    private fun clearUncommittedCandidate(current: KdbxDatabase, candidate: KdbxDatabase) {
        if (candidate === current) return
        candidate.rootGroup.clearSupersededSensitiveData(current.rootGroup)
        candidate.clearBinaryPool(current.binaries)
        candidate.clearCustomIconPool(current.customIcons)
        val candidateKdf = candidate.header.kdfParameters
        val currentKdf = current.header.kdfParameters
        val sharesArgonSecret = candidateKdf is KdfParameters.Argon2 &&
            currentKdf is KdfParameters.Argon2 && candidateKdf.secretKey != null &&
            candidateKdf.secretKey === currentKdf.secretKey
        if (candidateKdf !== currentKdf && !sharesArgonSecret) candidateKdf.clearSensitive()
    }
}
