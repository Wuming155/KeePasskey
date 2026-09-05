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

    companion object {
        private const val MAX_LINES = 500
        private val TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
