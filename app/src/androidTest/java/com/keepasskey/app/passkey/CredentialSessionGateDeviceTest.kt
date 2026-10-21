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

    /**
     * `ISSUE-P2-641` 验收矩阵里的「旧 token 重放」跨**真实进程重建**。
     *
     * 登记表 `CredentialPendingIntents.requestContexts` 是进程内 map：进程重建后
     * 旧 Intent 里的 requestId 语法上仍然合法，但已查不到对应上下文，`bind` 必须返回 false。
     * 这条只能由外部杀进程复现——单次 instrumentation 无法「自杀后继续断言」。
     *
     * 由 `tools/device/run_p2_641_token_replay.py` 分两段驱动。
     */
    @Test fun prepareRegistersOldToken() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("只允许宿主以 p2641Phase=prepare 启动", "prepare", arguments.getString(ARG_PHASE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "p2-641-replay").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val session = DatabaseSession()
        val password = "Synthetic-P2-641-Replay".toCharArray()
        try {
            val vault = File(directory, "replay.kdbx")
            assertTrue(session.create(vault, "Replay", password, useArgon2 = false).isSuccess)
            val ticket = session.captureCredentialRequest().ticket!!
            val intent = Intent()
            CredentialPendingIntents.attachRequestIdentity(intent, CredentialEntryRequestContext(
                CredentialEntryRequestType.PASSKEY_ASSERTION,
                vault.absolutePath, null, "https://example.test", "com.example.synthetic",
                sessionTicket = ticket
            ))
            // 同一进程内旧 token 必须可用（否则本条证明的不是「跨进程失效」而是「本来就没登记」）
            assertTrue("prepare 段登记后同一进程内必须可用", CredentialSessionGate(session).bind(
                intent, CredentialEntryRequestType.PASSKEY_ASSERTION
            ))
            val requestId = CredentialPendingIntents.requestIdFromIntent(intent)
                ?: error("登记后必须能从 Intent 取回 requestId")
            // 只落盘**非秘密**的 requestId 与库路径：verify 段据此重建同样的 Intent
            File(directory, "GATE_REACHED").writeText(
                buildString {
                    appendLine(android.os.Process.myPid())
                    appendLine(requestId)
                    appendLine(vault.absolutePath)
                }
            )
        } finally {
            session.close()
            password.fill('\u0000')
        }
    }

    @Test fun verifyOldTokenRejectedInNewProcess() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("只允许宿主以 p2641Phase=verify 启动", "verify", arguments.getString(ARG_PHASE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "p2-641-replay")
        val lines = File(directory, "GATE_REACHED").readLines()
        val oldPid = lines[0].trim().toInt()
        val requestId = lines[1].trim()
        val vaultPath = lines[2].trim()
        assertNotEquals(
            "verify 段必须是**新进程**（同 PID 说明进程没被杀掉，验收无效）",
            oldPid,
            android.os.Process.myPid()
        )

        val session = DatabaseSession()
        val password = "Synthetic-P2-641-Replay".toCharArray()
        try {
            assertTrue("新进程必须能重新打开同一本库", session.open(File(vaultPath), password).isSuccess)
            // 重建与 prepare 段等价的 Intent：requestId 语法合法，但登记表已随进程消失
            val intent = Intent().apply {
                data = android.net.Uri.parse("keepasskey://credential-entry/$requestId")
            }
            assertNotNull(
                "requestId 本身必须仍是合法 URI 形态（否则拒绝的原因是解析失败，不是登记表失效）",
                CredentialPendingIntents.requestIdFromIntent(intent)
            )
            assertNull(
                "进程重建后旧 requestId 不得再查到上下文",
                CredentialPendingIntents.requestContext(requestId, CredentialEntryRequestType.PASSKEY_ASSERTION)
            )
            assertFalse(
                "进程重建后旧 token 必须被拒绝受理（否则等于把旧请求重放到新进程）",
                CredentialSessionGate(session).bind(intent, CredentialEntryRequestType.PASSKEY_ASSERTION)
            )
            // 反向对照：本进程内新登记的 token 必须可用，证明拒绝来自「登记表丢失」而非别的原因
            val fresh = Intent()
            CredentialPendingIntents.attachRequestIdentity(fresh, CredentialEntryRequestContext(
                CredentialEntryRequestType.PASSKEY_ASSERTION,
                vaultPath, null, "https://example.test", "com.example.synthetic",
                sessionTicket = session.captureCredentialRequest().ticket
            ))
            assertTrue(
                "同进程内新登记的 token 必须可用（正对照）",
                CredentialSessionGate(session).bind(fresh, CredentialEntryRequestType.PASSKEY_ASSERTION)
            )
        } finally {
            session.close()
            password.fill('\u0000')
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val ARG_PHASE = "p2641Phase"
    }
}
