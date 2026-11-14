package com.keepasskey.app.data.logger

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.core.log.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳定版诊断桥的端到端（纯 JVM）契约：release 里 `AppLog.e/w` 的异常必须变成用户可导出的
 * 脱敏栈帧，且不得把异常原文或外部标识带回缓冲；「详细日志模式」要真的改变栈帧详细度；
 * 用户关闭「诊断日志」后普通故障事件仍受闸门约束（审计通道不受影响）。
 */
class DiagnosticLogBridgeTest {

    private val store = ExtendedSettingsStore(InMemorySharedPreferences().context())

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
        AppLog.diagnosticLineSink = null
        AppLog.diagnosticAuditSink = null
        AppLog.debugEnabled = false
    }

    @Test
    fun `异常经桥写入缓冲且不含异常原文与外部标识`() {
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, store).install()

        AppLog.e(
            "Sync",
            "同步失败: https://dav.example.com/vault.kdbx",
            IllegalStateException("secret-user@example.com")
        )

        val text = buffer.exportText()
        assertTrue("应写入进程诊断头：$text", text.contains("进程诊断头"))
        assertTrue("应保留异常类型：$text", text.contains("java.lang.IllegalStateException"))
        assertTrue("应保留可定位栈帧：$text", text.contains("\tat "))
        assertTrue("URL 应在写入时脱敏：$text", text.contains("<redacted-url>"))
        assertFalse("URL 主机不得残留", text.contains("dav.example.com"))
        assertFalse("异常 message 不得进入缓冲", text.contains("secret-user@example.com"))
    }

    @Test
    fun `包名与栈帧不被通用主机名规则误伤`() {
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, store).install()

        AppLog.e("T", "故障", deepException())

        assertTrue(
            "栈帧需保留完整包名以供发布 mapping 还原：${buffer.exportText()}",
            buffer.exportText().contains("com.keepasskey.")
        )
    }

    @Test
    fun `详细模式开启时保留更多栈帧`() {
        val brief = captureFrameCount(verbose = false)
        val detailed = captureFrameCount(verbose = true)

        assertTrue(
            "详细模式应保留更多栈帧（精简=$brief，详细=$detailed）",
            detailed > brief
        )
    }

    @Test
    fun `诊断日志关闭时普通故障事件不进缓冲而进程头仍留痕`() {
        store.save(ExtendedSettings(debugLogEnabled = false))
        val buffer = DebugLogBuffer(DiagnosticLogModule.provideDiagnosticLogGate(store))
        DiagnosticLogBridge(buffer, store).install()

        AppLog.e("T", "故障", IllegalStateException("raw"))

        assertTrue(
            "关闭后不得记录普通诊断事件：${buffer.exportText()}",
            buffer.snapshot().none { it.contains("[ERROR]") }
        )
        assertTrue("审计通道的进程头不受用户开关约束", buffer.exportText().contains("进程诊断头"))
    }

    @Test
    fun `诊断日志关闭时治理审计仍留痕`() {
        store.save(ExtendedSettings(debugLogEnabled = false))
        val buffer = DebugLogBuffer(DiagnosticLogModule.provideDiagnosticLogGate(store))
        DiagnosticLogBridge(buffer, store).install()

        AppLog.i("T", "普通事件应被丢弃")
        AppLog.audit("Export", "导出审计: 类型=诊断日志, 目标=下载, 结果=成功")

        val text = buffer.exportText()
        assertTrue("普通事件须随开关一起消失：$text", !text.contains("普通事件应被丢弃"))
        assertTrue("治理审计不得随开关消失：$text", text.contains("导出审计"))
        assertTrue("审计行须带 AUDIT 级别：$text", text.contains("[AUDIT]"))
    }

    @Test
    fun `审计通道与普通通道互不回环`() {
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, store).install()

        AppLog.audit(DebugLogBuffer.LOGCAT_TAG, "回显行不得再进缓冲")

        assertTrue(
            "KpLog 回显的审计行不得二次进入缓冲：${buffer.exportText()}",
            buffer.snapshot().none { it.contains("回显行不得再进缓冲") }
        )
    }

    @Test
    fun `缓冲回显的 KpLog 行不会再次进入缓冲`() {
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, store).install()
        val before = buffer.snapshot().size

        AppLog.i(DebugLogBuffer.LOGCAT_TAG, "[2026-11-14] [10:00:00.000] [main] [INFO] [X] echo")

        assertEquals(
            "KpLog 是缓冲回显 logcat 的 tag，必须过滤以阻断「缓冲 → logcat → 缓冲」回环",
            before,
            buffer.snapshot().size
        )
    }

    private fun captureFrameCount(verbose: Boolean): Int {
        store.save(ExtendedSettings(verboseSyncLog = verbose))
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, store).install()

        AppLog.e("T", "故障", deepException())

        val errorLine = buffer.snapshot().last { it.contains("[ERROR]") }
        return Regex("\tat ").findAll(errorLine).count()
    }

    /** 制造足够深的栈，使精简 / 详细两档帧预算产生可观测差异 */
    private fun deepException(depth: Int = 20): Throwable =
        if (depth == 0) IllegalStateException("raw") else deepException(depth - 1)
}
