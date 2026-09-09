package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ISSUE-P0-01 (ZT-01) 自动锁定守护单测。
 *
 * 背景：应用存在 AutofillUnlockActivity / CredentialUnlockActivity 两条不经 MainActivity 的
 * 独立冷启动入口，熄屏熔断此前仅经 MainActivity 注册 → 会话在进程存活期内无限期保持解锁。
 * 本测以纯 JVM 内核（[AutoLockSessionGuard] + 真实 [DatabaseSession]，AES-KDF 路径免 NDK）
 * 验证「不启动 MainActivity，仅经解锁入口打开会话 → 熄屏 → 会话锁定」全链路语义。
 */
class AutoLockSessionGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 模拟「仅经 Autofill / Credential 链式解锁入口打开会话」：全程无 MainActivity 参与 */
    private fun unlockViaUnlockEntry(): Pair<DatabaseSession, File> {
        val session = DatabaseSession()
        val file = File(tempFolder.root, "entry_vault.kdbx")
        val result = runBlocking {
            session.create(file, "EntryVault", ENTRY_PASSWORD, useArgon2 = false)
        }
        assertTrue("解锁入口建库应成功", result.isSuccess)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        return session to file
    }

    private fun newGuard(
        session: DatabaseSession,
        settings: FakeSettingsRepository = FakeSettingsRepository().apply {
            runBlocking { setLockWhenScreenOff(true) }
        }
    ): AutoLockSessionGuard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

    @Test
    fun `仅经解锁入口打开会话后熄屏即触发会话锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `仅后台锁定开关开启时熄屏同样熔断`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(true)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `两防护开关均关闭时熄屏不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(false)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `后台停留超时后切回前台即熔断`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        guard.lockOnBackgroundResume(backgroundTimestamp = now - 31_000L, now = now)

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `后台停留未达超时切回前台不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setLockWhenScreenOff(false)
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        val now = System.currentTimeMillis()
        guard.lockOnBackgroundResume(backgroundTimestamp = now - 10_000L, now = now)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `从未退至后台（时间戳为零）切回前台不锁定`() = runBlocking {
        val (session, _) = unlockViaUnlockEntry()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        val guard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

        guard.lockOnBackgroundResume(backgroundTimestamp = 0L)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
        assertFalse(guard.isLocked.value)
    }

    @Test
    fun `熄屏熔断前对未落盘修改先补存再锁定`() = runBlocking {
        val (session, file) = unlockViaUnlockEntry()
        val guard = newGuard(session)

        // 解锁后新增条目（经解锁入口场景同样成立），会话进入 DIRTY
        session.saveEntry(
            KdbxEntry(
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("Banking", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Bank1234", isProtected = true)
                )
            )
        )
        assertEquals(DatabaseSession.SessionState.DIRTY, session.state.value)

        guard.lockOnScreenOff()

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)

        // best-effort 补存生效：重新打开后修改仍在
        val reopen = runBlocking { session.open(file, ENTRY_PASSWORD) }
        assertTrue("锁库前补存应已落盘", reopen.isSuccess)
        val loadedEntries = session.databaseFlow.value?.rootGroup?.allEntries()
        assertNotNull(loadedEntries)
        assertEquals("Banking", loadedEntries!!.single().title)
    }

    companion object {
        private val ENTRY_PASSWORD = "EntryPass!42".toCharArray()
    }
}
