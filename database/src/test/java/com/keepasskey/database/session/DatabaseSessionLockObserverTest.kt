package com.keepasskey.database.session

import com.keepasskey.core.session.SessionLockObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ISSUE-P1-07 会话终止观察者契约测试：
 * 「锁定即销毁」必须可被外部衍生数据的持有方订阅——同步缓存的完整 KDBX 密文快照
 * 依赖该回调在锁库/关闭时一并清理，否则数据生命周期没有终止点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseSessionLockObserverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class RecordingObserver : SessionLockObserver {
        var calls = 0
        override fun onSessionLocked() {
            calls++
        }
    }

    private class ThrowingObserver : SessionLockObserver {
        var calls = 0
        override fun onSessionLocked() {
            calls++
            throw IllegalStateException("清理失败（模拟）")
        }
    }

    private suspend fun openedSession(): DatabaseSession {
        val session = DatabaseSession()
        val result = session.create(
            file = File(tempFolder.root, "vault_${System.nanoTime()}.kdbx"),
            name = "Vault",
            passwordChars = "ObserverTest#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue(result.isSuccess)
        return session
    }

    @Test
    fun `lock 与 close 均触发观察者`() = runTest {
        val session = openedSession()
        val observer = RecordingObserver()
        assertTrue(session.addLockObserver(observer))

        session.lock()
        assertEquals(1, observer.calls)

        session.close()
        assertEquals(2, observer.calls)
    }

    @Test
    fun `重复注册幂等且注销后不再回调`() = runTest {
        val session = openedSession()
        val observer = RecordingObserver()

        assertTrue(session.addLockObserver(observer))
        assertEquals("重复注册必须为幂等无操作", false, session.addLockObserver(observer))

        session.lock()
        assertEquals("重复注册不得导致重复回调", 1, observer.calls)

        assertTrue(session.removeLockObserver(observer))
        session.close()
        assertEquals(1, observer.calls)
    }

    @Test
    fun `观察者抛异常不得阻断锁库`() = runTest {
        val session = openedSession()
        val failing = ThrowingObserver()
        val healthy = RecordingObserver()
        session.addLockObserver(failing)
        session.addLockObserver(healthy)

        session.lock()

        assertEquals(1, failing.calls)
        assertEquals("前序观察者失败不得影响后续观察者", 1, healthy.calls)
        assertEquals(
            "清理失败不得阻断会话锁定",
            DatabaseSession.SessionState.LOCKED,
            session.state.value
        )
    }
}
