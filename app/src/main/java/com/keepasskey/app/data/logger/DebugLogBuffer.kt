package com.keepasskey.app.data.logger

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Singleton

/**
 * 进程内诊断日志环形缓冲（Kp2a 调试日志系统的本地优先轻量实现）。
 *
 * 稳定版与调试版共用本缓冲：用户遇到故障后必须能导出日志向开发者反馈，故 release 不再
 * 恒关。**所有行在写入时经 [DiagnosticLogRedactor] 脱敏**，缓冲、设置页预览、logcat 直出
 * 与导出文件同源，均不含 URL、账号、主机、库/密钥文件名等标识；导出边界再脱敏一次。
 * 缓冲只驻留内存、进程结束即消失；logcat 直出写系统日志缓冲（不写应用文件），落盘只发生在
 * 用户主动选择导出位置时。
 *
 * 铁律：调用方只允许写入非敏感运行时事件（同步结果、解锁成败、状态迁移），且只留异常
 * **类型**、布尔判定与计数，绝不写入主密码、密钥、用户字段、库别名或异常 message。
 * 正则无法识别任意自然语言中的敏感内容，源头卫生是第一道防线。
 *
 * ISSUE-P3-03 (43f) 接线：设置页「诊断日志」开关（`ExtendedSettings.debugLogEnabled`）
 * 经 [DiagnosticLogGate] 真实约束普通诊断事件的写入——关闭时 [log] 直接丢弃，
 * 不再出现「开关可切但日志恒记录」的假开关。**导出审计**（[audit]）走独立通道，
 * 不受该开关约束：ISSUE-P2-10 验收要求导出审计留痕可核验，不得被用户偏好静默关闭。
 *
 * §411 取证桥：生产实例经 [DiagnosticLogModule] 提供（logcat 直出开启）；构造不再 @Inject——
 * 同类型同作用的 @Provides 与 @Inject 构造是 Hilt 重复绑定错误，且 Boolean 开关无法走构造注入。
 */
@Singleton
class DebugLogBuffer(
    private val gate: DiagnosticLogGate
) {

    /**
     * 纯 JVM 单测便捷构造：不接入偏好源，闸门恒开（保持既有测试对日志可观测性的依赖）。
     * 生产路径恒由 Hilt 注入 [DiagnosticLogModule] 提供的真实闸门。
     */
    constructor() : this(DiagnosticLogGate.alwaysOn())

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /**
     * §411 取证桥：logcat 直出开关（生产 DI 恒 true；纯 JVM 单测构造恒 false——
     * 平台 Log 类在 JVM 不存在）。输出内容与缓冲行**同源同约束**（先过诊断闸门，
     * 写入时已脱敏），使真机取证可直接 `adb logcat -s KpLog`。
     */
    internal var logcatEchoEnabled: Boolean = false

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

    /** 进程内预览文本：与导出同源，同样只含写入时已脱敏的行 */
    fun exportText(): String = synchronized(lock) { lines.joinToString("\n") }

    /** 导出边界的第二道防线：规则幂等，对写入时已脱敏的行重复调用不改变内容 */
    fun exportSanitizedText(): String = synchronized(lock) {
        DiagnosticLogRedactor.redact(lines.joinToString("\n"))
    }

    private fun append(level: String, tag: String, message: String) {
        val timestamp = LocalTime.now().format(TS_FORMAT)
        // 写入即脱敏：整行（含 tag）过一遍规则，不留任何「导出前才清理」的窗口。
        // 导出边界的二次脱敏是幂等的第二道防线，不依赖本调用点的历史正确性。
        val line = DiagnosticLogRedactor.redact("[$timestamp] [$level] [$tag] $message")
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
        if (logcatEchoEnabled) {
            // 取证桥（§411）：内容与缓冲同源；经统一包装器 AppLog 输出（生产源码不得直引
            // 平台 Log 类；AppLog.safe 自带 JVM fail-safe）。release 保留 i 级输出，
            // 故稳定版同样可经 adb logcat 取得脱敏诊断行。
            com.keepasskey.core.log.AppLog.i(LOGCAT_TAG, line)
        }
    }

    companion object {
        /** 审计行级别标识（区分于 INFO/WARN，便于导出后人工核查） */
        const val AUDIT_LEVEL = "AUDIT"
        private const val MAX_LINES = 500
        private val TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private const val LOGCAT_TAG = "KpLog"
    }
}
