package com.keepasskey.app.data.logger

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Singleton

/**
 * 环形诊断缓冲的当前状态（供导出/复制报告头说明「日志是否被截断」，不进入日志正文）。
 */
data class DiagnosticLogStatus(
    val retained: Int,
    val capacity: Int,
    val dropped: Int,
    val firstTimestamp: String?,
    val lastTimestamp: String?
)

/**
 * 进程内诊断日志环形缓冲（Kp2a 调试日志系统的本地优先轻量实现）。
 *
 * 稳定版与调试版共用本缓冲：用户遇到故障后必须能导出日志向开发者反馈，故 release 不再
 * 恒关。**所有行在写入时经 [DiagnosticLogRedactor] 脱敏**，缓冲、设置页预览、logcat 直出
 * 与导出文件正文同源，均不含 URL、账号、主机、库/密钥文件名等标识；导出边界再脱敏一次。
 * 缓冲主存内存供设置页预览；每行同时异步追加到应用私有目录的 [DiagnosticLogHistory]，
 * 按设置页选择的周期（默认 3 天）留存，导出正文直接取该归档，覆盖周期内所有进程；
 * 用户主动清除日志时一并删除。导出到用户选择的位置仍只在用户主动导出时发生。
 *
 * 故障可定位性：每行带**日期、时刻、线程名、级别、tag**，并由应用内诊断桥把 `AppLog.e/w`
 * 的异常类型与有界栈帧（[com.keepasskey.core.log.diagnosticText]，不含异常 message）写入同一缓冲。
 * 因此稳定版导出的日志不只是「某步失败」，还能看到失败发生在哪个类、哪个方法。
 * 容量放宽到 [MAX_LINES] 行；溢出时在快照首行给出明确的丢弃提示，并可由 [status] 精确查询，
 * 避免关键故障被静默冲掉而排查者误以为日志完整。
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

    /** 溢出提示：缓冲被裁掉过历史行时作为快照首行呈现，不占正文容量、也不重复进 logcat。 */
    private var overflowNotice: String? = null
    private var droppedLines = 0
    private var firstTimestamp: String? = null
    private var lastTimestamp: String? = null

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

    fun snapshot(): List<String> = synchronized(lock) { snapshotLocked() }

    fun clear() = synchronized(lock) {
        lines.clear()
        overflowNotice = null
        droppedLines = 0
        firstTimestamp = null
        lastTimestamp = null
    }.also { DiagnosticLogHistory.clear() }

    /** 缓冲当前状态：报告头据此说明时间跨度、保留行数与丢弃行数。 */
    fun status(): DiagnosticLogStatus = synchronized(lock) {
        DiagnosticLogStatus(
            retained = lines.size,
            capacity = MAX_LINES,
            dropped = droppedLines,
            firstTimestamp = firstTimestamp,
            lastTimestamp = lastTimestamp
        )
    }

    /** 进程内预览文本：与导出正文同源，同样只含写入时已脱敏的行 */
    fun exportText(): String = synchronized(lock) { textLocked() }

    /**
     * 导出边界的第二道防线：正文优先取按天留存的磁盘归档（覆盖周期内所有进程），
     * 未启用磁盘留存（纯 JVM 单测）时退回本进程缓冲；规则幂等，重复调用不改变内容。
     */
    fun exportSanitizedText(): String {
        val text = DiagnosticLogHistory.snapshotForExport() ?: synchronized(lock) { textLocked() }
        return DiagnosticLogRedactor.redact(text)
    }

    private fun append(level: String, tag: String, message: String) {
        val now = LocalDateTime.now()
        val date = now.format(DATE_FORMAT)
        val time = now.format(TS_FORMAT)
        val fullTimestamp = now.format(FULL_TS_FORMAT)
        // 线程名只在多线程问题上有意义，截断防止异常长的自定义线程名撑爆单行
        val thread = Thread.currentThread().name.take(MAX_THREAD_NAME_LENGTH)
        // 写入即脱敏：整行（含 tag / 线程名 / 栈帧）过一遍规则，不留任何「导出前才清理」的窗口。
        // 导出边界的二次脱敏是幂等的第二道防线，不依赖本调用点的历史正确性。
        val line = DiagnosticLogRedactor.redact("[$date] [$time] [$thread] [$level] [$tag] $message")
        synchronized(lock) {
            lines.addLast(line)
            if (firstTimestamp == null) firstTimestamp = fullTimestamp
            lastTimestamp = fullTimestamp
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
                droppedLines++
            }
            if (droppedLines > 0) {
                overflowNotice = DiagnosticLogRedactor.redact(
                    "[$date] [$time] [$thread] [WARN] [Diagnostics] " +
                        "日志缓冲已达上限 $MAX_LINES 行，已丢弃较早的 $droppedLines 行；完整记录请及时复制或导出"
                )
            }
        }
        if (logcatEchoEnabled) {
            // 取证桥（§411）：内容与缓冲同源；经统一包装器 AppLog 输出（生产源码不得直引
            // 平台 Log 类；AppLog.safe 自带 JVM fail-safe）。release 保留 i 级输出，
            // 故稳定版同样可经 adb logcat 取得脱敏诊断行。
            com.keepasskey.core.log.AppLog.i(LOGCAT_TAG, line)
        }
        // 跨进程留存：写入时已脱敏的整行异步落盘，下次进程启动灌回缓冲前缀。
        DiagnosticLogHistory.append(line)
    }

    /** 调用方已持锁：快照与导出正文共用，保证「看到什么就能导出什么」。 */
    private fun snapshotLocked(): List<String> = buildList(lines.size + 1) {
        overflowNotice?.let(::add)
        addAll(lines)
    }

    private fun textLocked(): String = snapshotLocked().joinToString("\n")

    companion object {
        /** 审计行级别标识（区分于 INFO/WARN，便于导出后人工核查） */
        const val AUDIT_LEVEL = "AUDIT"

        /** 环形缓冲容量：故障复盘常需覆盖数小时事件，原 500 行易把关键失败冲掉。 */
        const val MAX_LINES = 2_000

        private const val MAX_THREAD_NAME_LENGTH = 48
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val FULL_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        internal const val LOGCAT_TAG = "KpLog"
    }
}
