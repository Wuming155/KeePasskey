package com.keepasskey.app.data.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `attachBaseContext` 阶段早期崩溃行的格式边界：可检索、有界、不含异常原文。 */
class EarlyCrashHandlerTest {

    @Test
    fun `早期崩溃行带类型与有界栈帧且不含异常原文`() {
        val error = IllegalStateException("raw-secret")

        val line = EarlyCrashHandler.buildLine(
            date = "2026-11-14",
            time = "10:00:00.000",
            threadName = "main",
            error = error
        )

        assertTrue(
            "归档行须可直接按 [Crash] 检索：$line",
            line.startsWith("[2026-11-14] [10:00:00.000] [main] [ERROR] [Crash]")
        )
        assertTrue("须带异常类型", line.contains("java.lang.IllegalStateException"))
        assertTrue("须带定位栈帧", line.contains("\tat "))
        assertFalse("异常原文不得进入归档", line.contains("raw-secret"))
        assertTrue("栈帧须有界", line.lines().size <= 10)
    }
}
