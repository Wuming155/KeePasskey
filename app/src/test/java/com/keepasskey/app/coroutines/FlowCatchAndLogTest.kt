package com.keepasskey.app.coroutines

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长驻 Flow 收集的失败出口（`ISSUE-P2` A8 ②）：上游抛错时留痕并结束收集，取消原样传播。
 *
 * 背景：`safeLaunch` 只兜底协程启动；收集体内部抛错时该协程终止，界面停在最后一次快照，
 * 日志里没有任何一行说明原因。`catchAndLog` 把「静默停止更新」变成「留痕停止更新」。
 */
class FlowCatchAndLogTest {

    private val logged = mutableListOf<String>()

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
    }

    private fun installSink() {
        logged.clear()
        AppLog.diagnosticSink = { _, tag, message, _ -> logged += "$tag:$message" }
    }

    @Test
    fun `上游抛错时留痕并结束收集不重抛`() = runTest {
        installSink()

        val received = mutableListOf<Int>()
        flow {
            emit(1)
            throw IllegalStateException("upstream-broke")
        }.catchAndLog("FlowTest").collect { received += it }

        assertEquals("抛错前的元素应已交付", listOf(1), received)
        assertTrue(
            "须留痕标签与异常类型：$logged",
            logged.any { it.contains("FlowTest") && it.contains("IllegalStateException") }
        )
        assertTrue(
            "文案须说明界面将停止更新：$logged",
            logged.any { it.contains("停止更新") }
        )
        assertTrue("不得输出异常 message：$logged", logged.none { it.contains("upstream-broke") })
    }

    @Test
    fun `正常流不受影响`() = runTest {
        installSink()

        val received = flowOf(1, 2, 3).catchAndLog("FlowTest").toList()

        assertEquals(listOf(1, 2, 3), received)
        assertTrue("正常路径不得留痕：$logged", logged.isEmpty())
    }

    @Test
    fun `取消原样传播且不记为失败`() = runTest {
        installSink()

        val failure = try {
            flow<Int> {
                emit(1)
                throw CancellationException("cancelled")
            }.catchAndLog("FlowTest").toList()
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertTrue("取消必须原样传播（不得被吞成正常结束）", failure != null)
        assertTrue("取消不是错误，不得留痕：$logged", logged.isEmpty())
    }
}
