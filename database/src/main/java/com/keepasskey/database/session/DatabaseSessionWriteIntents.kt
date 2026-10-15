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
     * Hash the currently committed private file only while the same database revision is still active.
     * The shared session mutex prevents an application save or database replacement while the file is
     * read. Callers use the returned digest as a revision-bound receipt, never as a standalone proof.
     */
    suspend fun digestStableFileFor(expectedDatabase: KdbxDatabase): StableDatabaseFileDigest? = mutex.withLock {
        if (core.database.value !== expectedDatabase ||
            core.state.value != DatabaseSession.SessionState.OPENED
        ) return@withLock null

        val file = core.activeFile ?: return@withLock null
        val pathIdentifier = core.activePathIdentifier ?: return@withLock null
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
