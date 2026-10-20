package com.keepasskey.app.passkey

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.database.exception.KdbxSessionChangedException
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Android Intent 身份解析 + 真实会话文件；不代替系统选择器/生物验证完整链路。 */
@RunWith(AndroidJUnit4::class)
class CredentialSessionGateDeviceTest {
    @Test fun 四类入口在Android按原会话提交且换库后全部拒绝() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "credential-gate-${System.nanoTime()}").apply { mkdirs() }
        val password = "Synthetic-Gate-2026".toCharArray()
        val session = DatabaseSession()
        try {
            val first = File(directory, "A.kdbx")
            val later = File(directory, "B.kdbx")
            assertTrue(session.create(first, "A", password, useArgon2 = false).isSuccess)
            val ticket = session.captureCredentialRequest().ticket!!
            val gates = listOf(CredentialEntryRequestType.PASSKEY_ASSERTION, CredentialEntryRequestType.PASSKEY_CREATE,
                CredentialEntryRequestType.PASSWORD_SAVE, CredentialEntryRequestType.PASSWORD_DRAFT).map { type ->
                val intent = Intent()
                CredentialPendingIntents.attachRequestIdentity(intent, CredentialEntryRequestContext(
                    type, first.absolutePath, null, "https://example.test", "com.example.synthetic", sessionTicket = ticket
                ))
                CredentialSessionGate(session).also { gate ->
                    assertTrue(gate.bind(intent, type))
                    gate.withRequest { session.saveEntry(KdbxEntry()); assertTrue(session.save().isSuccess) }
                    assertTrue(gate.complete { })
                }
            }
            val originalA = first.readBytes()
            assertTrue(session.create(later, "B", password, useArgon2 = false).isSuccess)
            val originalB = later.readBytes()
            for (gate in gates) {
                try { gate.withRequest { session.saveEntry(KdbxEntry()) }; fail("旧请求必须被拒绝") }
                catch (_: KdbxSessionChangedException) { }
                try { gate.complete { fail("不能回传成功") }; fail("必须复核票据") }
                catch (_: KdbxSessionChangedException) { }
            }
            assertArrayEquals(originalA, first.readBytes())
            assertArrayEquals(originalB, later.readBytes())
            assertTrue(session.databaseFlow.value!!.rootGroup.allEntries().isEmpty())
        } finally {
            session.close()
            password.fill('0')
            directory.deleteRecursively()
        }
    }
}
