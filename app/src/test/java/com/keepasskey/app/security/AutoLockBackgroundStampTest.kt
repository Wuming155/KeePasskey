package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ISSUE-P3-366 AC①：后台化时间戳持久化与「进程重建后恢复判定」单测。
 *
 * 口径：[AutoLockBackgroundStamp] 经 [AutoLockBackgroundStamp.Storage] 单源持久化时间戳，
 * 「进程重建」= 新建 stamp 实例共享同一存储（模拟进程被杀后 SharedPreferences 仍在）；
 * 恢复出的时间戳喂给纯 Kotlin 判定内核 [AutoLockSessionGuard.lockOnBackgroundResume]，
 * 覆盖超时 / 未超时 / 时间戳为零 / 后台锁定开关关闭四个分支，以及清账的对称语义。
 */
class AutoLockBackgroundStampTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 内存假后端：跨「进程重建前后」的两个 stamp 实例共享，模拟持久化层（键值即时间戳） */
    private class MemoryStampStorage : AutoLockBackgroundStamp.Storage {
        var value: Long = 0L
        override fun read(): Long = value
        override fun write(timestamp: Long) {
            value = timestamp
        }

        override fun clear() {
            value = 0L
        }
    }

    private fun unlockSession(): DatabaseSession {
        val session = DatabaseSession()
        val file = File(tempFolder.root, "stamp_vault.kdbx")
        val result = runBlocking {
            session.create(file, "StampVault", ENTRY_PASSWORD, useArgon2 = false)
        }
        assertTrue("建库应成功", result.isSuccess)
        return session
    }

    private fun newGuard(
        session: DatabaseSession,
        settings: FakeSettingsRepository
    ): AutoLockSessionGuard = AutoLockSessionGuard(session, settings, DebugLogBuffer())

    @Test
    fun `后台时间戳持久化后重建的实例可读回同一值`() {
        val storage = MemoryStampStorage()
        AutoLockBackgroundStamp(storage).markBackground(PERSISTED_STAMP)

        // 进程重建：新实例共享同一持久化层
        val rebuilt = AutoLockBackgroundStamp(storage)
        assertEquals(PERSISTED_STAMP, rebuilt.resolveForResume())
    }

    @Test
    fun `进程重建后读到已超时的旧时间戳回前台即熔断`() = runBlocking {
        val storage = MemoryStampStorage()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val session = unlockSession()
        val guard = newGuard(session, settings)

        val now = System.currentTimeMillis()
        AutoLockBackgroundStamp(storage).markBackground(now - 31_000L) // 上一进程退至后台
        // 本进程冷启动：内存无此时间戳，读回持久化旧值
        val recovered = AutoLockBackgroundStamp(storage).resolveForResume()
        guard.lockOnBackgroundResume(recovered, now)

        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue(guard.isLocked.value)
    }

    @Test
    fun `进程重建后读到未超时的旧时间戳回前台不锁定`() = runBlocking {
        val storage = MemoryStampStorage()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val session = unlockSession()
        val guard = newGuard(session, settings)

        val now = System.currentTimeMillis()
        AutoLockBackgroundStamp(storage).markBackground(now - 10_000L)
        val recovered = AutoLockBackgroundStamp(storage).resolveForResume()
        guard.lockOnBackgroundResume(recovered, now)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
    }

    @Test
    fun `无持久化记录（时间戳为零）回前台不锁定`() = runBlocking {
        val storage = MemoryStampStorage()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(true)
        settings.setAutoLockTimeoutSeconds(30)
        val session = unlockSession()
        val guard = newGuard(session, settings)

        val recovered = AutoLockBackgroundStamp(storage).resolveForResume()
        assertEquals("无记录应读回 0", 0L, recovered)
        guard.lockOnBackgroundResume(recovered)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
    }

    @Test
    fun `后台锁定开关关闭时读到旧时间戳也不锁定`() = runBlocking {
        val storage = MemoryStampStorage()
        val settings = FakeSettingsRepository()
        settings.setAutoLockBackground(false)
        settings.setAutoLockTimeoutSeconds(30)
        val session = unlockSession()
        val guard = newGuard(session, settings)

        val now = System.currentTimeMillis()
        AutoLockBackgroundStamp(storage).markBackground(now - 31_000L)
        val recovered = AutoLockBackgroundStamp(storage).resolveForResume()
        guard.lockOnBackgroundResume(recovered, now)

        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)
    }

    @Test
    fun `按当前时间戳清账后读数归零`() {
        val stamp = AutoLockBackgroundStamp(MemoryStampStorage())

        stamp.markBackground(PERSISTED_STAMP)
        stamp.clearIfCurrent(PERSISTED_STAMP)

        assertEquals(0L, stamp.resolveForResume())
    }

    @Test
    fun `非当前时间戳不清账（判定窗口内新后台段不被抹掉）`() {
        val stamp = AutoLockBackgroundStamp(MemoryStampStorage())

        stamp.markBackground(111L) // 本段后台（回前台判定所用）
        stamp.markBackground(222L) // 判定窗口内再次退后台写入的新段
        stamp.clearIfCurrent(111L) // 旧段判定完成试图清账

        assertEquals("新后台段不得被抹掉", 222L, stamp.resolveForResume())
    }

    companion object {
        private val ENTRY_PASSWORD = "StampPass!42".toCharArray()
        private const val PERSISTED_STAMP = 1_727_500_000_000L
    }
}
