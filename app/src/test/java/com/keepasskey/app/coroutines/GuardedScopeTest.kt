package com.keepasskey.app.coroutines

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用级作用域的异常兜底：协程根异常被拦截并留痕，兄弟协程与进程不受影响。
 */
class GuardedScopeTest {

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
    }

    @Test
    fun `协程根异常被拦截留痕且兄弟协程继续运行`() {
        val logged = mutableListOf<String>()
        AppLog.diagnosticSink = { _, tag, message, _ -> logged += "$tag:$message" }
        val scope = guardedScope(UnconfinedTestDispatcher(), "GuardedScopeTest")
        try {
            var siblingRan = false

            scope.launch { throw IllegalStateException("boom") }
            scope.launch { siblingRan = true }

            assertTrue(
                "拦截日志须带标签与异常类型：$logged",
                logged.any {
                    it.contains("GuardedScopeTest") && it.contains("IllegalStateException")
                }
            )
            assertTrue("SupervisorJob 隔离：兄弟协程仍应执行", siblingRan)
        } finally {
            scope.cancel()
        }
    }
}
