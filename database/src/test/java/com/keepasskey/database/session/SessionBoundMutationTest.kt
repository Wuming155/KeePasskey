package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.exception.KdbxSessionChangedException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 排队后替换会话，证明检查发生在真实改树/写盘临界区，而非入队前。 */
class SessionBoundMutationTest {
    @Test fun 旧请求排队后换库不能修改后来会话() = runBlocking {
        for (operation in 0..6) {
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
                            else -> {
                                val result = persistence.save()
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

    private fun database(label: String) = KdbxDatabase(
        KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = label)
    )
}
