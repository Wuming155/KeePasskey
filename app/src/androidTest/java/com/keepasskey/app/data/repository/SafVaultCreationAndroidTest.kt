package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 594 SAF 写入失败矩阵：每个故障都必须保留活动会话、原库与备份。 */
@RunWith(AndroidJUnit4::class)
class SafVaultCreationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var client: SafCreationProbeClient
    private lateinit var targetContext: Context
    private lateinit var oldFile: File
    private lateinit var oldBackup: File
    private lateinit var oldDirectory: File
    private lateinit var session: DatabaseSession
    private lateinit var oldPassword: CharArray
    private lateinit var oldTree: com.keepasskey.database.file.KdbxDatabase
    private lateinit var oldPath: String
    private lateinit var oldFileHash: ByteArray
    private lateinit var oldBackupHash: ByteArray
    private val grantedUris = mutableListOf<Uri>()

    @Before
    fun setUp() = runBlocking {
        client = SafCreationProbeClient()
        targetContext = client.targetContext
        oldDirectory = File(targetContext.filesDir, "saf594-${UUID.randomUUID()}").apply {
            check(mkdir())
        }
        oldFile = File(oldDirectory, "existing.kdbx")
        oldBackup = File(oldDirectory, "existing.kdbx.bak")
        oldPassword = charArrayOf('O', 'l', 'd', '#', '5', '9', '4', '!', 'K', 'e', 'y')
        session = DatabaseSession()
        val opened = session.create(oldFile, "Existing", oldPassword, useArgon2 = false)
        assertTrue("旧会话合成库创建失败", opened.isSuccess)
        oldTree = requireNotNull(session.databaseFlow.value)
        oldPath = requireNotNull(session.currentPathIdentifier)
        oldFileHash = sha256(oldFile.readBytes())
        oldBackup.writeBytes(oldFile.readBytes())
        check(oldBackup.length() > 0L)
        oldBackupHash = sha256(oldBackup.readBytes())
    }

    @After
    fun tearDown() {
        if (::session.isInitialized) runBlocking { session.close() }
        if (::oldPassword.isInitialized) oldPassword.fill('\u0000')
        grantedUris.forEach { uri ->
            val persisted = targetContext.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri == uri }
            if (persisted != null) {
                var flags = 0
                if (persisted.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (persisted.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                targetContext.contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
        if (::client.isInitialized) client.cleanupAndEnd()
        if (::oldFile.isInitialized) oldFile.delete()
        if (::oldBackup.isInitialized) oldBackup.delete()
        if (::oldDirectory.isInitialized) oldDirectory.delete()
    }

    @Test
    fun missingPersistablePermissionRejectsCreationAndDeletesOnlyEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("no-persist", persist = false)
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertEquals(0, state.getInt("writes"))
        assertEquals(1, state.getInt("deletes"))
        assertFalse(state.getBoolean("exists"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun missingDocumentNameRejectsCreationAndDeletesOnlyEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("name-missing", fault = "NAME_MISSING")
        assertFailedSafCreate(uri)
        assertDeletedEmpty(uri)
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun seededThreeByteDocumentIsPreserved() = runBlocking {
        val uri = newClaimedDocument("seeded")
        client.seedThreeBytes(uri)
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertEquals(3L, state.getLong("size"))
        assertArrayEquals(byteArrayOf(41, 42, 43), state.getByteArray("bytes"))
        assertEquals(0, state.getInt("deletes"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun unknownStatSizePreservesOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("stat-unknown", fault = "STAT_UNKNOWN")
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertTrue(state.getBoolean("exists"))
        assertEquals(0L, state.getLong("size"))
        assertEquals(0, state.getInt("deletes"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun openFailureDeletesOnlyEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("open-fail", fault = "OPEN_FAIL")
        assertFailedSafCreate(uri)
        assertDeletedEmpty(uri)
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun zeroWriteDeletesOnlyEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("zero-write", fault = "ZERO_WRITE")
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertTrue(state.getInt("writes") > 0)
        assertEquals(0L, state.getLong("size"))
        assertEquals(1, state.getInt("deletes"))
        assertFalse(state.getBoolean("exists"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun partialWritePreservesNonEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("partial-write", fault = "PARTIAL_WRITE")
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertTrue(state.getInt("writes") > 0)
        assertTrue(state.getLong("size") > 0L)
        assertEquals(0, state.getInt("deletes"))
        assertTrue(state.getBoolean("exists"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun fsyncFailurePreservesNonEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("fsync-fail", fault = "FSYNC_FAIL")
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertTrue(state.getInt("writes") > 0)
        assertTrue(state.getLong("size") > 0L)
        assertEquals(0, state.getInt("deletes"))
        assertTrue(state.getBoolean("exists"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun readBackMismatchPreservesNonEmptyOwnedDocument() = runBlocking {
        val uri = newClaimedDocument("read-mismatch", fault = "READ_MISMATCH")
        assertFailedSafCreate(uri)
        val state = client.state(uri)
        assertTrue(state.getLong("size") > 0L)
        assertEquals(0, state.getInt("deletes"))
        assertTrue(state.getBoolean("exists"))
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun deleteFailureUsesClaimDiscardAndKeepsEmptyDocument() = runBlocking {
        val uri = newClaimedDocument("delete-fail", fault = "DELETE_FAIL")
        NewVaultDocumentClaims.discard(targetContext, uri.toString())
        val state = client.state(uri)
        assertTrue(state.getBoolean("exists"))
        assertEquals(0L, state.getLong("size"))
        assertEquals(1, state.getInt("deletes"))
        assertOldSessionUnchanged()
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun claimCanBeConsumedOnceAndDiscardIsIdempotent() = runBlocking {
        val consumed = client.create("saf594-consume-${UUID.randomUUID()}.kdbx")
        assertTrue(NewVaultDocumentClaims.record(targetContext, consumed))
        grantedUris += consumed
        assertTrue(NewVaultDocumentClaims.consume(consumed))
        assertFalse(NewVaultDocumentClaims.consume(consumed))
        NewVaultDocumentClaims.discard(targetContext, consumed.toString())
        assertEquals(0, client.state(consumed).getInt("deletes"))

        val discarded = client.create("saf594-discard-${UUID.randomUUID()}.kdbx")
        assertTrue(NewVaultDocumentClaims.record(targetContext, discarded))
        grantedUris += discarded
        NewVaultDocumentClaims.discard(targetContext, discarded.toString())
        NewVaultDocumentClaims.discard(targetContext, discarded.toString())
        val state = client.state(discarded)
        assertFalse(state.getBoolean("exists"))
        assertEquals(1, state.getInt("deletes"))
        assertOldSessionUnchanged()
        assertOldSessionStillSavesAndReopens()
    }

    @Test
    fun unclaimedDocumentCannotBeCreatedThroughSafFlow() = runBlocking {
        val uri = client.create("saf594-unclaimed-${UUID.randomUUID()}.kdbx")
        grantedUris += uri
        val result = createSaf(uri)
        assertTrue(result is KdbxResult.Failure)
        assertEquals(KdbxError.CREATE_COLLISION, (result as KdbxResult.Failure).code)
        val state = client.state(uri)
        assertTrue(state.getBoolean("exists"))
        assertEquals(0L, state.getLong("size"))
        assertEquals(0, state.getInt("writes"))
        assertEquals(0, state.getInt("deletes"))
        assertOldSessionUnchanged()
        assertOldSessionStillSavesAndReopens()
    }

    private fun newClaimedDocument(
        label: String,
        persist: Boolean = true,
        fault: String? = null
    ): Uri {
        val uri = client.create("saf594-$label-${UUID.randomUUID()}.kdbx", persist)
        grantedUris += uri
        if (fault != null) client.setFault(uri, fault)
        assertTrue("本测试新建 URI 必须能领取", NewVaultDocumentClaims.record(targetContext, uri))
        return uri
    }

    private suspend fun assertFailedSafCreate(uri: Uri) {
        val result = createSaf(uri)
        assertTrue("故障注入的 SAF 建库必须失败", result is KdbxResult.Failure)
        assertEquals(0, registrationCount)
        assertOldSessionUnchanged()
    }

    private var registrationCount = 0

    private suspend fun createSaf(uri: Uri): KdbxResult<Unit> {
        val password = charArrayOf('N', 'e', 'w', '#', '5', '9', '4', '!', 'K', 'e', 'y')
        return try {
            SafVaultCreation.create(
                context = targetContext,
                strings = StringsProvider { id, args -> targetContext.getString(id, *args) },
                databaseSession = session,
                name = "requested-${UUID.randomUUID()}",
                masterPassword = password,
                keyFileData = null,
                preset = CreateVaultPreset.DEFAULT,
                targetUri = uri
            ) { _, _ ->
                registrationCount++
                KdbxResult.Success(Unit)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun assertDeletedEmpty(uri: Uri) {
        val state = client.state(uri)
        assertTrue(state.getBoolean("exists").not())
        assertEquals(0L, state.getLong("size"))
        assertEquals(1, state.getInt("deletes"))
    }

    private fun assertOldSessionUnchanged() {
        assertSame(oldTree, session.databaseFlow.value)
        assertEquals(oldPath, session.currentPathIdentifier)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        session.useCredentials { password, _ -> assertArrayEquals(oldPassword, password) }
        assertArrayEquals(oldFileHash, sha256(oldFile.readBytes()))
        assertArrayEquals(oldBackupHash, sha256(oldBackup.readBytes()))
        assertEquals(0, registrationCount)
    }

    private suspend fun assertOldSessionStillSavesAndReopens() {
        assertOldSessionUnchanged()
        assertTrue("原会话仍须使用旧凭据保存", session.save().isSuccess)
        val reopened = DatabaseSession()
        try {
            assertTrue("旧口令不能重新打开原库", reopened.open(oldFile, oldPassword).isSuccess)
            assertEquals("Existing", reopened.databaseFlow.value?.databaseName)
        } finally {
            reopened.close()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
