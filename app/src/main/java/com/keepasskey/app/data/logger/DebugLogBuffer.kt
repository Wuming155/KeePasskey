package com.keepasskey.app.data.logger

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内调试日志环形缓冲（Kp2a 调试日志系统的本地优先轻量实现）。
 * 铁律：只允许记录非敏感运行时事件（同步结果、解锁成败、状态迁移），
 * 严禁调用方把主密码、密钥、凭据等敏感明文写入本缓冲。
 */
@Singleton
class DebugLogBuffer @Inject constructor() {

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    fun log(level: String, tag: String, message: String) {
        val timestamp = LocalTime.now().format(TS_FORMAT)
        val line = "[$timestamp] [$level] [$tag] $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun info(tag: String, message: String) = log("INFO", tag, message)

    fun warn(tag: String, message: String) = log("WARN", tag, message)

    fun error(tag: String, message: String) = log("ERROR", tag, message)

    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }

    fun exportText(): String = synchronized(lock) { lines.joinToString("\n") }

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
        private const val MAX_LINES = 500
        private val TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        // 脱敏规则：URL 须先于邮箱处理（避免 URL 内嵌邮箱被二次匹配后残留 scheme 碎片）
        private val URL_PATTERN = Regex("https?://\\S+")
        private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private const val REDACTED_URL = "<redacted-url>"
        private const val REDACTED_ACCOUNT = "<redacted-account>"
    }
}
