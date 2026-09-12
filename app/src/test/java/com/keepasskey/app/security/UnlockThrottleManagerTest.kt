package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主密码解锁失败节流单测（ISSUE-P1-04 / ZT-04）。
 *
 * 覆盖：退避策略纯函数、失败计数累加、锁定触发与到期自动放行、成功重置。
 * 时间以显式 `now` 注入，锁定边界可精确断言，无需真实等待。
 */
class UnlockThrottleManagerTest {

    private val dbId = "db_personal"

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
        val manager = UnlockThrottleManager(store)
        val now = 1_000_000L

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD - 1) { i ->
            val gate = manager.registerFailure(dbId, now)
            assertTrue("第 ${i + 1} 次失败不应锁定", gate is ThrottleGate.Allowed)
            assertEquals(i + 1, gate.failureCount)
        }
        // 闸门仍放行
        assertTrue(manager.gate(dbId, now) is ThrottleGate.Allowed)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD - 1, store.read(dbId).failureCount)
    }

    // ── 锁定触发与到期放行 ────────────────────────────────────────────

    @Test
    fun `达到阈值触发锁定并在到期后自动放行`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store)
        val now = 5_000_000L

        var gate: ThrottleGate = ThrottleGate.Allowed(0)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) {
            gate = manager.registerFailure(dbId, now)
        }
        assertTrue("第 ${UnlockThrottlePolicy.FAILURE_THRESHOLD} 次失败应触发锁定", gate is ThrottleGate.Locked)
        val locked = gate as ThrottleGate.Locked
        assertEquals(UnlockThrottlePolicy.BASE_BACKOFF_MS, locked.remainingMs)

        // 锁定期内闸门 fail-closed
        val inLock = manager.gate(dbId, now + 1_000L)
        assertTrue(inLock is ThrottleGate.Locked)

        // 到期后自动放行，但计数保留（下次失败继续升级退避）
        val afterExpiry = manager.gate(dbId, now + UnlockThrottlePolicy.BASE_BACKOFF_MS + 1L)
        assertTrue("锁定到期后应放行", afterExpiry is ThrottleGate.Allowed)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD, afterExpiry.failureCount)
    }

    @Test
    fun `成功解锁重置计数与锁定`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store)
        val now = 9_000_000L

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) { manager.registerFailure(dbId, now) }
        assertTrue(manager.gate(dbId, now) is ThrottleGate.Locked)

        manager.registerSuccess(dbId)

        assertEquals(0, store.read(dbId).failureCount)
        assertEquals(0L, store.read(dbId).lockoutUntilEpochMs)
        val gate = manager.gate(dbId, now)
        assertTrue(gate is ThrottleGate.Allowed)
        assertEquals(0, gate.failureCount)
    }

    @Test
    fun `预置锁定态下闸门拒绝且剩余时长正确`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store)
        val lockUntil = 20_000_000L
        store.seed(dbId, UnlockThrottleRecord(failureCount = 7, lockoutUntilEpochMs = lockUntil))

        val gate = manager.gate(dbId, now = lockUntil - 3_000L)
        assertTrue(gate is ThrottleGate.Locked)
        assertEquals(3_000L, (gate as ThrottleGate.Locked).remainingMs)
        assertEquals(7, gate.failureCount)
    }

    // ── ISSUE-P3-54：记录完整性 fail-closed ────────────────────────────

    @Test
    fun `记录完整性校验失败时闸门failClosed并落有效锁定期`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store)
        val now = 30_000_000L
        // 模拟「计数 / 锁定记录被删除或篡改」：完整性标记为失效
        store.seed(
            dbId,
            UnlockThrottleRecord(failureCount = 0, lockoutUntilEpochMs = 0L, integrityIntact = false)
        )

        val gate = manager.gate(dbId, now)

        assertTrue("完整性失效必须 fail-closed 为锁定", gate is ThrottleGate.Locked)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD, gate.failureCount)
        assertEquals(UnlockThrottlePolicy.MAX_BACKOFF_MS, (gate as ThrottleGate.Locked).remainingMs)
        // 已回写一条带有效 MAC 的有界锁定期记录（不再永久失效）
        val persisted = store.read(dbId)
        assertTrue(persisted.integrityIntact)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD, persisted.failureCount)
        assertEquals(now + UnlockThrottlePolicy.MAX_BACKOFF_MS, persisted.lockoutUntilEpochMs)
    }

    // ── ISSUE-P3-68：运行时配置（总开关 / 自定义封顶） ──────────────────

    private fun sourceOf(config: ThrottleConfig) = object : ThrottleConfigSource {
        override val current: ThrottleConfig = config
    }

    @Test
    fun `开关关闭时失败不再触发锁定且既有锁定被放行`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, sourceOf(ThrottleConfig(enabled = false)))
        val now = 40_000_000L

        // 超阈值多次失败：不再锁定
        var gate: ThrottleGate = ThrottleGate.Allowed(0)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD + 3) {
            gate = manager.registerFailure(dbId, now)
        }
        assertTrue("开关关闭时失败不得锁定", gate is ThrottleGate.Allowed)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD + 3, gate.failureCount)

        // 预置锁定态（开关打开期间留下）在关闭后同样放行
        store.seed(dbId, UnlockThrottleRecord(failureCount = 9, lockoutUntilEpochMs = now + 600_000L))
        val seededGate = manager.gate(dbId, now)
        assertTrue("开关关闭时应忽略既有锁定截止", seededGate is ThrottleGate.Allowed)
        assertEquals(9, seededGate.failureCount)
    }

    @Test
    fun `开关重新打开后节流恢复生效`() {
        val store = FakeUnlockThrottleStore()
        val source = object : ThrottleConfigSource {
            override var current: ThrottleConfig = ThrottleConfig(enabled = false)
        }
        val manager = UnlockThrottleManager(store, source)
        val now = 50_000_000L

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD + 3) { manager.registerFailure(dbId, now) }
        assertTrue(manager.gate(dbId, now) is ThrottleGate.Allowed)

        // 开关恢复后再失败一次：按既有计数（含关闭期间累加的次数）重新进入退避锁定
        source.current = ThrottleConfig(enabled = true)
        val gate = manager.registerFailure(dbId, now)
        assertTrue("开关恢复后应重新进入锁定期", gate is ThrottleGate.Locked)
        assertTrue(manager.gate(dbId, now) is ThrottleGate.Locked)
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
        val manager = UnlockThrottleManager(store, sourceOf(ThrottleConfig(maxBackoffMs = capMs)))
        val now = 60_000_000L

        var gate: ThrottleGate = ThrottleGate.Allowed(0)
        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD + 1) { gate = manager.registerFailure(dbId, now) }
        assertTrue(gate is ThrottleGate.Locked)
        assertEquals(capMs, (gate as ThrottleGate.Locked).remainingMs)
    }

    @Test
    fun `完整性failClosed不受开关关闭影响`() {
        val store = FakeUnlockThrottleStore()
        val manager = UnlockThrottleManager(store, sourceOf(ThrottleConfig(enabled = false)))
        val now = 70_000_000L
        store.seed(
            dbId,
            UnlockThrottleRecord(failureCount = 0, lockoutUntilEpochMs = 0L, integrityIntact = false)
        )

        val gate = manager.gate(dbId, now)

        assertTrue("防篡改语义不得被用户开关旁路", gate is ThrottleGate.Locked)
        assertEquals(UnlockThrottlePolicy.MAX_BACKOFF_MS, (gate as ThrottleGate.Locked).remainingMs)
    }
}
