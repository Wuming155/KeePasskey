package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** 绕过向导直接调用会话，证明不可覆盖契约无旁路。 */
class DatabaseSessionCreateSafetyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun collisionPreservesOldSessionAndAllFilesWithEitherBackupPreference() = runBlocking<Unit> {
        for (backupEnabled in listOf(false, true)) {
            val pwd = "Fake#CreateSafety".toCharArray()
            val active = File(folder.root, "active-$backupEnabled.kdbx")
            val session = DatabaseSession()
            try {
                assertTrue(session.create(active, "Active", pwd, useArgon2 = false).isSuccess)
                val oldTree = session.databaseFlow.value
                val oldBytes = active.readBytes()
                val target = File(folder.root, "occupied-$backupEnabled.kdbx").apply { writeBytes(byteArrayOf(3, 4)) }
                val bak = File(folder.root, target.name + ".bak").apply { writeBytes(byteArrayOf(5, 6)) }
                session.createBackupBeforeSave = backupEnabled
                val result = session.create(target, "New", pwd, useArgon2 = false)
                assertTrue(result is KdbxResult.Failure)
                assertEquals(KdbxError.CREATE_COLLISION, (result as KdbxResult.Failure).code)
                assertSame(oldTree, session.databaseFlow.value)
                assertEquals(active, session.currentFile)
                assertArrayEquals(oldBytes, active.readBytes())
                assertArrayEquals(byteArrayOf(3, 4), target.readBytes())
                assertArrayEquals(byteArrayOf(5, 6), bak.readBytes())
                assertTrue(session.save().isSuccess)
            } finally { session.close(); pwd.fill('0') }
        }
    }

    @Test fun failedStreamCreationKeepsPreviousSessionAndCredentials() = runBlocking<Unit> {
        val pwd = "Fake#CreateFailure".toCharArray()
        val session = DatabaseSession()
        try {
            val active = File(folder.root, "active.kdbx")
            assertTrue(session.create(active, "Active", pwd, useArgon2 = false).isSuccess)
            val oldTree = session.databaseFlow.value
            val result = session.createStream("content://fake/new", "New", pwd, false, null,
                KdbxConstants.Cipher.AES_256_CBC,
                writeNew = { throw IOException("注入临时写入失败") }, saveWriter = {})
            assertTrue(result.isFailure)
            assertSame(oldTree, session.databaseFlow.value)
            assertEquals(active.absolutePath, session.currentPathIdentifier)
            assertTrue(session.save().isSuccess)
        } finally { session.close(); pwd.fill('0') }
    }

    @Test fun failedStreamWriterSeesOwnedCiphertextWipedAfterReturning() = runBlocking<Unit> {
        val pwd = "Fake#OwnedBytes".toCharArray()
        val session = DatabaseSession()
        var borrowedCiphertext: ByteArray? = null
        try {
            val result = session.createStream("content://fake/new", "New", pwd, false, null,
                KdbxConstants.Cipher.AES_256_CBC,
                writeNew = { bytes -> borrowedCiphertext = bytes; throw IOException("注入交付失败") },
                saveWriter = {})
            assertTrue(result.isFailure)
            val observed = requireNotNull(borrowedCiphertext)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it == 0.toByte() })
            assertNull(session.databaseFlow.value)
        } finally { session.close(); pwd.fill('0') }
    }

    @Test fun successfulNewCreationCanBeReopenedAndOnlyThenReplacesSession() = runBlocking<Unit> {
        val pwd = "Fake#ReopenNew".toCharArray()
        val session = DatabaseSession()
        val reopened = DatabaseSession()
        try {
            val first = File(folder.root, "first.kdbx")
            val second = File(folder.root, "second.kdbx")
            assertTrue(session.create(first, "First", pwd, useArgon2 = false).isSuccess)
            val oldBytes = first.readBytes()
            assertTrue(session.create(second, "Second", pwd, useArgon2 = false).isSuccess)
            assertEquals(second, session.currentFile)
            assertArrayEquals(oldBytes, first.readBytes())
            assertTrue(reopened.open(second, pwd).isSuccess)
            assertEquals("Second", reopened.databaseFlow.value!!.databaseName)
        } finally { reopened.close(); session.close(); pwd.fill('0') }
    }
}
