package com.keepasskey.core.log

import com.keepasskey.core.result.suppressCleanupFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppLog` 诊断出口与栈帧格式化的纯 JVM 行为用例。
 *
 * 设备侧 `AppLogDeviceTest` 已锁定 release 的 logcat 形态；本用例补的是**另一条通道**：
 * 带异常的 e/w 必须把原始故障事件交给应用内诊断桥（稳定版可导出日志据此拿到调用栈），
 * 纯文案事件不得占用该出口，且出口自身失败不得影响业务故障路径。
 */
class AppLogDiagnosticSinkTest {

    private data class Event(
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable
    )

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
        AppLog.diagnosticLineSink = null
        AppLog.debugEnabled = false
    }

    @Test
    fun `带异常的 e 与 w 经出口转发原始事件`() {
        val events = mutableListOf<Event>()
        AppLog.diagnosticSink = { level, tag, message, throwable ->
            events += Event(level, tag, message, throwable)
        }
        val error = IllegalStateException("raw")
        val warn = IllegalArgumentException("warn")

        AppLog.e("Fault", "错误事件", error)
        AppLog.w("Fault", "告警事件", warn)

        assertEquals(listOf("ERROR", "WARN"), events.map { it.level })
        assertEquals("错误事件", events[0].message)
        assertEquals("Fault", events[0].tag)
        assertSame(error, events[0].throwable)
        assertSame(warn, events[1].throwable)
    }

    @Test
    fun `不带异常的 e 与 w 不占用诊断出口`() {
        val events = mutableListOf<Event>()
        AppLog.diagnosticSink = { level, tag, message, throwable ->
            events += Event(level, tag, message, throwable)
        }

        AppLog.e("Fault", "纯文案错误")
        AppLog.w("Fault", "纯文案告警")
        AppLog.i("Fault", "信息")

        assertTrue("纯文案事件由业务调用点自行留痕，不得重复灌入缓冲", events.isEmpty())
    }

    @Test
    fun `诊断文本保留类型与栈帧且不透出异常原文`() {
        val cause = IllegalArgumentException("cause-marker")
        val throwable = IllegalStateException("raw-marker", cause)

        val text = diagnosticText("调用方信息", throwable, frameBudget = 4, maxCauses = 2)

        assertTrue(text.contains("调用方信息"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(text.contains("\tat "))
        assertFalse("顶层异常 message 不得进入日志", text.contains("raw-marker"))
        assertFalse("cause message 不得进入日志", text.contains("cause-marker"))
        assertTrue("超出帧预算须有截断标记", text.contains("more"))
    }

    @Test
    fun `诊断出口自身抛异常不影响业务调用`() {
        AppLog.diagnosticSink = { _, _, _, _ -> throw RuntimeException("sink failure") }

        AppLog.e("Fault", "错误事件", IllegalStateException("raw"))
        AppLog.w("Fault", "告警事件", IllegalArgumentException("raw"))
    }

    @Test
    fun `纯文案事件经行出口转发且不重复占用异常出口`() {
        val lines = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        AppLog.diagnosticLineSink = { _, tag, message -> lines += "$tag:$message" }
        AppLog.diagnosticSink = { _, tag, message, _ -> diagnostics += "$tag:$message" }

        AppLog.i("Fault", "信息")
        AppLog.e("Fault", "错误事件", IllegalStateException("raw"))

        assertEquals("纯文案只走行出口", listOf("Fault:信息"), lines)
        assertEquals("带异常事件只走诊断出口", listOf("Fault:错误事件"), diagnostics)
    }

    @Test
    fun `suppressed 异常输出类型与定位帧且不透出原文`() {
        val primary = IllegalStateException("primary-marker")
        primary.addSuppressed(IllegalStateException("cleanup-marker"))

        val text = diagnosticText("清理失败", primary, frameBudget = 4, maxCauses = 2)

        assertTrue(
            "清理失败须可定位到具体方法：$text",
            text.contains("\tSuppressed: java.lang.IllegalStateException")
        )
        assertTrue("suppressed 也要有定位帧", text.contains("\tat "))
        assertFalse("suppressed message 不得外传", text.contains("cleanup-marker"))
        assertFalse(text.contains("primary-marker"))
    }

    @Test
    fun `suppressed 数量有界并标注截断`() {
        val primary = IllegalStateException("primary")
        repeat(MAX_DIAGNOSTIC_SUPPRESSED + 2) { index ->
            primary.addSuppressed(RuntimeException("cleanup-$index"))
        }

        val text = diagnosticText("清理失败", primary)

        assertEquals(
            MAX_DIAGNOSTIC_SUPPRESSED,
            Regex("\\tSuppressed: ").findAll(text).count()
        )
        assertTrue("超出上限须显式标注", text.contains("... suppressed truncated"))
    }

    @Test
    fun `清理失败经统一挂载入口后可在诊断文本中定位`() {
        val primary = IllegalStateException("primary")

        suppressCleanupFailure(primary) {
            throw IllegalStateException("cleanup-secret")
        }

        val text = diagnosticText("敏感缓冲擦除失败", primary)

        assertTrue(
            "归档须能定位到清理方法：$text",
            text.contains("\tSuppressed: java.lang.IllegalStateException")
        )
        assertTrue(text.contains("\tat "))
        assertFalse("清理异常的 message 同样不外传", text.contains("cleanup-secret"))
    }
}
