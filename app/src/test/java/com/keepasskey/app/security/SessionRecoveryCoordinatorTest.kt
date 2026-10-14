package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `ISSUE-P1-592` 步骤 2 / 4：补存失败后的独立加密恢复单测。
 *
 * 用真实 [DatabaseSession] + AES-KDF 路径（免 NDK）：正式写通道注入「必然抛异常」的 writer
 * 模拟磁盘满，验证「补存失败 → 仍锁库 → 恢复副本已提交（且可用会话复合凭据解开）」
 * 与「两通道皆失败 → 登记无法保全告警」两条结局。
 */
class SessionRecoveryCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 打开一个「正式写盘必然失败」的会话，并写入一条新条目使其进入 DIRTY。 */
    private fun dirtySessionWithFailingSave(): Pair<DatabaseSession, File> {
        val file = File(tempFolder.root, "vault.kdbx")
        val seed = DatabaseSession()
        runBlocking { assertTrue(seed.create(file, "Vault", PASSWORD, useArgon2 = false).isSuccess) }
        runBlocking { seed.lock() }

        val session = DatabaseSession()
        val opened = runBlocking {
            session.openStream(
                pathIdentifier = file.absolutePath,
                inputStreamProvider = { java.io.FileInputStream(file) },
                saveWriter = { throw java.io.IOException("disk full") },
                passwordChars = PASSWORD
            )
        }
        assertTrue(opened.isSuccess)
        runBlocking {
            session.saveEntry(
                KdbxEntry(
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Recovered", isProtected = false),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("Secret123", isProtected = true)
                    )
                )
            )
        }
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)
        return session to file
    }

    private fun store(dir: File) =
        VaultRecoveryStore(null, DebugLogBuffer()).apply { baseDirOverride = dir }

    @Test
    fun `补存失败时写出可用会话凭据解开的恢复副本`() = runBlocking {
        val (session, file) = dirtySessionWithFailingSave()
        val recoveryStore = store(tempFolder.root)
        val coordinator = SessionRecoveryCoordinator(
            session, recoveryStore, RecoveryAlertStore(null), DebugLogBuffer()
        )

        val outcome = coordinator.onSaveFailedBeforeLock()

        assertEquals(LockOutcome.RecoveryCommitted, outcome)

        val entry = recoveryStore.list().single()
        assertEquals(file.absolutePath, entry.vaultId)

        val bytes = recoveryStore.readPayload(entry)
        assertNotNull(bytes)
        try {
            // 恢复副本是标准 KDBX，用同一会话凭据可解，且含未落盘的新条目
            val restored = KdbxFile.load(ByteArrayInputStream(bytes!!), PASSWORD.clone())
            val titles = restored.rootGroup.allEntries().map { it.title }
            assertTrue("恢复副本应含补存失败时未落盘的条目", titles.contains("Recovered"))
            restored.clearSensitiveData()
        } finally {
            bytes!!.fill(0)
        }
    }

    @Test
    fun `恢复通道也失败时登记无法保全告警并回传未持久化标志`() = runBlocking {
        val (session, _) = dirtySessionWithFailingSave()
        val blocker = File(tempFolder.root, "blocker").apply { writeText("x") }
        val brokenStore = store(File(blocker, "unreachable"))
        val alertStore = RecoveryAlertStore(null)
        val coordinator = SessionRecoveryCoordinator(session, brokenStore, alertStore, DebugLogBuffer())

        val outcome = coordinator.onSaveFailedBeforeLock()

        // 单测内存态告警恒可写 ⇒ alertPersisted = true
        assertEquals(LockOutcome.PreservationFailed(alertPersisted = true), outcome)
        assertNotNull("无法保全事实必须登记供下次进入应用查看", alertStore.consume())
    }

    private companion object {
        val PASSWORD = "VaultPass!42".toCharArray()
    }
}
