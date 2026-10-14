package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.SessionWriteReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** SAF rwt 故障和取消交付以提供方实际字节、会话状态与请求回执为准。 */
@RunWith(AndroidJUnit4::class)
class CredentialRotationSafAndroidTest {
    @Test fun openFailLeavesCredentialWriteUncertain() = runBlocking { uncertainWrite("OPEN_FAIL") }
    @Test fun zeroWriteLeavesCredentialWriteUncertain() = runBlocking { uncertainWrite("ZERO_WRITE") }
    @Test fun partialWriteLeavesCredentialWriteUncertain() = runBlocking { uncertainWrite("PARTIAL_WRITE") }
    @Test fun fsyncFailLeavesCredentialWriteUncertain() = runBlocking { uncertainWrite("FSYNC_FAIL") }

    @Test fun cancellationBeforeWriterKeepsOldDocumentAndAllowsSave() = runBlocking {
        withFixture("cancel-before") { client, uri, session, oldPassword, newPassword ->
            val before = requireNotNull(client.state(uri).getByteArray("bytes"))
            try {
                val priorTicket = requireNotNull(session.captureCredentialRequest().ticket)
                session.close()
                assertTrue(openForRotation(client.targetContext, uri, session, oldPassword, cancelBeforeWrite = true).isSuccess)
                val request = session.captureCredentialRequest()
                val oldTicket = requireNotNull(request.ticket)
                assertNotSame("同路径重开必须创建新的会话身份", priorTicket, oldTicket)
                var cancellation: CancellationException? = null
                try { session.withCredentialRequest(request) { session.changeCredentials(newPassword) } }
                catch (error: CancellationException) { cancellation = error }
                assertNotNull("writer 前取消必须传播", cancellation)
                assertEquals(SessionWriteReceipt.Status.NOT_STARTED, request.writeStatus)
                assertNull(request.committedTicket)
                assertSame(oldTicket, session.captureCredentialRequest().ticket)
                assertEquals(oldTicket.credentialGeneration, request.ticket?.credentialGeneration)
                assertArrayEquals(before, client.state(uri).getByteArray("bytes"))
                val cached = requireNotNull(session.passwordSnapshot())
                try { assertArrayEquals(oldPassword, cached) } finally { cached.fill('\u0000') }
                assertTrue("取消后普通保存仍可完成", session.save().isSuccess)
                assertCanOpen(uri, client.targetContext, oldPassword, "593-cancel-before")
                assertCannotOpen(uri, client.targetContext, newPassword)
                assertSame(oldTicket, session.captureCredentialRequest().ticket)
                assertEquals(oldTicket.credentialGeneration, session.captureCredentialRequest().ticket?.credentialGeneration)
            } finally { before.fill(0) }
        }
    }

    @Test fun cancellationAfterCommittedWriterKeepsNewDocumentAndTicket() = runBlocking {
        withFixture("cancel-after") { client, uri, session, oldPassword, newPassword ->
            val priorTicket = requireNotNull(session.captureCredentialRequest().ticket)
            session.close()
            assertTrue(openForRotation(client.targetContext, uri, session, oldPassword).isSuccess)
            val request = session.captureCredentialRequest()
            val oldTicket = requireNotNull(request.ticket)
            assertNotSame("同路径重开必须创建新的会话身份", priorTicket, oldTicket)
            var cancellation: CancellationException? = null
            try { session.withCredentialRequest(request) { session.changeCredentials(newPassword) } }
            catch (error: CancellationException) { cancellation = error }
            assertNotNull("提交后取消仍须传播", cancellation)
            assertEquals(SessionWriteReceipt.Status.COMMITTED, request.writeStatus)
            val committed = requireNotNull(request.committedTicket)
            assertEquals(oldTicket.pathIdentifier, committed.pathIdentifier)
            assertEquals(oldTicket.credentialGeneration + 1, committed.credentialGeneration)
            assertSame(committed, session.captureCredentialRequest().ticket)
            val cached = requireNotNull(session.passwordSnapshot())
            try { assertArrayEquals(newPassword, cached) } finally { cached.fill('\u0000') }
            val disk = requireNotNull(client.state(uri).getByteArray("bytes"))
            try { assertCanOpenBytes(disk, newPassword) } finally { disk.fill(0) }
            assertCannotOpen(uri, client.targetContext, oldPassword)
        }
    }

    private suspend fun uncertainWrite(fault: String) {
        withFixture("uncertain-${fault.lowercase()}") { client, uri, session, oldPassword, newPassword ->
            val beforeState = client.state(uri)
            val before = requireNotNull(beforeState.getByteArray("bytes"))
            val writesBefore = beforeState.getInt("writes")
            try {
                val oldTicket = requireNotNull(session.captureCredentialRequest().ticket)
                client.setFault(uri, fault)
                val request = session.captureCredentialRequest()
                val result = session.withCredentialRequest(request) { session.changeCredentials(newPassword) }
                assertTrue(result is KdbxResult.Failure)
                assertEquals(KdbxError.CREDENTIALS_WRITE_UNCERTAIN, (result as KdbxResult.Failure).code)
                assertNull(request.committedTicket)
                assertEquals(SessionWriteReceipt.Status.UNKNOWN, request.writeStatus)
                assertTrue(session.isReadOnly)
                assertTrue(session.credentialWriteUncertain.value)
                assertEquals(oldTicket, session.captureCredentialRequest().ticket)
                val cached = requireNotNull(session.passwordSnapshot())
                try { assertArrayEquals(oldPassword, cached) } finally { cached.fill('\u0000') }
                val afterState = client.state(uri)
                val after = requireNotNull(afterState.getByteArray("bytes"))
                try {
                    when (fault) {
                        "OPEN_FAIL" -> assertArrayEquals(before, after)
                        "ZERO_WRITE" -> assertEquals(0, after.size)
                        "PARTIAL_WRITE" -> assertEquals(32, after.size)
                        "FSYNC_FAIL" -> {
                            assertTrue("fsync failure must follow a full-document write", afterState.getInt("writes") > writesBefore)
                            assertTrue("fsync failure after write must retain encrypted bytes", after.isNotEmpty())
                            assertCanOpen(uri, client.targetContext, newPassword, "593-uncertain-fsync_fail")
                            assertCannotOpen(uri, client.targetContext, oldPassword)
                        }
                    }
                    assertTrue(session.save() is KdbxResult.Failure)
                    assertArrayEquals(after, client.state(uri).getByteArray("bytes"))
                    val retry = session.captureCredentialRequest()
                    val retryResult = session.withCredentialRequest(retry) { session.changeCredentials(oldPassword) }
                    assertTrue(retryResult is KdbxResult.Failure)
                    assertEquals(KdbxError.CREDENTIALS_WRITE_UNCERTAIN, (retryResult as KdbxResult.Failure).code)
                    assertArrayEquals(after, client.state(uri).getByteArray("bytes"))
                } finally { after.fill(0) }
            } finally { before.fill(0) }
        }
    }

    private suspend fun openForRotation(
        context: Context, uri: Uri, session: DatabaseSession, password: CharArray, cancelBeforeWrite: Boolean = false
    ): KdbxResult<Unit> = session.openStream(
        pathIdentifier = uri.toString(),
        inputStreamProvider = { context.contentResolver.openInputStream(uri) ?: error("SAF input unavailable") },
        saveWriter = SafVaultCreation.saveWriter(context, uri, "rotation.kdbx"),
        passwordChars = password,
        trackedSaveWriter = if (cancelBeforeWrite) { _, _ ->
            throw CancellationException("取消发生于写入前")
        } else { bytes, receipt ->
            SafVaultCreation.trackedSaveWriter(context, uri, "rotation.kdbx").invoke(bytes, receipt)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().cancel(CancellationException("真实提交后交付取消"))
                currentCoroutineContext().ensureActive()
            }
        }
    )

    private suspend fun withFixture(
        name: String,
        block: suspend (SafCreationProbeClient, Uri, DatabaseSession, CharArray, CharArray) -> Unit
    ) {
        val client = SafCreationProbeClient()
        val context = client.targetContext
        val uri = client.create("593-$name.kdbx")
        val oldPassword = "Old#593#Password".toCharArray()
        val newPassword = "New#593#Password".toCharArray()
        val session = DatabaseSession()
        try {
            assertTrue(NewVaultDocumentClaims.record(context, uri))
            var registrationCount = 0
            val created = SafVaultCreation.create(
                context, StringsProvider { id, args -> context.getString(id, *args) }, session,
                "Rotation", oldPassword, null, CreateVaultPreset.DEFAULT, uri
            ) { _, path ->
                assertEquals(uri.toString(), path)
                registrationCount++
                KdbxResult.Success(Unit)
            }
            assertTrue("生产 SAF 建库成功", created.isSuccess)
            assertEquals(1, registrationCount)
            assertEquals(uri.toString(), session.currentPathIdentifier)
            block(client, uri, session, oldPassword, newPassword)
        } finally {
            session.close()
            oldPassword.fill('\u0000')
            newPassword.fill('\u0000')
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            if (permission != null) {
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            }
            client.cleanupAndEnd()
        }
    }

    private fun assertCanOpen(uri: Uri, context: Context, password: CharArray, expectedName: String? = null) {
        context.contentResolver.openInputStream(uri)!!.use {
            val database = KdbxFile.load(it, password)
            try { expectedName?.let { expected -> assertEquals(expected, database.databaseName) } }
            finally { database.clearSensitiveData() }
        }
    }

    private fun assertCannotOpen(uri: Uri, context: Context, password: CharArray) {
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            context.contentResolver.openInputStream(uri)!!.use { KdbxFile.load(it, password).clearSensitiveData() }
        }
    }

    private fun assertCanOpenBytes(bytes: ByteArray, password: CharArray) {
        KdbxFile.load(bytes.inputStream(), password).clearSensitiveData()
    }
}
