package com.keepasskey.app.security

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundIdleLockTimerTest {

    @After
    fun tearDown() {
        // 只回收本类装上的 Main；MainDispatcherGuard 会为下一个用例装新鲜默认态。
        MainDispatcherGuard.tearDown()
    }

    /**
     * 计时器的挂起/唤醒与 `nowMillis` 必须落在**同一个**虚拟时钟上。
     *
     * `runTest` 会继承 `Dispatchers.Main` 上残留的测试调度器（可能来自前一个测试类），
     * 若本类不显式绑定，`backgroundScope` 与 Main 就会分属两个 scheduler：单类跑绿、全量红。
     */
    private fun bindClock(scope: kotlinx.coroutines.test.TestScope) {
        Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))
    }

    @Test
    fun `关闭前台闲置锁定会取消旧 deadline`() = runTest {
        bindClock(this)
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
        bindClock(this)
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
        bindClock(this)
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
        bindClock(this)
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
        bindClock(this)
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
        // 以本用例虚拟时钟的当前值为交互起点：runTest 会继承 Main 上残留调度器的时钟，
        // 固定写 0 在时钟已被推进的全量运行里会被判成「早已闲置超时」（单类从 0 起故不显）。
        var lastInteraction = scope.testScheduler.currentTime
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
