package com.keepasskey.app.security

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.UserSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节流配置源冷启动窗口单测（`ISSUE-P3-579` AC③ 收口，2026-10-11）。
 *
 * 锁定的是两件事：
 * ① **设置流抵达前**回落 `enabled = true`（fail-closed 安全默认）——已开启节流的用户在锁定期内强杀
 *    进程重启，冷启动短窗内**不得**再直接放行（旧实现回落 `false` 即条目所指的绕过窗口）；
 * ② **设置流抵达后**一律按用户偏好收敛（出厂默认关闭 ⇒ 关闭态忽略既有锁定截止）。
 *
 * 生产侧 `collect` 跑在真实 `Dispatchers.Default`（`guardedScope`）上，故收敛断言用**真实时间**轮询
 * （`runBlocking` + `withTimeout`），不依赖虚拟时间。
 */
class UnlockThrottleConfigProviderTest {

    private val dbId = "db_personal"

    /** 设置流永不到达：模拟进程启动初期（DataStore 首值尚未读出）的冷启动窗口 */
    private class NeverEmittingSettings : SettingsRepository by FakeSettingsRepository() {
        override fun getSettings(): Flow<UserSettings> = MutableSharedFlow()
    }

    /** 设置流由用例显式驱动（`replay = 1` ⇒ 无论 collect 何时订阅都能收到已发出的最新值） */
    private class ControllableSettings : SettingsRepository by FakeSettingsRepository() {
        val emissions = MutableSharedFlow<UserSettings>(replay = 1, extraBufferCapacity = 8)
        override fun getSettings(): Flow<UserSettings> = emissions
    }

    /** 真实时间轮询等待（条件不成立即超时失败，防「静默空转的绿」） */
    private fun awaitTrue(condition: () -> Boolean) = runBlocking {
        withTimeout(EVENTUAL_TIMEOUT_MS) {
            while (!condition()) delay(POLL_INTERVAL_MS)
        }
    }

    // ── ① 冷启动窗口：安全默认（开启） ────────────────────────────────

    @Test
    fun `冷启动设置流未抵达时回落安全默认开启`() {
        val provider = UnlockThrottleConfigProvider(NeverEmittingSettings())

        assertTrue("冷启动窗口必须回落 fail-closed 安全默认（开启）", provider.current.enabled)
        assertEquals(UnlockThrottlePolicy.MAX_BACKOFF_MS, provider.current.maxBackoffMs)
    }

    @Test
    fun `冷启动窗口内既有锁定截止仍被强制执行`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, UnlockThrottleConfigProvider(NeverEmittingSettings()))
        val now = 70_000_000L
        store.seed(dbId, UnlockThrottleRecord(failureCount = 7, lockoutUntilEpochMs = now + 600_000L))

        val gate = manager.gate(dbId, now)
        assertTrue("设置流未抵达时不得放行已被锁定的用户", gate is ThrottleGate.Locked)
        assertEquals(600_000L, (gate as ThrottleGate.Locked).remainingMs)
    }

    // ── ② 设置流抵达：按用户偏好收敛 ──────────────────────────────────

    @Test
    fun `设置流抵达后按用户偏好收敛_出厂关闭态下放行既有锁定`() {
        val settings = ControllableSettings()
        val provider = UnlockThrottleConfigProvider(settings)
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, provider)
        val now = 80_000_000L
        store.seed(dbId, UnlockThrottleRecord(failureCount = 7, lockoutUntilEpochMs = now + 600_000L))

        // 抵达前（安全默认开启）：既有锁定截止 fail-closed
        assertTrue(manager.gate(dbId, now) is ThrottleGate.Locked)

        // 抵达后（出厂默认关闭）：收敛为关闭态 ⇒ 忽略既有锁定截止（用户显式退出的既有语义）
        settings.emissions.tryEmit(UserSettings(unlockThrottleEnabled = false))
        awaitFalse { provider.current.enabled }

        assertFalse("设置流抵达后应交由用户偏好裁决", provider.current.enabled)
        assertTrue(manager.gate(dbId, now) is ThrottleGate.Allowed)
    }

    @Test
    fun `设置流抵达后自定义封顶时长按合法域收敛`() {
        val settings = ControllableSettings()
        val provider = UnlockThrottleConfigProvider(settings)

        // 申报值低于合法下限（MIN_LOCKOUT_SECONDS）⇒ 仓库下游口径一致地 coerce 到下限
        settings.emissions.tryEmit(UserSettings(unlockThrottleEnabled = true, unlockLockoutMaxSeconds = 10))
        awaitTrue { provider.current.maxBackoffMs == EXPECTED_MIN_CAP_MS }

        assertEquals(EXPECTED_MIN_CAP_MS, provider.current.maxBackoffMs)
    }

    private fun awaitFalse(condition: () -> Boolean) = awaitTrue { !condition() }

    private companion object {
        const val EVENTUAL_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 5L

        /** 合法下限（[UnlockThrottleConfigProvider.MIN_LOCKOUT_SECONDS] = 60 秒）折算毫秒 */
        val EXPECTED_MIN_CAP_MS =
            UnlockThrottleConfigProvider.MIN_LOCKOUT_SECONDS * 1_000L
    }
}
