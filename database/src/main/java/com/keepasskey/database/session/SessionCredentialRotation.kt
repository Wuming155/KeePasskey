package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 凭据轮换的提交事务；调用者持会话 mutex，候选准备与写出均可取消。
 * 现行缓存直到提交证据成立才改变，因此不制造旧凭据快照，也无需失败回滚。
 * 本类拥有候选副本与序列化副本；提交时候选所有权转移到缓存，余下缓冲一律 finally 擦除。
 */
internal class SessionCredentialRotation(
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val fileWriter: SessionFileWriter,
    private val serialize: suspend (KdbxDatabase, CharArray?, ByteArray?) -> ByteArray
) {
    suspend fun execute(password: CharArray?, keyFile: ByteArray?): KdbxResult<Unit> {
        validate(password, keyFile)?.let { return it }
        val request = currentCoroutineContext()[SessionCredentialRequest]
        val nextTicket = core.credentialTicket?.next()
        val database = core.database.value!!
        val writer = core.saveWriter!!
        val trackedWriter = core.trackedSaveWriter
        var candidatePassword: CharArray? = null
        var candidateKey: ByteArray? = null
        var serialized: ByteArray? = null
        var adopted = false
        val receipt = SessionWriteReceipt()
        try {
            candidatePassword = password?.copyOf()
            candidateKey = keyFile?.copyOf()
            // 在分配结果所在的调度器内登记所有权，覆盖 withContext 返回取消窗口。
            withContext(Dispatchers.Default) {
                serialized = serialize(database, candidatePassword, candidateKey)
            }
            currentCoroutineContext().ensureActive()
            // 会话 mutex 从准备到采用持续持有，锁库/换库/写树不能插入提交窗口。
            if (trackedWriter != null) {
                trackedWriter(serialized!!, receipt)
            } else {
                // 旧式任意 writer 无法证明异常前是否已经改变目标：失败只能判不确定。
                receipt.writing()
                writer(serialized!!)
                receipt.committed()
            }
            if (receipt.status != SessionWriteReceipt.Status.COMMITTED) {
                receipt.uncertain()
                return KdbxResult.Failure(
                    IllegalStateException("写出通道未确认凭据提交"),
                    code = KdbxError.CREDENTIALS_WRITE_UNCERTAIN
                )
            }
            return KdbxResult.Success(Unit)
        } catch (error: Throwable) {
            receipt.failed()
            if (error is CancellationException) {
                if (receipt.status == SessionWriteReceipt.Status.COMMITTED ||
                    receipt.status == SessionWriteReceipt.Status.UNKNOWN
                ) {
                    throw CredentialRotationCancellationException(receipt.status, error)
                }
                throw error
            }
            if (receipt.status == SessionWriteReceipt.Status.COMMITTED) return KdbxResult.Success(Unit)
            val code = if (receipt.status == SessionWriteReceipt.Status.UNKNOWN) {
                KdbxError.CREDENTIALS_WRITE_UNCERTAIN
            } else {
                KdbxError.CREDENTIALS_CHANGE_FAILED
            }
            return KdbxResult.Failure(error, code = code)
        } finally {
            try {
                request?.recordWriteStatus(receipt.status)
                // 无挂起点的缓存采用先于任何调度器跳转，不会因调用者取消而漏掉。
                if (receipt.status == SessionWriteReceipt.Status.COMMITTED) {
                    credentials.adoptOwnedCredentials(candidatePassword, candidateKey)
                    adopted = true
                    core.adoptCredentialTicket(nextTicket)
                    request?.recordCommitted(nextTicket)
                    core.state.value = DatabaseSession.SessionState.OPENED
                    // ISSUE-P1-631：换密写盘确认后，库以**新凭据**密文重写——持久化基线必须同步
                    // 前移为新密文（旧基线仍以旧凭据加密，登记它只会让后续解析失败误入缺失分支）。
                    // 采用新凭据之后登记，保证登记值与会话当前凭据一致；克隆为同步操作，无挂起点。
                    core.retainPersistedBaselineBestEffort(serialized, core.activePathIdentifier)
                    // NonCancellable 仅包本地备份删除；不包计算、writer、网络或生物识别等待。
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { fileWriter.deleteBackupQuietly(core.activeFile) }
                    }
                } else if (receipt.status == SessionWriteReceipt.Status.UNKNOWN) {
                    core.credentialWriteUncertain.value = true
                    core.readOnlyMode = true
                }
            } finally {
                if (!adopted) {
                    candidatePassword?.fill('0')
                    candidateKey?.fill(0)
                }
                serialized?.fill(0)
            }
        }
    }

    private fun validate(password: CharArray?, keyFile: ByteArray?): KdbxResult.Failure? {
        val code = when {
            core.credentialWriteUncertain.value -> KdbxError.CREDENTIALS_WRITE_UNCERTAIN
            core.readOnlyMode -> KdbxError.CREDENTIALS_READ_ONLY
            core.saveWriter == null -> KdbxError.CREDENTIALS_NO_WRITER
            core.database.value == null -> KdbxError.CREDENTIALS_NO_DATABASE
            (password == null || password.isEmpty()) && keyFile == null -> KdbxError.CREDENTIALS_KEYFILE_ONLY_UNBIND
            else -> return null
        }
        return KdbxResult.Failure(IllegalStateException("当前会话不能轮换凭据"), code = code)
    }
}
