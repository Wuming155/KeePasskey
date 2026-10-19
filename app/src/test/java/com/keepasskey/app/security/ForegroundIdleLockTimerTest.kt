package com.keepasskey.app.security

import com.keepasskey.app.data.repository.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundIdleLockTimerTest {

    @Test
    fun `关闭前台闲置锁定会取消旧 deadline`() = runTest {
        val fixture = Fixture(this)
        fixture.timeout(5)
        fixture.timer.schedule()
        runCurrent()

        fixture.settings.setAutoLockForegroundEnabled(false)
        fixture.timer.schedule()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(emptyList<String>(), fixture.lockReasons)
    }

    @Test
    fun `延长超时不在旧 deadline 锁定并在新 deadline 锁定`() = runTest {
        val fixture = Fixture(this)
        fixture.timeout(5)
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(2_000)

        fixture.timeout(10)
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.lockReasons)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, fixture.lockReasons.size)
    }

    @Test
    fun `缩短超时按最新 deadline 锁定`() = runTest {
        val fixture = Fixture(this)
        fixture.timeout(10)
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(2_000)

        fixture.timeout(4)
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(1, fixture.lockReasons.size)
    }

    @Test
    fun `连续交互重排后仅按最近一次交互的 deadline 锁定`() = runTest {
        val fixture = Fixture(this)
        fixture.timeout(5)
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(3_000)

        fixture.lastInteraction = testScheduler.currentTime
        repeat(6) { fixture.timer.schedule() }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.lockReasons)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, fixture.lockReasons.size)
    }

    @Test
    fun `转后台取消计时且重新解锁后可重新启动`() = runTest {
        val fixture = Fixture(this)
        fixture.timeout(5)
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(2_000)

        fixture.inBackground = true
        fixture.timer.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.lockReasons)

        fixture.inBackground = false
        fixture.locked = true
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.lockReasons)

        fixture.locked = false
        fixture.lastInteraction = testScheduler.currentTime
        fixture.timer.schedule()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, fixture.lockReasons.size)
    }

    private class Fixture(scope: kotlinx.coroutines.test.TestScope) {
        val settings = FakeSettingsRepository()
        val lockReasons = mutableListOf<String>()
        var inBackground = false
        var locked = false
        var lastInteraction = 0L
        val timer = ForegroundIdleLockTimer(
            scope = scope.backgroundScope,
            settingsRepository = settings,
            isInBackground = { inBackground },
            isLocked = { locked },
            lastInteractionMillis = { lastInteraction },
            nowMillis = { scope.testScheduler.currentTime },
            triggerLock = { lockReasons += it }
        )

        suspend fun timeout(seconds: Int) {
            settings.setAutoLockForegroundTimeoutSeconds(seconds)
        }
    }
}
