package com.keepasskey.app.data.logger

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内调试日志环形缓冲（Kp2a 调试日志系统的本地优先轻量实现）。
 * 铁律：只允许记录非敏感运行时事件（同步结果、解锁成败、状态迁移），
 * 严禁调用方把主密码、密钥、凭据等敏感明文写入本缓冲。
 *
 * ISSUE-P3-03 (43f) 接线：设置页「诊断日志」开关（`ExtendedSettings.debugLogEnabled`）
 * 经 [DiagnosticLogGate] 真实约束普通诊断事件的写入——关闭时 [log] 直接丢弃，
 * 不再出现「开关可切但日志恒记录」的假开关。**导出审计**（[audit]）走独立通道，
 * 不受该开关约束：ISSUE-P2-10 验收要求导出审计留痕可核验，不得被用户偏好静默关闭。
 */
@Singleton
class DebugLogBuffer @Inject constructor(
    private val gate: DiagnosticLogGate
) {

    /**
     * 纯 JVM 单测便捷构造：不接入偏好源，闸门恒开（保持既有测试对日志可观测性的依赖）。
     * 生产路径恒由 Hilt 注入 [DiagnosticLogModule] 提供的真实闸门。
     */
    constructor() : this(DiagnosticLogGate.alwaysOn())

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /** 普通诊断事件入口：受 [DiagnosticLogGate] 约束（关闭时整条丢弃，不占缓冲容量）。 */
    fun log(level: String, tag: String, message: String) {
        if (!gate.isEnabled()) return
        append(level, tag, message)
    }

    fun info(tag: String, message: String) = log("INFO", tag, message)

    fun warn(tag: String, message: String) = log("WARN", tag, message)

    fun error(tag: String, message: String) = log("ERROR", tag, message)

    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

    /**
     * 审计通道（ISSUE-P2-10 契约）：**不受诊断日志开关约束**。
     * 仅限导出等安全治理事件使用，消息本身仍须遵守脱敏铁律。
     */
    fun audit(tag: String, message: String) = append(AUDIT_LEVEL, tag, message)

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    fun exportText(): String = synchronized(lock) { lines.joinToString("\n") }

    private fun append(level: String, tag: String, message: String) {
        val timestamp = LocalTime.now().format(TS_FORMAT)
        val line = "[$timestamp] [$level] [$tag] $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    /**
     * 断点整改：脱敏导出——兑现设置页「导出前自动移除账号名与网址字段」的 UI 承诺。
     * 缓冲按契约只记录非敏感运行时事件，但异常/结果消息可能带出主机地址或账号，
     * 导出前统一做确定性脱敏：先整体移除 URL（内部可能嵌邮箱），再移除独立邮箱。
     */
    fun exportSanitizedText(): String = synchronized(lock) {
        lines.joinToString("\n")
            .replace(URL_PATTERN, REDACTED_URL)
            .replace(EMAIL_PATTERN, REDACTED_ACCOUNT)
    }

    companion object {
        /** 审计行级别标识（区分于 INFO/WARN，便于导出后人工核查） */
        const val AUDIT_LEVEL = "AUDIT"
        private const val MAX_LINES = 500
        private val TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        // 脱敏规则：URL 须先于邮箱处理（避免 URL 内嵌邮箱被二次匹配后残留 scheme 碎片）
        private val URL_PATTERN = Regex("https?://\\S+")
        private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private const val REDACTED_URL = "<redacted-url>"
        private const val REDACTED_ACCOUNT = "<redacted-account>"
    }
}
