package com.keepasskey.app.ui.screens.settings

import android.os.Build
import com.keepasskey.app.BuildConfig
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.logger.DiagnosticLogDocument
import com.keepasskey.app.ui.model.StringsProvider
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 诊断日志**报告**：导出文件与剪贴板复制共用同一份文本（正文恒为
 * [DebugLogBuffer.exportDocument] 产出的同一次归档快照）。
 *
 * 缓冲里只有事件行，排查者还需要知道「这是哪个版本、哪个平台、日志有没有被环形缓冲截掉」。
 * 故在正文之前附一段只读报告头；它不属于日志正文，不进缓冲、不占容量、也不会被 logcat 直出。
 * 稳定版故障定位所需的异常类型与栈帧由 [com.keepasskey.app.data.logger.DiagnosticLogBridge]
 * 以事件形式写进正文，与本头不重复。
 *
 * 报告头的行数与记录范围**取自正文同一份快照**：此前头读内存状态、正文读磁盘归档，
 * 进程重启后会导出「头说 2 行、正文有 3 天」的自相矛盾报告。
 *
 * 剪贴板是跨进程 Binder 通道，单次写入不能承载任意长度文本：超限时保留报告头与**最近**的
 * 整行日志（故障现场通常最新的信息最有用），并明确标注已截断，引导用户改用导出取全文。
 */
internal object DiagnosticLogReport {

    /** 单次剪贴板写入的字符预算（远低于 Binder 事务上限，兼顾失败率与故障信息量） */
    const val MAX_CLIP_CHARS = 100_000

    private const val SEPARATOR = "------------------------------"
    private val GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun build(buffer: DebugLogBuffer, strings: StringsProvider): String =
        build(buffer.exportDocument(), strings)

    fun build(document: DiagnosticLogDocument, strings: StringsProvider): String =
        buildString {
            appendLine(strings.get(R.string.debug_report_title))
            appendLine(
                strings.get(
                    R.string.debug_report_env,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.BUILD_TYPE,
                    Build.VERSION.SDK_INT,
                    // 纯 JVM 单测的 Android stub 可能返回 null；环境摘要缺 ABI 不影响排查
                    Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
                    LocalDateTime.now().format(GENERATED_AT_FORMAT)
                )
            )
            appendLine(
                strings.get(
                    R.string.debug_report_state,
                    document.retained,
                    DebugLogBuffer.MAX_LINES,
                    document.dropped,
                    document.firstTimestamp ?: "-",
                    document.lastTimestamp ?: "-"
                )
            )
            appendLine(SEPARATOR)
            append(document.text)
        }

    /**
     * 超长报告按行裁剪：头部（含版本与状态）恒保留，正文只取尾部整行。
     * 返回的字符串长度不超过 [MAX_CLIP_CHARS]（除非单行本身超预算，此时仍按尾部截断）。
     */
    fun forClipboard(report: String, strings: StringsProvider): String {
        if (report.length <= MAX_CLIP_CHARS) return report
        val notice = strings.get(R.string.debug_copy_truncated)
        val separatorIndex = report.indexOf(SEPARATOR)
        val header = if (separatorIndex >= 0) {
            report.substring(0, separatorIndex + SEPARATOR.length) + "\n"
        } else {
            ""
        }
        val body = if (separatorIndex >= 0) {
            report.substring(separatorIndex + SEPARATOR.length).trimStart('\n')
        } else {
            report
        }
        val budget = MAX_CLIP_CHARS - header.length - notice.length - 1
        val tail = if (budget <= 0 || body.length <= budget) {
            body.takeLast(budget.coerceAtLeast(0))
        } else {
            val candidate = body.takeLast(budget)
            val firstBreak = candidate.indexOf('\n')
            if (firstBreak >= 0) candidate.substring(firstBreak + 1) else candidate
        }
        return (header + notice + "\n" + tail).takeLast(MAX_CLIP_CHARS)
    }
}
