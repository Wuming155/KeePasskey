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
        if (core.database.value !== expected || ticket !== prepared.ticket || prepared.isWiped) return@withLock unavailable()
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
    @Volatile
    private var wiped = false
    val isWiped: Boolean get() = wiped

    /**
     * `ENG-20261028-31`：**借用**预备密文执行只读消费（典型：把与本地落盘完全相同的那一份字节
     * 上传云端，免除第二遍整库序列化）。
     *
     * 契约与 `SessionEncryptedSnapshot.borrowEncryptedBytes` 同形：闭包内消费，**严禁修改、清零、
     * 驻留或跨协程传递**；借用期即本产物生命周期。已清零（提交完成 / 放弃 / 失败）时 fail-closed
     * 拒绝，绝不把全零字节当成有效密文推上云端。
     */
    suspend fun <T> borrowBytes(block: suspend (ByteArray) -> T): T {
        check(!wiped) { "预备密文已清零，不可再借用" }
        return block(bytes)
    }

    /**
     * `ENG-20261028-31`：为「上传 412 后重入」交付一份**独立拥有**的密文副本
     * （调用方负责用毕清零）。重入侧必须在本产物 [wipe] 之后仍能解析该版本，
     * 故只在该分支付出一次整库拷贝——常规成功路径不产生额外副本。
     */
    fun copyBytesForHandoff(): ByteArray {
        check(!wiped) { "预备密文已清零，不可再交付" }
        return bytes.copyOf()
    }

    fun wipe() {
        wiped = true
        bytes.fill(0)
    }
}
