package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 独占密文快照；身份与载荷在同一次会话锁内取得，调用方必须 close/wipe。 */
class SessionEncryptedSnapshot internal constructor(
    val pathIdentifier: String,
    val vaultName: String,
    val ticket: SessionCredentialTicket,
    private val bytes: ByteArray
) : AutoCloseable {
    /** 借用密文，仅在闭包内消费，严禁修改、清零或驻留。 */
    suspend fun <T> borrowEncryptedBytes(block: suspend (ByteArray) -> T): T = block(bytes)
    fun wipe() = bytes.fill(0)
    override fun close() = wipe()
}

internal class SessionEncryptedSnapshots(
    private val mutex: Mutex,
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val serialize: suspend (KdbxDatabase, CharArray?, ByteArray?) -> ByteArray
) {
    suspend fun capture(): KdbxResult<SessionEncryptedSnapshot> = mutex.withLock {
        val database = core.database.value ?: return@withLock unavailable()
        val ticket = core.credentialTicket ?: return@withLock unavailable()
        val path = core.activePathIdentifier ?: return@withLock unavailable()
        val password = credentials.currentPassword()
        val keyFile = credentials.currentKeyFile()
        if (password == null && keyFile == null) return@withLock unavailable()
        var owned: ByteArray? = null
        try {
            owned = serialize(database, password, keyFile)
            val snapshot = SessionEncryptedSnapshot(path, database.databaseName, ticket, owned)
            owned = null
            KdbxResult.Success(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            KdbxResult.Failure(error, code = KdbxError.EXPORT_FAILED)
        } finally {
            owned?.fill(0)
        }
    }

    suspend fun copyPrepared(
        expected: KdbxDatabase,
        prepared: PreparedSerializedSave
    ): KdbxResult<SessionEncryptedSnapshot> = mutex.withLock {
        val ticket = core.credentialTicket ?: return@withLock unavailable()
        val path = core.activePathIdentifier ?: return@withLock unavailable()
        if (core.database.value !== expected || ticket !== prepared.ticket) return@withLock unavailable()
        KdbxResult.Success(SessionEncryptedSnapshot(path, expected.databaseName, ticket, prepared.bytes.copyOf()))
    }

    private fun unavailable() = KdbxResult.Failure(
        IllegalStateException("快照对应的活动会话已失效"), code = KdbxError.SAVE_SESSION_CHANGED
    )
}

/** 预备密文由调用方独占，提交或放弃后必须 wipe；复制只交付独立密文所有权。 */
class PreparedSerializedSave internal constructor(
    internal val bytes: ByteArray,
    internal val prunedRoot: com.keepasskey.core.model.KdbxGroup,
    internal val ticket: SessionCredentialTicket? = null
) {
    fun wipe() = bytes.fill(0)
}
