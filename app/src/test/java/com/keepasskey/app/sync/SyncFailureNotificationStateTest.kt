package com.keepasskey.app.sync

import com.keepasskey.app.sync.SyncFailureNotificationAction.CANCEL
import com.keepasskey.app.sync.SyncFailureNotificationAction.NONE
import com.keepasskey.app.sync.SyncFailureNotificationAction.POST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SyncFailureNotificationState] 单测（ISSUE-P2-548 AC①）：
 * 覆盖「同一同步周期内引擎失败事件 + 结论」的**竞态**与 `posted` 的**跨线程可见性**。
 *
 * 整改前这一层**零覆盖**（`app/src/test` 只有 [SyncFailureSignalTest] 覆盖两个纯函数，
 * 收集器交互与 `posted` 标志全靠真机偶遇）：`posted` 是非 `@Volatile` 的裸 `var`，
 * 写于 `post()`、读于 `cancel()`，而两个订阅者是多线程池下的兄弟协程 ⇒
 * 鉴权失败的通知被同周期 `Offline` 结论撤掉是**不确定发生**的。
 */
class SyncFailureNotificationStateTest {

    @Test
    fun `同周期引擎失败事件后接 Error 结论只发一次且通知留存`() {
        val state = SyncFailureNotificationState()

        assertEquals("事件先到：须发出通知", POST, state.onEngineFailure())
        assertEquals("结论 Error：已处于目标态，不得重复 notify", NONE, state.onOutcome(SyncOutcome.Error("远端鉴权被拒")))

        assertTrue("AC①：鉴权/协议失败所在周期结束后通知必须在通知栏", state.isPosted)
    }

    @Test
    fun `结论先到时事件不得重复发通知`() {
        val state = SyncFailureNotificationState()

        assertEquals(POST, state.onOutcome(SyncOutcome.Error("远端鉴权被拒")))
        assertEquals("同周期事件不得把已在栏的通知再发一次", NONE, state.onEngineFailure())

        assertTrue(state.isPosted)
    }

    @Test
    fun `真断网结论撤下已发通知且不空撤第二次`() {
        val state = SyncFailureNotificationState()

        assertEquals(POST, state.onEngineFailure())
        assertEquals("AC②：真断网（Offline）必须撤下通知，不得常驻", CANCEL, state.onOutcome(SyncOutcome.Offline))
        assertEquals("已撤下后重复结论为空转，不得无谓 cancel", NONE, state.onOutcome(SyncOutcome.Offline))

        assertFalse(state.isPosted)
    }

    @Test
    fun `成功结论撤下通知后再次失败须重新发出`() {
        val state = SyncFailureNotificationState()

        assertEquals(POST, state.onOutcome(SyncOutcome.Error("上传失败")))
        assertEquals(CANCEL, state.onOutcome(SyncOutcome.UpToDate))
        assertEquals("下一个失败周期须能重新亮出通知", POST, state.onOutcome(SyncOutcome.Error("上传失败")))

        assertTrue(state.isPosted)
    }

    @Test
    fun `下发失败回退后不产生幽灵撤销`() {
        val state = SyncFailureNotificationState()

        assertEquals(POST, state.onEngineFailure())
        state.markPostFailed()
        assertFalse(state.isPosted)
        assertEquals("通知从未真正上屏 ⇒ 后续结论不得凭空 cancel 一次", NONE, state.onOutcome(SyncOutcome.Offline))
    }

    /**
     * 跨线程可见性 / 丢失更新：8 线程同时把状态推向「已发」，
     * 若 `posted` 无 happens-before 保障，将出现**多个 POST**（重复 notify）。
     */
    @Test
    fun `并发推同一目标态只产生一次动作`() {
        val state = SyncFailureNotificationState()
        assertEquals("并发前置态：未发", false, state.isPosted)

        assertEquals(1, concurrentPostCount(state))
        assertTrue(state.isPosted)
        assertEquals(1, concurrentCancelCount(state))
        assertFalse(state.isPosted)
        assertEquals(1, concurrentPostCount(state))
        assertTrue(state.isPosted)
    }

    /** 8 线程 × 500 次把状态推向「已发」，统计实际产生的 POST 次数（期望恰好 1）。 */
    private fun concurrentPostCount(state: SyncFailureNotificationState): Int {
        val counter = AtomicInteger()
        runConcurrently {
            if (state.onEngineFailure() == POST) counter.incrementAndGet()
        }
        return counter.get()
    }

    /** 8 线程 × 500 次把状态推向「已撤」，统计实际产生的 CANCEL 次数（期望恰好 1）。 */
    private fun concurrentCancelCount(state: SyncFailureNotificationState): Int {
        val counter = AtomicInteger()
        runConcurrently {
            if (state.onOutcome(SyncOutcome.Offline) == CANCEL) counter.incrementAndGet()
        }
        return counter.get()
    }

    private fun runConcurrently(threads: Int = 8, repeats: Int = 500, body: () -> Unit) {
        val barrier = CyclicBarrier(threads)
        val failures = AtomicInteger()
        val workers = (1..threads).map {
            Thread {
                try {
                    barrier.await()
                    repeat(repeats) { body() }
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals("并发压测期间不得有线程抛异常", 0, failures.get())
    }
}
