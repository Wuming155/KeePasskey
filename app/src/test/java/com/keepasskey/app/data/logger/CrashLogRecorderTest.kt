package com.keepasskey.app.data.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程级未捕获异常留痕：同步写入归档、原样转交前处理器、重复安装幂等、诊断关闭时不记录。
 */
class CrashLogRecorderTest {

    @Test
    fun `未捕获异常同步留痕并原样转交前处理器`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> forwarded = true }
        try {
            val buffer = DebugLogBuffer()
            CrashLogRecorder(buffer).install()

            val handler = requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("raw-secret"))

            assertTrue("崩溃语义不变：必须转交安装前的处理器", forwarded)
            val text = buffer.exportText()
            assertTrue("须留下可检索的崩溃行：$text", text.contains("[ERROR] [Crash]"))
            assertTrue(text.contains("java.lang.IllegalStateException"))
            assertTrue("须带定位帧", text.contains("\tat "))
            assertFalse("异常原文不得进入归档", text.contains("raw-secret"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `重复安装只转交一次且诊断关闭时不记录`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> forwarded++ }
        try {
            val buffer = DebugLogBuffer(DiagnosticLogGate.alwaysOff())
            val recorder = CrashLogRecorder(buffer)
            recorder.install()
            recorder.install()

            requireNotNull(Thread.getDefaultUncaughtExceptionHandler())
                .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

            assertEquals("安装幂等：前处理器只应被调用一次", 1, forwarded)
            assertTrue("用户关闭诊断日志时不记录崩溃", buffer.snapshot().isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
