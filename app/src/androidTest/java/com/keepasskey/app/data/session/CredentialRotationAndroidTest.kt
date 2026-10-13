package com.keepasskey.app.data.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.database.session.AtomicFileWriter
import com.keepasskey.database.session.CredentialRotationCancellationException
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.SessionWriteReceipt
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** 真实 Android 文件、KDF 与取消交付：读取磁盘和缓存，不能只断言 Job.isCancelled。 */
@RunWith(AndroidJUnit4::class)
class CredentialRotationAndroidTest {
    @Test fun committedCancellationKeepsNewDiskAndCache() = runBlocking<Unit> {
        exercise(committed = true)
    }
    @Test fun cancellationBeforeWriteKeepsOldDiskAndCache() = runBlocking<Unit> {
        exercise(committed = false)
    }

    private suspend fun exercise(committed: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.filesDir.toPath(), "rotation-").toFile()
        val target = File(directory, "synthetic.kdbx")
        val oldPassword = "Old#Android#Fake".toCharArray()
        val newPassword = "New#Android#Fake".toCharArray()
        val session = DatabaseSession()
        var before: ByteArray? = null
        try {
            val created = session.create(target, "Rotation", oldPassword, false)
            if (created is com.keepasskey.core.result.KdbxResult.Failure) throw AssertionError("安全新建失败 code=${created.code}", created.error)
            assertTrue(created.isSuccess)
            before = target.readBytes()
            session.close()
            assertTrue(session.openStream(
                pathIdentifier = target.absolutePath,
                associatedFile = target,
                inputStreamProvider = { target.inputStream() },
                saveWriter = { bytes -> withContext(Dispatchers.IO) { AtomicFileWriter.writeAtomic(target) { it.write(bytes) } } },
                trackedSaveWriter = { bytes, receipt ->
                    withContext(Dispatchers.IO) {
                        if (committed) {
                            receipt.writing()
                            AtomicFileWriter.writeAtomic(target) { it.write(bytes) }
                            receipt.committed()
                            currentCoroutineContext().cancel(CancellationException("真实替换后交付取消"))
                            currentCoroutineContext().ensureActive()
                        } else throw CancellationException("实际写入前取消")
                    }
                },
                passwordChars = oldPassword
            ).isSuccess)
            var cancellation: CancellationException? = null
            coroutineScope {
                launch {
                    try { session.changeCredentials(newPassword) }
                    catch (error: CancellationException) { cancellation = error }
                }.join()
            }
            assertNotNull(cancellation)
            val expected = if (committed) newPassword else oldPassword
            val rejected = if (committed) oldPassword else newPassword
            val snapshot = session.passwordSnapshot()!!
            try { assertArrayEquals(expected, snapshot) } finally { snapshot.fill('0') }
            if (committed) {
                assertEquals(SessionWriteReceipt.Status.COMMITTED,
                    (cancellation as CredentialRotationCancellationException).writeStatus)
                assertFalse(File(directory, "synthetic.kdbx.bak").exists())
            } else assertArrayEquals(before, target.readBytes())
            val loaded = KdbxFile.load(target.inputStream(), expected)
            try { assertEquals("Rotation", loaded.databaseName) } finally { loaded.clearSensitiveData() }
            assertThrows(KdbxInvalidCredentialsException::class.java) {
                target.inputStream().use { KdbxFile.load(it, rejected).clearSensitiveData() }
            }
            assertTrue(session.save().isSuccess)
        } finally {
            session.close()
            oldPassword.fill('0')
            newPassword.fill('0')
            before?.fill(0)
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
