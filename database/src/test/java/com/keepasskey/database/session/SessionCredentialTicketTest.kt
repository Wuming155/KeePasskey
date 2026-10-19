package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.rules.TemporaryFolder

/** 锁屏障覆盖受理、等锁、提交三阶段；票据不依赖树实例或文件路径相等。 */
class SessionCredentialTicketTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class Fixture : Closeable {
        val core = SessionCore()
        val mutex = Mutex()
        val cache = SessionCredentialCache()
        val password = "Ticket#Old#Fake".toCharArray()
        val candidate = "Ticket#New#Fake".toCharArray()
        val writer = SessionFileWriter { false }
        var writes = 0
        val persistence = SessionPersistence(mutex, core, cache, writer)
        init {
            core.establishSessionTicket("synthetic-A.kdbx")
            core.database.value = KdbxDatabase(KdbxHeader.createDefault(useArgon2 = false),
                rootGroup = KdbxGroup(name = "Ticket"))
            cache.cachePassword(password)
            core.saveWriter = { writes++ }
        }
        override fun close() {
            core.database.value?.clearSensitiveData()
            cache.clear()
            password.fill('0')
            candidate.fill('0')
        }
    }

    @Test fun `排队等待锁期间换库拒绝旧受理且无写入`() = runBlocking<Unit> {
        Fixture().use { f -> rejectAfterReplacement(f, "synthetic-B.kdbx") }
    }

    @Test fun `同路径重新打开仍拒绝旧受理且无写入`() = runBlocking<Unit> {
        Fixture().use { f -> rejectAfterReplacement(f, "synthetic-A.kdbx") }
    }

    @Test fun `另一凭据代次推进后拒绝排队请求`() = runBlocking<Unit> {
        Fixture().use { f ->
            val request = SessionCredentialRequest(f.core.credentialTicket)
            f.mutex.lock()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(request) { f.persistence.changeCredentials(f.candidate, null) }
            }
            f.core.advanceCredentialTicket()
            f.mutex.unlock()
            job.join()
            assertEquals(0, f.writes)
            assertNull(request.committedTicket)
            assertArrayEquals(f.password, f.cache.currentPassword())
        }
    }

    @Test fun `同一请求第二次轮换被拒绝`() = runBlocking<Unit> {
        Fixture().use { f ->
            val request = SessionCredentialRequest(f.core.credentialTicket)
            f.core.trackedSaveWriter = { _, receipt -> f.writes++; receipt.committed() }
            withContext(request) { f.persistence.changeCredentials(f.candidate, null) }
            val second = withContext(request) { f.persistence.changeCredentials(f.password, null) }
            assertTrue(second is KdbxResult.Failure)
            assertEquals(1, f.writes)
        }
    }

    @Test fun `相同请求的纯上下文嵌套可复用`() = runBlocking<Unit> {
        val session = DatabaseSession()
        val request = session.captureCredentialRequest()
        session.withCredentialRequest(request) {
            session.withCredentialRequest(request) {
                assertSame(request, currentCoroutineContext()[SessionCredentialRequest])
            }
        }
    }

    @Test fun `旧批次票据在换库后于条目分组删除与保存边界均被拒绝`() = runBlocking<Unit> {
        val session = openedSession("ticket-target-A")
        val oldRequest = session.captureCredentialRequest()
        val oldEntry = KdbxEntry(id = KdbxUuid.random())
        val oldGroup = KdbxGroup(name = "旧批次分组")

        session.lock()
        assertTrue(session.create(
            File(tempFolder.root, "ticket-target-B-${System.nanoTime()}.kdbx"),
            "Target B", "TicketTarget#Fake".toCharArray(), useArgon2 = false
        ).isSuccess)

        session.withCredentialRequest(oldRequest) {
            assertSessionTicketMismatch { session.saveEntry(oldEntry) }
            assertSessionTicketMismatch { session.saveGroup(oldGroup) }
            assertSessionTicketMismatch { session.deleteEntry(oldEntry.id) }
            assertTrue("过期票据不得序列化或保存后来会话", session.save().isFailure)
        }

        assertTrue("换入库B后不得出现旧批次条目", session.databaseFlow.value!!.rootGroup.allEntries().isEmpty())
        assertTrue("换入库B后不得出现旧批次分组", session.databaseFlow.value!!.rootGroup.allGroups().none { it.name == oldGroup.name })
        session.close()
    }

    @Test fun `同会话批次票据允许正常条目分组变更与保存`() = runBlocking<Unit> {
        val session = openedSession("ticket-target-positive")
        val request = session.captureCredentialRequest()
        val entry = KdbxEntry(id = KdbxUuid.random())
        val group = KdbxGroup(name = "同会话导入分组")

        session.withCredentialRequest(request) {
            session.saveGroup(group)
            session.saveEntry(entry)
            assertTrue("当前票据应允许保存", session.save().isSuccess)
        }

        assertTrue(session.databaseFlow.value!!.rootGroup.allEntries().any { it.id == entry.id })
        assertTrue(session.databaseFlow.value!!.rootGroup.allGroups().any { it.name == group.name })
        session.close()
    }

    private suspend fun assertSessionTicketMismatch(block: suspend () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(
            "会话切换后的挂起写入必须拒绝过期票据，而不是丢失协程上下文",
            thrown is SessionCredentialTicketMismatchException
        )
    }

    private suspend fun openedSession(name: String): DatabaseSession {
        val session = DatabaseSession()
        val result = session.create(
            File(tempFolder.root, "$name-${System.nanoTime()}.kdbx"),
            name,
            "SessionTicket#Fake".toCharArray(),
            useArgon2 = false
        )
        assertTrue("测试库必须成功创建", result.isSuccess)
        return session
    }

    private suspend fun rejectAfterReplacement(f: Fixture, path: String) = coroutineScope {
        val request = SessionCredentialRequest(f.core.credentialTicket)
        var result: KdbxResult<Unit>? = null
        f.mutex.lock()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result = withContext(request) { f.persistence.changeCredentials(f.candidate, null) }
        }
        assertFalse(job.isCompleted)
        f.core.invalidateSessionTicket()
        f.core.establishSessionTicket(path)
        f.mutex.unlock()
        job.join()
        assertTrue(result is KdbxResult.Failure)
        assertEquals(0, f.writes)
        assertArrayEquals(f.password, f.cache.currentPassword())
        assertNull(request.committedTicket)
    }

    @Test fun `无显式请求的直接调用也在等锁前固定票据`() = runBlocking<Unit> {
        Fixture().use { f ->
            var result: KdbxResult<Unit>? = null
            f.mutex.lock()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                result = f.persistence.changeKeyFileOnly(byteArrayOf(1, 2, 3))
            }
            f.core.advanceCredentialTicket()
            f.mutex.unlock()
            job.join()
            assertTrue(result is KdbxResult.Failure)
            assertEquals(0, f.writes)
            assertArrayEquals(f.password, f.cache.currentPassword())
        }
    }

    @Test fun `已提交请求独有新代次而过期请求不能借用其提交事实`() = runBlocking<Unit> {
        Fixture().use { f ->
            val original = f.core.credentialTicket!!
            val committed = SessionCredentialRequest(original)
            val stale = SessionCredentialRequest(original)
            val rotation = SessionCredentialRotation(f.core, f.cache, f.writer) { _, _, _ -> byteArrayOf(9) }
            f.core.trackedSaveWriter = { _, receipt -> receipt.committed() }
            assertTrue(withContext(committed) { rotation.execute(f.candidate, null) }.isSuccess)
            assertNotSame(original, committed.committedTicket)
            assertSame(f.core.credentialTicket, committed.committedTicket)
            assertEquals(original.credentialGeneration + 1, committed.committedTicket!!.credentialGeneration)
            assertSame(original.sessionIdentity, committed.committedTicket!!.sessionIdentity)
            assertTrue(withContext(stale) { f.persistence.changeCredentials(f.password, null) }.isFailure)
            assertNull(stale.committedTicket)
            assertArrayEquals(f.candidate, f.cache.currentPassword())
        }
    }

    @Test fun `实际提交后取消仍交付本请求提交票据`() = runBlocking<Unit> {
        Fixture().use { f ->
            val request = SessionCredentialRequest(f.core.credentialTicket)
            val rotation = SessionCredentialRotation(f.core, f.cache, f.writer) { _, _, _ -> byteArrayOf(9) }
            f.core.trackedSaveWriter = { _, receipt ->
                receipt.committed()
                currentCoroutineContext().cancel()
                currentCoroutineContext().ensureActive()
            }
            var cancellation: CancellationException? = null
            val job = launch {
                try { withContext(request) { rotation.execute(f.candidate, null) } }
                catch (error: CancellationException) { cancellation = error }
            }
            job.join()
            assertNotNull(cancellation)
            assertEquals(SessionWriteReceipt.Status.COMMITTED, request.writeStatus)
            assertSame(f.core.credentialTicket, request.committedTicket)
            assertNotNull(request.committedTicket)
            assertEquals(1, request.committedTicket!!.credentialGeneration)
            assertArrayEquals(f.candidate, f.cache.currentPassword())
        }
    }

    @Test fun `锁定关闭使票据失效且受控收尾不得触碰后来会话`() = runBlocking<Unit> {
        val session = DatabaseSession()
        val db = KdbxDatabase(KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = "First"))
        session.setDatabaseForTesting(db)
        val first = session.captureCredentialRequest().ticket!!
        session.lock()
        assertNull(session.captureCredentialRequest().ticket)
        session.setDatabaseForTesting(KdbxDatabase(KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = "Next")))
        var staleCalls = 0
        assertFalse(session.useCredentialTicket(first) { staleCalls++ })
        assertEquals(0, staleCalls)
        val next = session.captureCredentialRequest().ticket!!
        assertTrue(session.useCredentialTicket(next) { staleCalls++ })
        assertEquals(1, staleCalls)
        session.close()
        assertFalse(session.isCredentialTicketCurrent(next))
    }

    @Test fun `真实新建与打开成功后发布对应路径票据`() = runBlocking<Unit> {
        val directory = createTempDirectory("session-ticket-").toFile()
        val file = File(directory, "created.kdbx")
        val password = "Create#OpenTicket".toCharArray()
        val creator = DatabaseSession()
        val opener = DatabaseSession()
        try {
            assertTrue(creator.create(file, "Created", password, useArgon2 = false) is KdbxResult.Success)
            val created = creator.captureCredentialRequest().ticket!!
            assertEquals(file.absolutePath, created.pathIdentifier)

            assertTrue(opener.open(file, password.copyOf()) is KdbxResult.Success)
            val opened = opener.captureCredentialRequest().ticket!!
            assertEquals(file.absolutePath, opened.pathIdentifier)
            assertNotSame(created, opened)
        } finally {
            password.fill('0')
            creator.close()
            opener.close()
            directory.deleteRecursively()
        }
    }
}
