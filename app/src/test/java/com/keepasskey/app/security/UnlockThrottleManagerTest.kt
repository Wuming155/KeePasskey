package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主密码解锁失败节流单测（ISSUE-P1-04 / ZT-04）。
 *
 * 覆盖：退避策略纯函数、失败计数累加、锁定触发与到期自动放行、成功重置，以及
 * ENG-20261028-37 的时间语义——持久截止按墙钟恢复、进程内倒计时只由单调时钟推进、
 * 调时不得缩短或延长等待、重启期间墙钟回拨造成的超长截止按策略上限截断。
 * 时间经 [FakeThrottleClock] 注入，锁定边界可精确断言，无需真实等待。
 */
class UnlockThrottleManagerTest {

    private val dbId = "db_personal"
    private val clock = FakeThrottleClock(wallStart = 1_000_000L)

    // ── 退避策略纯函数 ────────────────────────────────────────────────

    @Test
    fun `阈值以下不退避`() {
        for (count in 0 until UnlockThrottlePolicy.FAILURE_THRESHOLD) {
            assertEquals("失败 $count 次不应锁定", 0L, UnlockThrottlePolicy.backoffMillisFor(count))
        }
    }

    @Test
    fun `达到阈值起启用退避并指数增长`() {
        val atThreshold = UnlockThrottlePolicy.backoffMillisFor(UnlockThrottlePolicy.FAILURE_THRESHOLD)
        assertEquals(UnlockThrottlePolicy.BASE_BACKOFF_MS, atThreshold)

        val next = UnlockThrottlePolicy.backoffMillisFor(UnlockThrottlePolicy.FAILURE_THRESHOLD + 1)
        assertEquals(UnlockThrottlePolicy.BASE_BACKOFF_MS * 2, next)
        assertTrue("退避应随时长单调增长", next > atThreshold)
    }

    @Test
    fun `退避时长封顶于上限不溢出`() {
        val huge = UnlockThrottlePolicy.backoffMillisFor(UnlockThrottlePolicy.FAILURE_THRESHOLD + 100)
        assertEquals(UnlockThrottlePolicy.MAX_BACKOFF_MS, huge)
        assertTrue("退避时长必须为正", huge > 0L)
    }

    // ── 计数累加 ──────────────────────────────────────────────────────

    @Test
    fun `连续失败累加计数且阈值前不锁定`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD - 1) { i ->
            val gate = manager.registerFailure(dbId)
            assertTrue("第 ${i + 1} 次失败不应锁定", gate is ThrottleGate.Allowed)
            assertEquals(i + 1, gate.failureCount)
        }
        // 闸门仍放行
        assertTrue(manager.gate(dbId) is ThrottleGate.Allowed)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD - 1, store.read(dbId).failureCount)
    }

    // ── 锁定触发与到期放行 ────────────────────────────────────────────

    @Test
    fun `达到阈值触发锁定并在到期后自动放行`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)

        var gate: ThrottleGate = ThrottleGate.Allowed(0)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) {
            gate = manager.registerFailure(dbId)
        }
        assertTrue("第 ${UnlockThrottlePolicy.FAILURE_THRESHOLD} 次失败应触发锁定", gate is ThrottleGate.Locked)
        val locked = gate as ThrottleGate.Locked
        assertEquals(UnlockThrottlePolicy.BASE_BACKOFF_MS, locked.remainingMs)

        // 锁定期内闸门 fail-closed
        clock.advance(1_000L)
        val inLock = manager.gate(dbId)
        assertTrue(inLock is ThrottleGate.Locked)

        // 到期后自动放行，但计数保留（下次失败继续升级退避）
        clock.advance(UnlockThrottlePolicy.BASE_BACKOFF_MS)
        val afterExpiry = manager.gate(dbId)
        assertTrue("锁定到期后应放行", afterExpiry is ThrottleGate.Allowed)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD, afterExpiry.failureCount)
    }

    @Test
    fun `成功解锁重置计数与锁定`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { manager.registerFailure(dbId) }
        assertTrue(manager.gate(dbId) is ThrottleGate.Locked)

        manager.registerSuccess(dbId)

        assertEquals(0, store.read(dbId).failureCount)
        assertEquals(0L, store.read(dbId).lockoutUntilEpochMs)
        val gate = manager.gate(dbId)
        assertTrue(gate is ThrottleGate.Allowed)
        assertEquals(0, gate.failureCount)
    }

    @Test
    fun `预置锁定态下闸门拒绝且剩余时长正确`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)
        // 失败 7 次对应策略上限 120 秒，3 秒剩余在截断范围内
        store.seed(dbId, UnlockThrottleRecord(failureCount = 7, lockoutUntilEpochMs = clock.wall + 3_000L))

        val gate = manager.gate(dbId)
        assertTrue(gate is ThrottleGate.Locked)
        assertEquals(3_000L, (gate as ThrottleGate.Locked).remainingMs)
        assertEquals(7, gate.failureCount)
    }

    // ── ENG-20261028-37：墙钟与单调时钟的组合语义 ──────────────────────

    @Test
    fun `锁定期内墙钟前拨不缩短等待`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { manager.registerFailure(dbId) }

        clock.shiftWall(2L * 60L * 60L * 1_000L) // 把系统时间调快 2 小时

        val gate = manager.gate(dbId)
        assertTrue("调快系统时间不得提前解锁", gate is ThrottleGate.Locked)
        assertEquals(
            "剩余等待由单调时钟决定，调时不得缩短",
            UnlockThrottlePolicy.BASE_BACKOFF_MS,
            (gate as ThrottleGate.Locked).remainingMs
        )
    }

    @Test
    fun `锁定期内墙钟回拨不延长等待`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { manager.registerFailure(dbId) }

        clock.shiftWall(-60L * 60L * 1_000L) // 把系统时间调慢 1 小时

        val gate = manager.gate(dbId)
        assertTrue(gate is ThrottleGate.Locked)
        assertEquals(
            "剩余等待由单调时钟决定，调时不得延长",
            UnlockThrottlePolicy.BASE_BACKOFF_MS,
            (gate as ThrottleGate.Locked).remainingMs
        )
    }

    @Test
    fun `到期后墙钟回拨不复活锁定`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, clock = clock)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { manager.registerFailure(dbId) }

        clock.advance(UnlockThrottlePolicy.BASE_BACKOFF_MS)
        assertTrue(manager.gate(dbId) is ThrottleGate.Allowed)

        // 持久截止仍在 store 中；本进程已观测到期，回拨墙钟不得把同一锁定重新立起来
        clock.shiftWall(-60L * 60L * 1_000L)
        assertTrue("进程内已到期的锁定不得因墙钟回拨复活", manager.gate(dbId) is ThrottleGate.Allowed)
    }

    @Test
    fun `重启后按持久截止继续节流并在截止后放行`() {
        val store = FakeUnlockThrottleStore()
        val beforeRestart = UnlockThrottleManager(store, clock = clock)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { beforeRestart.registerFailure(dbId) }

        // 新进程：墙钟已流逝 10 秒（锁定 30 秒），单调时钟自零起算、进程内状态为空
        val restartedClock = FakeThrottleClock(wallStart = clock.wall + 10_000L)
        val restarted = UnlockThrottleManager(store, clock = restartedClock)

        val gate = restarted.gate(dbId)
        assertTrue("重启后必须按持久截止继续节流", gate is ThrottleGate.Locked)
        assertEquals(20_000L, (gate as ThrottleGate.Locked).remainingMs)

        restartedClock.advance(20_000L)
        assertTrue("持久截止到期后应放行", restarted.gate(dbId) is ThrottleGate.Allowed)
    }

    @Test
    fun `重启后墙钟回拨造成的超长截止被截断`() {
        val store = FakeUnlockThrottleStore()
        val beforeRestart = UnlockThrottleManager(store, clock = clock)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { beforeRestart.registerFailure(dbId) }

        // 进程死亡期间墙钟被回拨 1 小时：持久截止看上去还有 1 小时 + 30 秒
        val restartedClock = FakeThrottleClock(wallStart = clock.wall - 60L * 60L * 1_000L)
        val restarted = UnlockThrottleManager(store, clock = restartedClock)

        val gate = restarted.gate(dbId)
        assertTrue(gate is ThrottleGate.Locked)
        assertEquals(
            "虚增的剩余时长须截断到该失败次数的策略上限",
            UnlockThrottlePolicy.backoffMillisFor(UnlockThrottlePolicy.FAILURE_THRESHOLD),
            (gate as ThrottleGate.Locked).remainingMs
        )
    }

    // ── ISSUE-P3-68：运行时配置（总开关 / 自定义封顶） ──────────────────

    private fun sourceOf(config: ThrottleConfig) = object : ThrottleConfigSource {
        override val current: ThrottleConfig = config
    }

    @Test
    fun `开关关闭时失败不再触发锁定且既有锁定被放行`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, sourceOf(ThrottleConfig(enabled = false)), clock)

        // 超阈值多次失败：不再锁定
        var gate: ThrottleGate = ThrottleGate.Allowed(0)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD + 3) {
            gate = manager.registerFailure(dbId)
        }
        assertTrue("开关关闭时失败不得锁定", gate is ThrottleGate.Allowed)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD + 3, gate.failureCount)

        // 预置锁定态（开关打开期间留下）在关闭后同样放行
        store.seed(dbId, UnlockThrottleRecord(failureCount = 9, lockoutUntilEpochMs = clock.wall + 600_000L))
        val seededGate = manager.gate(dbId)
        assertTrue("开关关闭时应忽略既有锁定截止", seededGate is ThrottleGate.Allowed)
        assertEquals(9, seededGate.failureCount)
    }

    @Test
    fun `开关重新打开后节流恢复生效`() {
        val store = FakeUnlockThrottleStore()
        val source = object : ThrottleConfigSource {
            override var current: ThrottleConfig = ThrottleConfig(enabled = false)
        }
        val manager = UnlockThrottleManager(store, source, clock)

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD + 3) { manager.registerFailure(dbId) }
        assertTrue(manager.gate(dbId) is ThrottleGate.Allowed)

        // 开关恢复后再失败一次：按既有计数（含关闭期间累加的次数）重新进入退避锁定
        source.current = ThrottleConfig(enabled = true)
        val gate = manager.registerFailure(dbId)
        assertTrue("开关恢复后应重新进入锁定期", gate is ThrottleGate.Locked)
        assertTrue(manager.gate(dbId) is ThrottleGate.Locked)
    }

    @Test
    fun `自定义封顶时长生效于退避策略`() {
        val capMs = 60_000L
        // 60 秒封顶：5 次失败退避 30 秒（未触顶），6 次即被压到 60 秒（原策略会到 120 秒）
        assertEquals(30_000L, UnlockThrottlePolicy.backoffMillisFor(5, ThrottleConfig(maxBackoffMs = capMs)))
        assertEquals(capMs, UnlockThrottlePolicy.backoffMillisFor(6, ThrottleConfig(maxBackoffMs = capMs)))
        assertEquals(capMs, UnlockThrottlePolicy.backoffMillisFor(50, ThrottleConfig(maxBackoffMs = capMs)))

        // 管理器侧：注入自定义封顶后 registerFailure 按新上限锁定
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, sourceOf(ThrottleConfig(maxBackoffMs = capMs)), clock)

        var gate: ThrottleGate = ThrottleGate.Allowed(0)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD + 1) { gate = manager.registerFailure(dbId) }
        assertTrue(gate is ThrottleGate.Locked)
        assertEquals(capMs, (gate as ThrottleGate.Locked).remainingMs)
    }
}