package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import kotlin.io.path.createTempDirectory

/** 锁屏障覆盖受理、等锁、提交三阶段；票据不依赖树实例或文件路径相等。 */
class SessionCredentialTicketTest {
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

    @Test
    fun `预备后换密再提交因票据失效被拒绝且不覆盖正式文件`() = runBlocking {
        val directory = createTempDirectory("prepared-rotation-").toFile()
        val file = File(directory, "vault.kdbx")
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        val session = DatabaseSession()
        val opener = DatabaseSession()
        var prepared: PreparedSerializedSave? = null
        try {
            assertTrue(session.create(file, "Vault", oldPwd, useArgon2 = false) is KdbxResult.Success)
            val initialTree = session.databaseFlow.value!!

            val prepResult = session.prepareSerializedSave(initialTree)
            assertTrue(prepResult is KdbxResult.Success)
            val prepData = (prepResult as KdbxResult.Success).data
            prepared = prepData
            val prepBytes = prepData.bytes
            assertFalse(prepBytes.all { it == 0.toByte() })
            assertFalse(prepData.isWiped)

            assertTrue(session.changeCredentials(newPwd).isSuccess)
            val treeAfterRotation = session.databaseFlow.value!!
            val bytesAfterRotation = file.readBytes()

            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = initialTree,
                candidate = initialTree,
                prepared = prepData
            )

            assertTrue(commitResult is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (commitResult as KdbxResult.Failure).code)

            assertSame("换密被拒绝后活动树必须保持轮换后的实例", treeAfterRotation, session.databaseFlow.value)
            assertArrayEquals("拒绝提交旧预备后正式文件必须保持轮换后的内容", bytesAfterRotation, file.readBytes())
            assertTrue("预备密文字节必须被全零擦除", prepBytes.all { it == 0.toByte() })
            assertTrue(prepData.isWiped)

            assertTrue(opener.open(file, newPwd) is KdbxResult.Success)
            opener.close()
            assertTrue(opener.open(file, oldPwd) is KdbxResult.Failure)
        } finally {
            prepared?.wipe()
            oldPwd.fill('0')
            newPwd.fill('0')
            session.close()
            opener.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `预备后密钥改绑再提交因票据失效被拒绝且不覆盖正式文件`() = runBlocking {
        val directory = createTempDirectory("prepared-rebind-").toFile()
        val file = File(directory, "rebind.kdbx")
        val password = "Pass#Rebind#2026".toCharArray()
        val keyFileData = ByteArray(64) { (it + 1).toByte() }
        val session = DatabaseSession()
        val opener = DatabaseSession()
        var prepared: PreparedSerializedSave? = null
        try {
            assertTrue(session.create(file, "RebindVault", password, useArgon2 = false) is KdbxResult.Success)
            val initialTree = session.databaseFlow.value!!

            val prepResult = session.prepareSerializedSave(initialTree)
            assertTrue(prepResult is KdbxResult.Success)
            val prepData = (prepResult as KdbxResult.Success).data
            prepared = prepData
            val prepBytes = prepData.bytes

            assertTrue(session.changeCredentials(password, keyFileData).isSuccess)
            val treeAfterRotation = session.databaseFlow.value!!
            val bytesAfterRotation = file.readBytes()

            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = initialTree,
                candidate = initialTree,
                prepared = prepData
            )

            assertTrue(commitResult is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (commitResult as KdbxResult.Failure).code)

            assertSame("改绑被拒绝后活动树必须保持改绑后的实例", treeAfterRotation, session.databaseFlow.value)
            assertArrayEquals("拒绝提交旧预备后正式文件必须保持改绑后的内容", bytesAfterRotation, file.readBytes())
            assertTrue("预备密文字节必须被全零擦除", prepBytes.all { it == 0.toByte() })
            assertTrue(prepData.isWiped)

            assertTrue(opener.open(file, password, keyFileData) is KdbxResult.Success)
            opener.close()
            assertTrue(opener.open(file, password, null) is KdbxResult.Failure)
        } finally {
            prepared?.wipe()
            password.fill('0')
            keyFileData.fill(0)
            session.close()
            opener.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `预备后仅换密钥文件改绑再提交因票据失效被拒绝且不覆盖正式文件`() = runBlocking {
        val directory = createTempDirectory("prepared-keyfile-only-").toFile()
        val file = File(directory, "keyonly.kdbx")
        val password = "Pass#KeyOnly#2026".toCharArray()
        val oldKey = ByteArray(64) { (it + 1).toByte() }
        val newKey = ByteArray(64) { (it + 9).toByte() }
        val session = DatabaseSession()
        val opener = DatabaseSession()
        var prepared: PreparedSerializedSave? = null
        try {
            assertTrue(
                session.create(file, "KeyOnlyVault", password, useArgon2 = false, keyFileData = oldKey) is KdbxResult.Success
            )
            val initialTree = session.databaseFlow.value!!

            val prepResult = session.prepareSerializedSave(initialTree)
            assertTrue(prepResult is KdbxResult.Success)
            val prepData = (prepResult as KdbxResult.Success).data
            prepared = prepData
            val prepBytes = prepData.bytes

            assertTrue(session.changeKeyFileOnly(newKey).isSuccess)
            val treeAfterRotation = session.databaseFlow.value!!
            val bytesAfterRotation = file.readBytes()

            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = initialTree,
                candidate = initialTree,
                prepared = prepData
            )

            assertTrue(commitResult is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (commitResult as KdbxResult.Failure).code)

            assertSame("仅换密钥文件被拒绝后活动树必须保持原实例", treeAfterRotation, session.databaseFlow.value)
            assertArrayEquals("拒绝提交旧预备后正式文件必须保持新密钥文件内容", bytesAfterRotation, file.readBytes())
            assertTrue("预备密文字节必须被全零擦除", prepBytes.all { it == 0.toByte() })
            assertTrue(prepData.isWiped)

            assertTrue(opener.open(file, password, newKey) is KdbxResult.Success)
            opener.close()
            assertTrue(opener.open(file, password, oldKey) is KdbxResult.Failure)
        } finally {
            prepared?.wipe()
            password.fill('0')
            oldKey.fill(0)
            newKey.fill(0)
            session.close()
            opener.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `预备后会话锁定清零再提交被拒绝且不写正式文件`() = runBlocking {
        val directory = createTempDirectory("prepared-lock-").toFile()
        val file = File(directory, "locked.kdbx")
        val password = "Pass#Lock#2026".toCharArray()
        val session = DatabaseSession()
        var prepared: PreparedSerializedSave? = null
        try {
            assertTrue(session.create(file, "LockVault", password, useArgon2 = false) is KdbxResult.Success)
            val tree = session.databaseFlow.value!!
            val prepResult = session.prepareSerializedSave(tree)
            assertTrue(prepResult is KdbxResult.Success)
            val prepData = (prepResult as KdbxResult.Success).data
            prepared = prepData
            val prepBytes = prepData.bytes

            val bytesBefore = file.readBytes()
            session.lock()

            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = tree,
                candidate = tree,
                prepared = prepData
            )

            assertTrue(commitResult is KdbxResult.Failure)
            assertArrayEquals("锁定后拒绝提交不得修改文件", bytesBefore, file.readBytes())
            assertTrue("预备密文字节必须被全零擦除", prepBytes.all { it == 0.toByte() })
            assertTrue(prepData.isWiped)
        } finally {
            prepared?.wipe()
            password.fill('0')
            session.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `预备后切换打开另一数据库再提交被拒绝且不污染新库`() = runBlocking {
        val directory = createTempDirectory("prepared-switch-").toFile()
        val fileA = File(directory, "vaultA.kdbx")
        val fileB = File(directory, "vaultB.kdbx")
        val pwdA = "Pass#VaultA#2026".toCharArray()
        val pwdB = "Pass#VaultB#2026".toCharArray()
        val session = DatabaseSession()
        val creatorB = DatabaseSession()
        val openerB = DatabaseSession()
        var preparedA: PreparedSerializedSave? = null
        try {
            assertTrue(session.create(fileA, "VaultA", pwdA, useArgon2 = false) is KdbxResult.Success)
            assertTrue(creatorB.create(fileB, "VaultB", pwdB, useArgon2 = false) is KdbxResult.Success)
            creatorB.close()

            val treeA = session.databaseFlow.value!!
            val prepResultA = session.prepareSerializedSave(treeA)
            assertTrue(prepResultA is KdbxResult.Success)
            val prepDataA = (prepResultA as KdbxResult.Success).data
            preparedA = prepDataA
            val prepBytesA = prepDataA.bytes

            assertTrue(session.open(fileB, pwdB) is KdbxResult.Success)
            val treeB = session.databaseFlow.value!!
            val bytesB = fileB.readBytes()

            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = treeB,
                candidate = treeB,
                prepared = prepDataA
            )

            assertTrue(commitResult is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (commitResult as KdbxResult.Failure).code)
            assertSame("换库拒绝旧提交后活动树保持库B实例", treeB, session.databaseFlow.value)
            assertArrayEquals("新库B文件字节未被修改", bytesB, fileB.readBytes())
            assertTrue("旧预备密文字节必须被全零擦除", prepBytesA.all { it == 0.toByte() })
            assertTrue(prepDataA.isWiped)

            assertTrue("新库B必须保持可用", openerB.open(fileB, pwdB) is KdbxResult.Success)
        } finally {
            preparedA?.wipe()
            pwdA.fill('0')
            pwdB.fill('0')
            session.close()
            creatorB.close()
            openerB.close()
            directory.deleteRecursively()
        }
    }
}
