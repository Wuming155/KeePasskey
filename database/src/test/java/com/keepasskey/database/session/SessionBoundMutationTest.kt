package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.exception.KdbxSessionChangedException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 排队后替换会话，证明检查发生在真实改树/写盘临界区，而非入队前。 */
class SessionBoundMutationTest {
    @Test fun 旧请求排队后换库不能修改后来会话() = runBlocking {
        for (operation in 0..7) {
            val core = SessionCore()
            core.establishSessionTicket("A")
            core.database.value = database("A")
            val request = SessionCredentialRequest(core.credentialTicket)
            val mutex = Mutex(locked = true)
            val mutations = SessionContentMutations(
                mutex, core.database, core.state, { false }, credentialTicket = { core.credentialTicket }
            )
            val persistence = SessionPersistence(mutex, core, SessionCredentialCache(), SessionFileWriter { false })
            var failure: Throwable? = null
            val entry = KdbxEntry()
            val group = KdbxGroup(name = "Imported")
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(request) {
                        when (operation) {
                            0 -> mutations.saveEntry(entry)
                            1 -> mutations.saveGroup(group)
                            2 -> mutations.deleteEntry(entry.id)
                            3 -> mutations.updateEntryById(entry.id) { it }
                            4 -> mutations.updateDatabaseMeta { it.copy(databaseName = "wrong") }
                            5 -> mutations.saveEntryInGroup(entry, group.id)
                            6 -> {
                                val result = persistence.save()
                                assertTrue(result is com.keepasskey.core.result.KdbxResult.Failure)
                                failure = (result as com.keepasskey.core.result.KdbxResult.Failure).error
                            }
                            else -> {
                                val result = persistence.commitDatabaseMeta(core.database.value!!) {
                                    it.copy(databaseName = "wrong")
                                }
                                assertTrue(result is com.keepasskey.core.result.KdbxResult.Failure)
                                failure = (result as com.keepasskey.core.result.KdbxResult.Failure).error
                            }
                        }
                    }
                } catch (error: Throwable) { failure = error }
            }
            assertFalse(job.isCompleted)
            core.invalidateSessionTicket()
            core.establishSessionTicket(if (operation % 2 == 0) "B" else "A")
            val later = database("Later")
            core.database.value = later
            mutex.unlock()
            job.join()
            assertTrue("操作 $operation 必须拒绝旧票据", failure is KdbxSessionChangedException)
            assertSame(later, core.database.value)
        }
    }

    @Test fun 旧请求排队后换密不能提交Meta修改() = runBlocking {
        val core = SessionCore()
        core.establishSessionTicket("A")
        core.database.value = database("A")
        val request = SessionCredentialRequest(core.credentialTicket)
        val mutex = Mutex(locked = true)
        val persistence = SessionPersistence(mutex, core, SessionCredentialCache(), SessionFileWriter { false })
        var failure: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(request) {
                    val result = persistence.commitDatabaseMeta(core.database.value!!) {
                        it.copy(databaseName = "wrong")
                    }
                    assertTrue(result is KdbxResult.Failure)
                    failure = (result as KdbxResult.Failure).error
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        assertFalse(job.isCompleted)
        core.invalidateSessionTicket()
        core.establishSessionTicket("Rotated")
        val rotated = database("Rotated")
        core.database.value = rotated
        mutex.unlock()
        job.join()
        assertTrue("换密后必须咬住 requestguard 拒绝旧票据", failure is KdbxSessionChangedException)
        assertSame("后来活动库不得被旧候选改写", rotated, core.database.value)
    }

    @Test fun 旧请求排队后锁库不能提交Meta修改() = runBlocking {
        val core = SessionCore()
        core.establishSessionTicket("A")
        val initial = database("A")
        core.database.value = initial
        val request = SessionCredentialRequest(core.credentialTicket)
        val mutex = Mutex(locked = true)
        val persistence = SessionPersistence(mutex, core, SessionCredentialCache(), SessionFileWriter { false })
        var failure: Throwable? = null
        var resultCode: String? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(request) {
                    val result = persistence.commitDatabaseMeta(core.database.value!!) {
                        it.copy(databaseName = "wrong")
                    }
                    assertTrue(result is KdbxResult.Failure)
                    val fail = result as KdbxResult.Failure
                    resultCode = fail.code
                    failure = fail.error
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        assertFalse(job.isCompleted)
        core.invalidateSessionTicket()
        core.database.value = null
        core.state.value = DatabaseSession.SessionState.LOCKED
        mutex.unlock()
        job.join()
        // 关键断言：若未咬住 requestguard，则因 database.value == null 会落入 SAVE_NO_DATABASE 分支；
        // 由 requestguard 优先检查则必须上浮 KdbxSessionChangedException 与 SAVE_SESSION_CHANGED。
        assertEquals("必须由 requestguard 返回会话变更码", KdbxError.SAVE_SESSION_CHANGED, resultCode)
        assertTrue("必须由 requestguard 咬住会话变更异常", failure is KdbxSessionChangedException)
        assertNull("锁库后活动库保持为空", core.database.value)
    }

    @Test fun 当前请求跨IO调度仍可修改而空票据拒绝() = runBlocking {
        val core = SessionCore()
        core.establishSessionTicket("A")
        core.database.value = database("A")
        val mutations = SessionContentMutations(
            Mutex(), core.database, core.state, { false }, credentialTicket = { core.credentialTicket }
        )
        val entry = KdbxEntry()
        withContext(SessionCredentialRequest(core.credentialTicket)) {
            withContext(Dispatchers.IO) { mutations.saveEntry(entry) }
        }
        assertEquals(entry.id, core.database.value!!.rootGroup.allEntries().single().id)
        val before = core.database.value
        try {
            withContext(SessionCredentialRequest(null)) { mutations.saveGroup(KdbxGroup(name = "Rejected")) }
            fail("空票据不能成为通配授权")
        } catch (_: KdbxSessionChangedException) { }
        assertSame(before, core.database.value)
    }

    @Test fun `普通写受理在改树与落盘前固定票据`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(database("A"))
        val accepted = session.captureCredentialRequest()
        var observed: SessionCredentialRequest? = null
        session.withAcceptedRequest { request ->
            observed = request
            session.lock()
            session.setDatabaseForTesting(database("B"))
            val later = session.databaseFlow.value
            val mutation = runCatching { session.saveEntry(KdbxEntry()) }.exceptionOrNull()
            assertTrue("受理后换库必须拒绝改树", mutation is KdbxSessionChangedException)
            assertSame("拒绝改树不得触碰后来会话", later, session.databaseFlow.value)
            val saved = session.save()
            assertTrue(saved is KdbxResult.Failure)
            assertEquals(KdbxError.SAVE_SESSION_CHANGED, (saved as KdbxResult.Failure).code)
        }
        // 续用判据是**票据代次**而非请求实例：每次 captureCredentialRequest 都新建实例
        // （见 SessionCredentialRequestScope 的 KDoc），实例身份只在嵌套受理内保持。
        assertSame("受理点固定的是受理时刻的票据代次", accepted.ticket, requireNotNull(observed).ticket)
        session.close()
    }

    @Test fun `同一次受理内的嵌套普通写沿用同一请求`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(database("A"))
        val accepted = session.captureCredentialRequest()
        session.withAcceptedRequest { outer ->
            // 同前一件：受理点固定的是票据代次，实例身份只保证在**同一次**受理的嵌套内沿用
            assertSame(accepted.ticket, outer.ticket)
            session.withAcceptedRequest { inner ->
                assertSame("嵌套受理不得重新捕获票据", outer, inner)
                assertSame(outer, currentCoroutineContext()[SessionCredentialRequest])
            }
        }
        session.close()
    }

    @Test fun `外层过期受理不会被普通写重新捕获`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(database("A"))
        val stale = session.captureCredentialRequest()
        session.lock()
        session.setDatabaseForTesting(database("B"))
        withContext(stale) {
            session.withAcceptedRequest { request ->
                assertSame(stale, request)
                val failure = runCatching { session.saveEntry(KdbxEntry()) }.exceptionOrNull()
                assertTrue(failure is KdbxSessionChangedException)
            }
        }
        assertEquals("B", session.databaseFlow.value!!.rootGroup.name)
        session.close()
    }

    private fun database(label: String) = KdbxDatabase(
        KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = label)
    )
}
