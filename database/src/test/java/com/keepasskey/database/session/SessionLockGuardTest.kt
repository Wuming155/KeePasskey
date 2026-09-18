package com.keepasskey.database.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ISSUE-P3-188` §170：[SessionLockGuard] 被五个 ViewModel 共用（详情页 / 编辑页 / 生成器 /
 * 设置页 / 自动填充选择器），它把「注册与注销成对」这一易漏点收敛到一处，
 * 因此**成对性本身必须有用例把守**——原先只有两个 VM 各自带了会话锁定用例，
 * 其余三个 VM 的锁定路径在宿主层无人直调。
 *
 * 本类只验证样板的三条不变量，不重复 `DatabaseSessionLockObserverTest` 已锁的
 * 会话侧契约（lock 与 close 都通知、异常隔离等）。
 */
class SessionLockGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private suspend fun openedSession(): DatabaseSession {
        val session = DatabaseSession()
        val result = session.create(
            file = File(tempFolder.root, "vault_${System.nanoTime()}.kdbx"),
            name = "Vault",
            passwordChars = "GuardTest#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue(result.isSuccess)
        return session
    }

    @Test
    fun `注册后收到回调，注销后不再收到`() = runTest {
        val session = openedSession()
        var calls = 0
        val guard = SessionLockGuard(session) { calls++ }

        guard.register()
        session.lock()
        assertEquals("注册后锁定必须触发一次回调", 1, calls)

        guard.unregister()
        session.close()
        assertEquals("注销后不得再收到回调（成对性不变量）", 1, calls)
    }

    @Test
    fun `重复注册不会造成重复回调`() = runTest {
        val session = openedSession()
        var calls = 0
        val guard = SessionLockGuard(session) { calls++ }

        guard.register()
        guard.register()
        session.lock()
        assertEquals("观测器同一实例重复注册必须幂等", 1, calls)

        guard.unregister()
        session.close()
        assertEquals(1, calls)
    }

    @Test
    fun `会话缺省时注册与注销都是无害空操作`() {
        // 各 ViewModel 的注入通道允许为 null（纯 JVM 单测），故本类不得在 null 会话上抛
        var calls = 0
        val guard = SessionLockGuard(null) { calls++ }

        guard.register()
        guard.unregister()
        assertEquals(0, calls)
    }
}
