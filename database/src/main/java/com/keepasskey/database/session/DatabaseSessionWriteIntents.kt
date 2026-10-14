package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import java.io.File
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

    /** Commit a database Meta candidate only after the real writer confirms persistence. */
    suspend fun commitDatabaseMeta(
        expectedDatabase: KdbxDatabase,
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxResult<Unit> = persistence.commitDatabaseMeta(expectedDatabase, transform)
}

/** Borrowed database identity and write target captured for one write intent. */
data class DatabaseSessionWriteSnapshot(
    val database: KdbxDatabase,
    val pathIdentifier: String?,
    val file: File?
)
