package com.keepasskey.app.coroutines

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.log.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [safeLaunch] 在**真实 Android 运行时**上的出口兜底（真机 / 隔离 AVD）。
 *
 * JVM 用例（`SafeLaunchTest`）只能证明 kotlinx 在 JVM 上的行为；`Dispatchers.Main` 在 Android 上
 * 走 `HandlerContext` + `AndroidExceptionPreHandler`，逃逸异常会直接进 `RuntimeInit` 的
 * 「uncaught exception → 杀进程」路径。本用例在 **Main.immediate** 上注入三类抛错，
 * 断言异常被拦截、留痕到达诊断出口、且仪表进程仍然存活（若逃逸，进程会在此用例内被系统杀掉，
 * 整个 instrumentation 失败而不是用例失败）。
 */
@RunWith(AndroidJUnit4::class)
class SafeLaunchMainThreadDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val diagnostics = AtomicInteger()
    private var previousSink: com.keepasskey.core.log.DiagnosticEventSink? = null

    @After
    fun tearDown() {
        AppLog.diagnosticSink = previousSink
    }

    private fun installSink() {
        previousSink = AppLog.diagnosticSink
        AppLog.diagnosticSink = { _, _, _, _ -> diagnostics.incrementAndGet() }
    }

    @Test
    fun testSafeLaunchInterceptsDirectThrowOnMainThread() {
        installSink()
        val latch = CountDownLatch(1)
        val reported = AtomicReference<Throwable?>()

        instrumentation.runOnMainSync {
            CoroutineScope(Dispatchers.Main.immediate).safeLaunch(
                tag = TAG,
                onError = {
                    reported.set(it)
                    latch.countDown()
                }
            ) {
                throw IllegalStateException("device injection: direct")
            }
        }

        assertTrue("主线程协程异常必须交给 onError", latch.await(5, TimeUnit.SECONDS))
        assertTrue("上报的异常类型必须保持: ${reported.get()}", reported.get() is IllegalStateException)
        assertEquals("必须留痕一条", 1, diagnostics.get())
        assertMainThreadAlive()
    }

    @Test
    fun testSafeLaunchInterceptsNestedChildThrowOnMainThread() {
        installSink()
        val latch = CountDownLatch(1)

        instrumentation.runOnMainSync {
            CoroutineScope(Dispatchers.Main.immediate).safeLaunch(tag = TAG) {
                // 协程体内再 launch：不经过本函数的 try，只能靠启动上下文里的处理器兜底
                launch {
                    try {
                        throw IllegalStateException("device injection: nested")
                    } finally {
                        latch.countDown()
                    }
                }
            }
        }

        assertTrue("子协程必须真的被执行", latch.await(5, TimeUnit.SECONDS))
        assertTrue("子协程异常必须留痕（未安装处理器时它会杀掉进程）", diagnostics.get() >= 1)
        assertMainThreadAlive()
    }

    @Test
    fun testSafeLaunchSurvivesFailureInsideFallback() {
        installSink()
        val latch = CountDownLatch(1)

        instrumentation.runOnMainSync {
            CoroutineScope(Dispatchers.Main.immediate).safeLaunch(
                tag = TAG,
                onError = {
                    latch.countDown()
                    throw IllegalStateException("device injection: fallback")
                }
            ) {
                throw IllegalArgumentException("device injection: original")
            }
        }

        assertTrue("兜底回调必须被调用", latch.await(5, TimeUnit.SECONDS))
        assertTrue("原始异常与兜底自身异常都要留痕", diagnostics.get() >= 2)
        assertMainThreadAlive()
    }

    /** 逃逸的异常会先杀掉进程；能回到主线程并拿到返回值，即证明进程仍存活且主线程可用。 */
    private fun assertMainThreadAlive() {
        val marker = AtomicReference<String?>()
        instrumentation.runOnMainSync { marker.set("alive") }
        assertEquals("主线程必须仍可调度（进程存活）", "alive", marker.get())
    }

    private companion object {
        const val TAG = "SafeLaunchDeviceTest"
    }
}
