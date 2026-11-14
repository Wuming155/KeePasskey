package com.keepasskey.app.testutil

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.log.diagnosticText

/**
 * 把 `AppLog` 的三条诊断出口接到一个 [DebugLogBuffer]，模拟 `DiagnosticLogBridge` 的生产装配。
 *
 * 为什么需要它：业务代码记录日志已统一经 `AppLog`（`i/w/e`、`audit`、`verbose`），由
 * `DiagnosticLogBridge` 在 `Application.onCreate` 把出口接到进程级缓冲。纯 JVM 用例没有
 * Application，出口恒为 null——于是「业务记了日志」在用例里看不到，断言缓冲内容会拿到空列表。
 *
 * 本函数只装配出口、不做额外脱敏（缓冲的 `append` 自身已脱敏），也不安装磁盘归档：
 * 需要归档的用例请自行 `DiagnosticLogHistory.configure`。
 *
 * 用法：用例 `@Before` 调用 [installAppLogBridge]，`@After` 调用 [resetAppLogBridge]。
 */
fun installAppLogBridge(buffer: DebugLogBuffer) {
    AppLog.diagnosticSink = { level, tag, message, throwable ->
        if (tag != DebugLogBuffer.LOGCAT_TAG) {
            buffer.logFromAppLog(level, tag, diagnosticText(message, throwable))
        }
    }
    AppLog.diagnosticLineSink = { level, tag, message ->
        if (tag != DebugLogBuffer.LOGCAT_TAG) buffer.logFromAppLog(level, tag, message)
    }
    AppLog.diagnosticAuditSink = { tag, message ->
        if (tag != DebugLogBuffer.LOGCAT_TAG) buffer.audit(tag, message)
    }
    AppLog.diagnosticVerboseSink = { tag, message ->
        if (tag != DebugLogBuffer.LOGCAT_TAG) buffer.logFromAppLog("DEBUG", tag, message)
    }
}

/** 拆除 [installAppLogBridge] 装配的出口（用例 `@After` 调用，避免跨用例串味）。 */
fun resetAppLogBridge() {
    AppLog.diagnosticSink = null
    AppLog.diagnosticLineSink = null
    AppLog.diagnosticAuditSink = null
    AppLog.diagnosticVerboseSink = null
}
