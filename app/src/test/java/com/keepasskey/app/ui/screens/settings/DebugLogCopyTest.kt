package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.logger.DiagnosticLogHistory
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.testutil.installAppLogBridge
import com.keepasskey.app.testutil.resetAppLogBridge
import com.keepasskey.app.ui.model.StringsProvider
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志「复制到剪贴板」的行为用例。
 *
 * 验收面：复制与导出共用同一份脱敏报告；空缓冲不写空剪贴板；通道缺失 / 写入异常都如实
 * 报失败（ISSUE-P2-353 AC① 口径）；出域动作留审计；超长文本按行裁剪且保留环境头。
 */
class DebugLogCopyTest {

    private val strings = StringsProvider { id, args ->
        when (id) {
            R.string.debug_report_title -> "REPORT-TITLE"
            R.string.debug_report_env -> "REPORT-ENV"
            R.string.debug_report_state -> "REPORT-STATE ${args.joinToString("/")}"
            R.string.debug_copy_truncated -> "TRUNCATED"
            R.string.debug_clip_label -> "CLIP-LABEL"
            else -> "s$id"
        }
    }

    @After
    fun tearDown() {
        resetAppLogBridge()
        // 归档是进程级单例：排空删除任务，避免临时目录里的记录影响后续用例
        DiagnosticLogHistory.clear()
        DiagnosticLogHistory.snapshotForExport()
    }

    @Test
    fun `复制写入与导出同源的脱敏报告并回报成功`() {
        val buffer = DebugLogBuffer()
        installAppLogBridge(buffer)
        buffer.warn("Sync", "同步失败: https://dav.example.com/vault.kdbx")
        val expectedBody = buffer.exportSanitizedText()
        val clipboard = FakeClipboard()
        val controller = controller(buffer, clipboard)

        controller.copyDebugLogs()

        val text = clipboard.lastText?.toString().orEmpty()
        assertTrue("报告头须含标题：$text", text.contains("REPORT-TITLE"))
        assertTrue("报告头须含环境摘要：$text", text.contains("REPORT-ENV"))
        assertTrue("报告头须含记录状态：$text", text.contains("REPORT-STATE"))
        assertTrue("正文须为缓冲日志：$text", text.contains("[WARN] [Sync]"))
        assertTrue("导出与复制共用脱敏正文", text.endsWith(expectedBody))
        assertTrue(text.contains("<redacted-url>"))
        assertFalse("导出正文不得残留主机", text.contains("dav.example.com"))
        assertEquals("CLIP-LABEL", clipboard.lastLabel?.toString())
        assertEquals(R.string.debug_copy_done, controller.debugExportFeedback.value?.resId)
        assertTrue("成功出域须留审计", buffer.exportText().contains("日志复制: 结果=成功"))
    }

    @Test
    fun `空缓冲不写剪贴板并如实提示`() {
        val clipboard = FakeClipboard()
        val controller = controller(DebugLogBuffer(), clipboard)

        controller.copyDebugLogs()

        assertNull(clipboard.lastText)
        assertEquals(R.string.debug_copy_empty, controller.debugExportFeedback.value?.resId)
        assertTrue(controller.debugExportFeedback.value?.isError == true)
    }

    @Test
    fun `通道缺失或写入失败都如实报失败且留审计`() {
        listOf(null, FakeClipboard(fail = true)).forEach { clipboard ->
            val buffer = DebugLogBuffer()
            installAppLogBridge(buffer)
        installAppLogBridge(buffer)
            buffer.info("T", "事件")
            val controller = controller(buffer, clipboard)

            controller.copyDebugLogs()

            assertEquals(
                R.string.clipboard_copy_failed,
                controller.debugExportFeedback.value?.resId
            )
            assertTrue("失败须带类型化错误标记", controller.debugExportFeedback.value?.isError == true)
            assertTrue("失败同样留审计", buffer.exportText().contains("日志复制: 结果=失败"))
        }
    }

    @Test
    fun `超长报告复制时保留环境头与最近记录并标注截断`() {
        val body = (1..20_000).joinToString("\n") { "line-$it: " + "x".repeat(20) }
        val report = "REPORT-TITLE\nREPORT-ENV\nREPORT-STATE\n------------------------------\n$body"

        val clipped = DiagnosticLogReport.forClipboard(report, strings)

        assertTrue(clipped.length <= DiagnosticLogReport.MAX_CLIP_CHARS)
        assertTrue("环境头须保留", clipped.startsWith("REPORT-TITLE"))
        assertTrue("截断须显式提示", clipped.contains("TRUNCATED"))
        assertTrue("应保留最新记录", clipped.contains("line-20000:"))
        assertFalse("最早记录应被裁掉", clipped.contains("line-1: "))
    }

    @Test
    fun `短报告原样复制不附加截断提示`() {
        val report = DiagnosticLogReport.build(
            DebugLogBuffer().also { it.info("T", "事件") },
            strings
        )

        val clipped = DiagnosticLogReport.forClipboard(report, strings)

        assertEquals(report, clipped)
        assertFalse(clipped.contains("TRUNCATED"))
    }

    @Test
    fun `报告头行数与时间范围取自正文同一份快照`() {
        val buffer = DebugLogBuffer()
        installAppLogBridge(buffer)
        buffer.info("T", "first-entry")
        buffer.warn("T", "second-entry")
        val clipboard = FakeClipboard()
        val controller = controller(buffer, clipboard)

        controller.copyDebugLogs()

        val text = clipboard.lastText?.toString().orEmpty()
        assertTrue("报告头须给出与正文一致的行数：$text", text.contains("REPORT-STATE 2/2000/0/"))
        val body = text.substringAfter("------------------------------\n", "")
        assertEquals("正文行数应与报告头一致", 2, body.lines().size)
        assertTrue(body.lines().all { it.startsWith("[") })
    }

    @Test
    fun `内存为空但磁盘归档有记录时可正常复制`() {
        val dir = Files.createTempDirectory("diagnostic-copy").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        DiagnosticLogHistory.configure(dir, 3)
        DiagnosticLogHistory.appendSync(
            "[$today] [09:00:00.000] [main] [INFO] [Archive] archived-entry"
        )

        val clipboard = FakeClipboard()
        // 模拟进程重启：内存缓冲为空，只有上一条进程写入的归档
        val controller = controller(DebugLogBuffer(), clipboard)

        controller.copyDebugLogs()

        val text = clipboard.lastText?.toString().orEmpty()
        assertTrue("归档正文应可复制：$text", text.contains("archived-entry"))
        assertTrue("报告头须反映归档行数：$text", text.contains("REPORT-STATE 1/2000/0/"))
        assertEquals(R.string.debug_copy_done, controller.debugExportFeedback.value?.resId)
    }

    @Test
    fun `清除诊断日志后归档保留审计行且正文已删除`() {
        val dir = Files.createTempDirectory("diagnostic-clear").toFile()
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        DiagnosticLogHistory.configure(dir, 3)
        val buffer = DebugLogBuffer()
        installAppLogBridge(buffer)
        buffer.info("T", "cleared-entry")
        // 等异步追加落盘，确保待清除的正文确实已经在归档里
        DiagnosticLogHistory.snapshotForExport()

        SettingsDebugLogActions(buffer).clearDebugLogs()

        val archived = DiagnosticLogHistory.snapshotForExport().orEmpty()
        assertFalse("用户清除后正文必须删除：$archived", archived.contains("cleared-entry"))
        assertTrue("清除动作须留下可核查的审计行：$archived", archived.contains("日志清除"))
    }

    @Test
    fun `导出流不可得时留警告并返回失败`() {
        val buffer = DebugLogBuffer()
        installAppLogBridge(buffer)
        buffer.info("T", "事件")
        val controller = controller(buffer, FakeClipboard())

        val written = controller.writeDiagnosticReport { null }

        assertFalse(written)
        assertTrue(
            "导出失败须可自证：${buffer.exportText()}",
            buffer.exportText().contains("SAF 目标不可写")
        )
    }

    @Test
    fun `导出报告写入注入的流`() {
        val buffer = DebugLogBuffer()
        installAppLogBridge(buffer)
        buffer.info("T", "事件")
        val controller = controller(buffer, FakeClipboard())
        val out = ByteArrayOutputStream()

        val written = controller.writeDiagnosticReport { out }

        assertTrue(written)
        val report = out.toString(Charsets.UTF_8.name())
        assertTrue("须含报告头：$report", report.contains("REPORT-TITLE"))
        assertTrue("须含脱敏正文：$report", report.contains("[INFO] [T] 事件"))
    }

    private fun controller(
        buffer: DebugLogBuffer,
        clipboard: ClipboardSecurityChannel?
    ): SettingsExportController = SettingsExportController(
        vaultRepository = FakeVaultRepository(),
        debugLogBuffer = buffer,
        appContext = null,
        strings = strings,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clipboard = clipboard,
        // 复制已移入 IO：测试用 Unconfined 同步执行，保持「调用后即可断言」的既有形态
        ioDispatcher = Dispatchers.Unconfined
    )

    private class FakeClipboard(private val fail: Boolean = false) : ClipboardSecurityChannel {
        var lastLabel: CharSequence? = null
        var lastText: CharSequence? = null

        override fun copySensitiveText(
            label: CharSequence,
            text: CharSequence,
            customTimeoutSeconds: Int?
        ) = Unit

        override fun copySensitiveChars(
            label: CharSequence,
            chars: CharArray,
            customTimeoutSeconds: Int?
        ) = Unit

        override fun copyPlainText(label: CharSequence, text: CharSequence) {
            if (fail) throw IllegalStateException("clipboard unavailable")
            lastLabel = label
            lastText = text
        }
    }
}
