package com.keepasskey.app.passkey

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.database.exception.KdbxSessionChangedException
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CredentialSessionGateTest {
    @Test fun 四类旧入口不能读写后来活动库或回传成功() = runBlocking {
        val directory = createTempDirectory("credential-session-").toFile()
        val session = DatabaseSession()
        val password = "Synthetic-Session-2026".toCharArray()
        try {
            val first = File(directory, "A.kdbx")
            val later = File(directory, "B.kdbx")
            assertTrue(session.create(first, "A", password, useArgon2 = false).isSuccess)
            val firstTicket = session.captureCredentialRequest().ticket!!
            val gates = TYPES.map { type ->
                val identity = register(type, first.absolutePath, firstTicket)
                CredentialSessionGate(session).also { assertTrue(it.bind(identity, type)) }
            }
            assertTrue(session.create(later, "B", password, useArgon2 = false).isSuccess)
            val before = later.readBytes()
            for (gate in gates) {
                try { gate.withRequest { session.saveEntry(KdbxEntry()) }; fail("旧入口必须拒绝") }
                catch (_: KdbxSessionChangedException) { }
                try { gate.complete { fail("旧入口不能交付成功") }; fail("必须检查会话") }
                catch (_: KdbxSessionChangedException) { }
            }
            assertTrue(session.databaseFlow.value!!.rootGroup.allEntries().isEmpty())
            assertArrayEquals(before, later.readBytes())
        } finally {
            session.close()
            password.fill('0')
            directory.deleteRecursively()
        }
    }

    @Test fun 锁定入口只可重绑定原库一次且同路径重开失效() = runBlocking {
        val directory = createTempDirectory("credential-unlock-").toFile()
        val session = DatabaseSession()
        val password = "Synthetic-Unlock-2026".toCharArray()
        try {
            val file = File(directory, "A.kdbx")
            assertTrue(session.create(file, "A", password, useArgon2 = false).isSuccess)
            session.lock()
            val identity = register(CredentialEntryRequestType.PASSWORD_SAVE, file.absolutePath, null)
            val gate = CredentialSessionGate(session)
            assertTrue(gate.bind(identity, CredentialEntryRequestType.PASSWORD_SAVE, allowUnlock = true))
            assertFalse(CredentialSessionGate(session).bind(identity, CredentialEntryRequestType.PASSKEY_CREATE, true))
            assertTrue(session.create(File(directory, "B.kdbx"), "B", password, useArgon2 = false).isSuccess)
            try { gate.withRequest { fail("锁定入口也不能重绑定到B") }; fail("必须拒绝不同路径") }
            catch (_: KdbxSessionChangedException) { }
            assertTrue(session.open(file, password.copyOf()).isSuccess)
            gate.withRequest { session.saveEntry(KdbxEntry()) }
            var delivered = false
            assertTrue(gate.complete { delivered = true })
            assertTrue(delivered)
            assertTrue(session.open(file, password.copyOf()).isSuccess)
            try { gate.withRequest { fail("同路径重开不能沿用已绑定票据") }; fail("必须拒绝") }
            catch (_: KdbxSessionChangedException) { }
        } finally {
            session.close()
            password.fill('0')
            directory.deleteRecursively()
        }
    }

    private fun register(type: CredentialEntryRequestType, path: String, ticket: com.keepasskey.database.session.SessionCredentialTicket?): String {
        val identity = CredentialPendingIntents.newRequestId()
        CredentialPendingIntents.registerRequestContext(identity, CredentialEntryRequestContext(
            type, path, null, "https://example.test", "com.example.synthetic", sessionTicket = ticket
        ))
        return identity
    }

    companion object {
        private val TYPES = listOf(
            CredentialEntryRequestType.PASSKEY_ASSERTION, CredentialEntryRequestType.PASSKEY_CREATE,
            CredentialEntryRequestType.PASSWORD_SAVE, CredentialEntryRequestType.PASSWORD_DRAFT
        )
    }
}
