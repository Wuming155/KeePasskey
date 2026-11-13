package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import java.io.FileInputStream
import java.security.MessageDigest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Writes whose target identity must be captured and validated under the session lock. */
class DatabaseSessionWriteIntents internal constructor(
    private val mutex: Mutex,
    private val core: SessionCore,
    private val persistence: SessionPersistence
) {
    /** Capture a borrowed database reference and its current write target atomically. */
    suspend fun captureSnapshot(): DatabaseSessionWriteSnapshot? = mutex.withLock {
        core.database.value?.let { database ->
            DatabaseSessionWriteSnapshot(database, core.activePathIdentifier, core.activeFile)
        }
    }

    /**
     * 只在指定数据库仍为已落盘活动版本时捕获密文摘要，期间持有会话提交锁。
     * 私有文件读取正式文件；SAF 使用已读取或写盘确认的密文基线，避免额外物化整库。
     * 回执绑定数据库身份，不能单独作为内容已同步的证据。
     */
    suspend fun digestStableFileFor(expectedDatabase: KdbxDatabase): StableDatabaseFileDigest? = mutex.withLock {
        if (core.database.value !== expectedDatabase ||
            core.state.value != DatabaseSession.SessionState.OPENED
        ) return@withLock null

        val pathIdentifier = core.activePathIdentifier ?: return@withLock null
        val file = core.activeFile
        if (file == null) {
            if (core.persistedBaselinePathIdentifier() != pathIdentifier) return@withLock null
            return@withLock withContext(Dispatchers.Default) {
                core.persistedBaselineDigest()?.let { digest ->
                    StableDatabaseFileDigest(pathIdentifier, pathIdentifier, digest)
                }
            }
        }
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            val digest = MessageDigest.getInstance(SHA_256)
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            try {
                FileInputStream(file).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                StableDatabaseFileDigest(pathIdentifier, file.absolutePath, digest.digest().toHexString())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } finally {
                buffer.fill(0)
            }
        }
    }

    /** Commit a database Meta candidate only after the real writer confirms persistence. */
    suspend fun commitDatabaseMeta(
        expectedDatabase: KdbxDatabase,
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxResult<Unit> = persistence.commitDatabaseMeta(expectedDatabase, transform)
}

/** A digest captured under the database session commit lock for one active database identity. */
data class StableDatabaseFileDigest(
    val pathIdentifier: String,
    val filePath: String,
    val sha256: String
)

private const val SHA_256 = "SHA-256"
private const val DIGEST_BUFFER_BYTES = 64 * 1024

private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }

/** Borrowed database identity and write target captured for one write intent. */
data class DatabaseSessionWriteSnapshot(
    val database: KdbxDatabase,
    val pathIdentifier: String?,
    val file: File?
)
