package com.keepasskey.app.security

import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.PreparedSerializedSave
import com.keepasskey.database.session.SessionEncryptedSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 草稿载荷慢写在会话锁外，索引仅在原会话票据仍有效时提交。 */
internal class VaultCrashDrafts(
    private val session: DatabaseSession,
    private val store: VaultRecoveryStore
) {
    suspend fun capture(): KdbxResult<VaultRecoveryStore.Entry> =
        preserve(session.captureEncryptedSnapshot())

    suspend fun capturePrepared(expected: KdbxDatabase, prepared: PreparedSerializedSave): KdbxResult<VaultRecoveryStore.Entry> =
        preserve(session.copyPreparedSnapshot(expected, prepared))

    private suspend fun preserve(result: KdbxResult<SessionEncryptedSnapshot>): KdbxResult<VaultRecoveryStore.Entry> {
        if (result is KdbxResult.Failure) return result
        val snapshot = result.getOrThrow()
        try {
            val staged = snapshot.borrowEncryptedBytes { bytes ->
                store.stage(snapshot.pathIdentifier, snapshot.vaultName, snapshot.pathIdentifier,
                    bytes, VaultRecoveryStore.Purpose.CRASH_DRAFT)
            } ?: return failed()
            try {
                var committed = false
                withContext(Dispatchers.IO) {
                    session.useCredentialTicket(snapshot.ticket) { committed = store.commit(staged) }
                }
                return if (committed) KdbxResult.Success(staged.entry) else failed()
            } finally { staged.close() }
        } finally { snapshot.wipe() }
    }

    suspend fun saved(entry: VaultRecoveryStore.Entry) = withContext(Dispatchers.IO) { store.delete(entry) }

    private fun failed() = KdbxResult.Failure(
        IllegalStateException("未能保全加密崩溃草稿，保存已停止"), code = KdbxError.SAVE_FAILED
    )
}
