package com.keepasskey.app.ui

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销动作的失败策略（`runSnackbarUndo`）：失败必须「留痕 + 告知」，取消必须原样传播。
 *
 * 背景：撤销在生产由全局 Snackbar 宿主的协程作用域执行，此前 `event.onUndo?.invoke()` 无任何
 * 保护——写库失败或 SAF 授权失效会让异常从组合期协程逃逸并杀掉进程，表现为「点撤销即闪退」。
 */
class SnackbarUndoTest {

    private val diagnostics = mutableListOf<Pair<String, Throwable>>()

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
    }

    private fun installSink() {
        AppLog.diagnosticSink = { _, tag, _, failure -> diagnostics += tag to failure }
    }

    @Test
    fun `撤销失败时留痕并告知用户`() = runTest {
        installSink()
        var notified = 0
        val failure = IllegalStateException("模拟撤销写库失败")

        runSnackbarUndo(
            onUndo = { throw failure },
            onFailure = { notified += 1 }
        )

        assertEquals("失败必须告知用户「内容未恢复」，且只告知一次", 1, notified)
        assertEquals("失败必须留痕一条", 1, diagnostics.size)
        assertEquals("AppGlobalSnackbarHost", diagnostics.single().first)
        // kotlinx 的堆栈恢复会在 withContext 边界复制异常实例，故只断言类型（类名即留痕口径）
        assertTrue(diagnostics.single().second is IllegalStateException)
    }

    @Test
    fun `撤销成功时不触发失败提示`() = runTest {
        installSink()
        var notified = 0
        var undone = false

        runSnackbarUndo(onUndo = { undone = true }, onFailure = { notified += 1 })

        assertTrue(undone)
        assertEquals(0, notified)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `取消异常原样传播且不当作撤销失败`() = runTest {
        installSink()
        var notified = 0

        val escaped = runCatching {
            runSnackbarUndo(
                onUndo = { throw CancellationException("结构化并发取消") },
                onFailure = { notified += 1 }
            )
        }.exceptionOrNull()

        assertTrue("取消必须原样重抛: $escaped", escaped is CancellationException)
        assertEquals("取消不是失败，不得提示「内容未恢复」", 0, notified)
        assertTrue(diagnostics.isEmpty())
    }
}
