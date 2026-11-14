package com.keepasskey.app.coroutines

import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [safeLaunch] 的契约：UI 协程出口的异常被拦截并留痕，取消原样传播，兜底自身抛错不再逃逸。
 *
 * 这些用例同时是「进程存活」的 JVM 侧证据：逃逸的异常会落到测试线程的未捕获处理器
 * （JUnit 记失败 / JVM 打栈），因此「用例通过」即「没有异常越过协程根」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeLaunchTest {

    private data class DiagnosticEvent(val level: String, val tag: String, val failure: Throwable)

    private val events = mutableListOf<DiagnosticEvent>()

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
        MainDispatcherGuard.tearDown()
    }

    private fun installSink() {
        AppLog.diagnosticSink = { level, tag, _, failure -> events += DiagnosticEvent(level, tag, failure) }
    }

    @Test
    fun `协程体抛异常时被拦截并写入诊断出口`() = runTest {
        installSink()
        val scope = MainDispatcherGuard.trackScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var reported: Throwable? = null

        val job = scope.safeLaunch(
            tag = "SafeLaunchUnderTest",
            onError = { reported = it }
        ) {
            throw IllegalStateException("模拟撤销写库失败")
        }
        advanceUntilIdle()

        assertTrue("异常必须交给 onError，调用点才有复位机会", reported is IllegalStateException)
        assertFalse("兜底不是取消：任务应正常完成而非取消", job.isCancelled)
        assertEquals("留痕必须恰好一条", 1, events.size)
        assertEquals("ERROR", events.single().level)
        assertEquals("标签必须来自调用点，归因才可用", "SafeLaunchUnderTest", events.single().tag)
        assertSame(reported, events.single().failure)
    }

    @Test
    fun `取消异常原样重抛且不算失败`() = runTest {
        installSink()
        val scope = MainDispatcherGuard.trackScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var reported: Throwable? = null

        val job = scope.safeLaunch(tag = "SafeLaunchUnderTest", onError = { reported = it }) {
            throw CancellationException("结构化并发取消")
        }
        advanceUntilIdle()

        assertTrue("取消必须让 Job 处于取消态", job.isCancelled)
        assertNull("取消不是错误，不得上报给 onError", reported)
        assertTrue("取消不得写入故障留痕", events.isEmpty())
    }

    @Test
    fun `兜底回调自身抛异常不会二次逃逸`() = runTest {
        installSink()
        val scope = MainDispatcherGuard.trackScope(CoroutineScope(StandardTestDispatcher(testScheduler)))

        scope.safeLaunch(
            tag = "SafeLaunchUnderTest",
            onError = { throw IllegalStateException("复位本身失败") }
        ) {
            throw IllegalArgumentException("原始失败")
        }
        advanceUntilIdle()

        assertEquals("两次失败各留一条：原始异常 + 兜底自身异常", 2, events.size)
        assertTrue(events[0].failure is IllegalArgumentException)
        assertTrue("兜底自身异常同样要留痕，否则复位失效无从发现", events[1].failure is IllegalStateException)
    }

    @Test
    fun `协程体内嵌套启动的子协程异常不会逃逸`() = runTest {
        installSink()
        val scope = MainDispatcherGuard.trackScope(CoroutineScope(StandardTestDispatcher(testScheduler)))

        scope.safeLaunch(tag = "SafeLaunchUnderTest") {
            launch { throw IllegalStateException("协程体内再启动") }
        }
        advanceUntilIdle()

        assertTrue(
            "子协程异常必须经继承的处理器留痕（未安装时它会直达线程默认处理器杀掉进程）",
            events.isNotEmpty() && events.first().failure is IllegalStateException
        )
    }

    @Test
    fun `调用点追加的上下文被合入协程`() = runTest {
        val scope = MainDispatcherGuard.trackScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        var seen: kotlin.coroutines.CoroutineContext? = null

        scope.safeLaunch(tag = "SafeLaunchUnderTest", context = CoroutineName("explicit-context")) {
            seen = kotlin.coroutines.coroutineContext
        }
        advanceUntilIdle()

        assertEquals(
            "调用点追加的上下文元素必须生效（否则 Dispatchers.IO 之类的指定会被静默丢弃）",
            "explicit-context",
            seen?.get(CoroutineName)?.name
        )
    }
}
