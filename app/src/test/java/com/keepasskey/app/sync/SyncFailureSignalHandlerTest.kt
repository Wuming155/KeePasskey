package com.keepasskey.app.sync

import com.keepasskey.app.sync.SyncFailureNotificationAction.CANCEL
import com.keepasskey.app.sync.SyncFailureNotificationAction.POST
import com.keepasskey.sync.engine.SyncCacheEvent
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncFailureSignalHandler] 单测（`ISSUE-P2-557` ③）：
 * 元素级异常隔离——单个信号处理抛**非** `SecurityException` 时收集器不终止，
 * 后续周期仍能正常发 / 撤通知；取消则沿链重抛。
 *
 * 整改前 `signals().collect { … }` 整段无 `try/catch`，且 `post()` 只捕 `SecurityException`：
 * 任何其它 `Throwable` 逃出 `collect` 即终止唯一收集协程，宿主 `guardedScope` 只落日志不重抛
 * ⇒ 同步失败通知**永久静默**且不可自愈（`started` 已为 `true`，再 `start()` 被直接挡掉）。
 */
class SyncFailureSignalHandlerTest {

    private fun cycleError() = SyncFailureInput.CycleOutcome(SyncOutcome.Error("远端鉴权被拒"))

    @Test
    fun `post 抛非 SecurityException 时被隔离且后续周期仍能发与撤通知`() {
        val executed = mutableListOf<SyncFailureNotificationAction>()
        var failNextPost = true
        val handler = SyncFailureSignalHandler(execute = { action ->
            executed += action
            if (action == POST && failNextPost) {
                failNextPost = false
                throw IllegalStateException("模拟平台层非 SecurityException")
            }
        })

        // 周期 1：失败 ⇒ POST ⇒ 执行抛异常 ⇒ 就地隔离（收集器不终止）
        handler.onSignal(cycleError())
        assertFalse("POST 未真正上屏 ⇒ 状态须回退为未发，避免幽灵已发", handler.isPosted)

        // 周期 2：再失败 ⇒ POST 执行成功 ⇒ 通知在栏
        handler.onSignal(cycleError())
        assertTrue("隔离后后续周期必须仍能亮出通知", handler.isPosted)

        // 周期 3：成功 ⇒ CANCEL ⇒ 撤下通知
        handler.onSignal(SyncFailureInput.CycleOutcome(SyncOutcome.UpToDate))
        assertFalse("隔离后后续周期必须仍能撤下通知", handler.isPosted)

        assertEquals(listOf(POST, POST, CANCEL), executed)
    }

    @Test
    fun `引擎失败事件抛异常同样被隔离`() {
        var failNext = true
        val handler = SyncFailureSignalHandler(execute = {
            if (failNext) {
                failNext = false
                throw RuntimeException("模拟平台层异常")
            }
        })

        handler.onSignal(
            SyncFailureInput.EngineEvent(
                SyncCacheEvent.CouldntSaveToRemote(remotePath = "/vault.kdbx", cause = null)
            )
        )
        assertFalse(handler.isPosted)

        // 后续事件仍被处理（证明收集未被终止）
        handler.onSignal(
            SyncFailureInput.EngineEvent(
                SyncCacheEvent.CouldntSaveToRemote(remotePath = "/vault.kdbx", cause = null)
            )
        )
        assertTrue(handler.isPosted)
    }

    @Test
    fun `非失败的引擎事件不产生任何动作`() {
        val executed = mutableListOf<SyncFailureNotificationAction>()
        val handler = SyncFailureSignalHandler(execute = { executed += it })

        handler.onSignal(
            SyncFailureInput.EngineEvent(
                SyncCacheEvent.LoadedFromRemoteInSync(remotePath = "/vault.kdbx")
            )
        )
        assertTrue("正常同步足迹不得触发通知动作", executed.isEmpty())
    }

    @Test
    fun `取消不被隔离而是沿链重抛`() {
        val handler = SyncFailureSignalHandler(execute = { throw CancellationException("周期被取消") })

        assertThrows(CancellationException::class.java) {
            handler.onSignal(cycleError())
        }
    }
}
