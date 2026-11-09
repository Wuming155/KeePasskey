package com.keepasskey.core.log

import android.util.Log
import kotlin.concurrent.Volatile

/**
 * 统一日志包装器（ISSUE-P1-10 / ZT-10 整改）。
 *
 * 全仓业务代码禁止直接调用 `android.util.Log`，统一经本包装器输出：
 * 1. **v/d 仅调试期输出**：[debugEnabled] 由宿主 Application 按 `BuildConfig.DEBUG` 置位，
 *    release 构建另由 R8 `-assumenosideeffects` 直接剥离调用点（双保险）；
 * 2. **e/w 异常按内容分级**：release 下输出调用方 message、异常类型与**栈帧**，绝不输出
 *    异常 message、cause message、suppressed 内容或 `Throwable.toString()` 结果——
 *    message 属不可信外部输入（可能携带 rpId / userName / 主机地址等敏感标识），
 *    而 `StackTraceElement` 只有「类名 + 方法名 + 文件 + 行号」，不含局部变量与业务数据，
 *    是可在脱敏前提下保留的定位信息（ENG-20261028-27）；
 * 3. **fail-safe**：Log 输出失败（含 JVM 单测环境无 android.util.Log 实现）一律静默，
 *    日志永不阻断业务主流程。
 */
object AppLog {

    /** 进程级调试开关：宿主 Application（MainApplication）onCreate 时按 BuildConfig.DEBUG 置位 */
    @Volatile
    var debugEnabled: Boolean = false

    /** release 诊断栈的帧预算（跨 cause 链累计，每个异常另保底 1 帧定位锚点）与 cause 链深度上限，避免故障路径刷爆 logcat */
    private const val MAX_DIAGNOSTIC_FRAMES = 32
    private const val MAX_DIAGNOSTIC_CAUSES = 4

    fun v(tag: String, message: String) {
        if (debugEnabled) safe { Log.v(tag, message) }
    }

    fun d(tag: String, message: String) {
        if (debugEnabled) safe { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) = safe { Log.i(tag, message) }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t == null) {
            safe { Log.w(tag, message) }
        } else if (debugEnabled) {
            safe { Log.w(tag, message, t) }
        } else {
            safe { Log.w(tag, diagnostic(message, t)) }
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t == null) {
            safe { Log.e(tag, message) }
        } else if (debugEnabled) {
            safe { Log.e(tag, message, t) }
        } else {
            safe { Log.e(tag, diagnostic(message, t)) }
        }
    }

    /**
     * release 诊断文本：调用方 message + 异常类型 + 栈帧，异常 message / cause message /
     * suppressed 一律不外传。
     *
     * 输出保持 Java 栈的通用形态（`类型` 行、`\tat 类.方法(文件:行)`、`Caused by: 类型`），
     * 使 release 包中被混淆的类名/方法名/行号可经 `retrace` + `mapping.txt` 还原；
     * 为此 cause 的类型行**不加**尖括号等装饰，否则 retrace 不识别该行（实测）。
     *
     * 预算分配（设备用例所约束的契约，ENG-20261028-27）：**类型行一律输出**，帧由
     * [MAX_DIAGNOSTIC_FRAMES] 跨 cause 链累计分配，但每个异常另保底 1 帧定位锚点——
     * 顶层异常帧数常达 30+，若让首个异常吃光预算，后续 cause 只剩类型行而失去「哪个方法抛出」
     * 的锚点（设备用例实测到该形态）。超出预算的帧标注 `... N more`；cause 链超过
     * [MAX_DIAGNOSTIC_CAUSES] 层时标注 `... cause chain truncated`。
     */
    private fun diagnostic(message: String, t: Throwable): String = buildString {
        append(message)
        var frameBudget = MAX_DIAGNOSTIC_FRAMES
        var causeDepth = 0
        var chainTruncated = false
        var current: Throwable? = t
        var isCause = false
        while (current != null) {
            append('\n')
            if (isCause) append("Caused by: ")
            append(current.javaClass.name)
            val frames = current.stackTrace
            if (frames.isNotEmpty()) {
                val shown = maxOf(1, minOf(frames.size, frameBudget))
                for (i in 0 until shown) append("\n\tat ").append(frames[i])
                if (shown < frames.size) append("\n\t... ").append(frames.size - shown).append(" more")
                frameBudget = (frameBudget - shown).coerceAtLeast(0)
            }
            val cause = current.cause
            val next = if (cause === current) null else cause
            if (next == null) break
            if (causeDepth >= MAX_DIAGNOSTIC_CAUSES) {
                chainTruncated = true
                break
            }
            current = next
            isCause = true
            causeDepth++
        }
        if (chainTruncated) append("\n... cause chain truncated")
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // JVM 单测环境 android.util.Log 为未 mock 的 stub；日志输出永不影响业务主流程
        }
    }
}
