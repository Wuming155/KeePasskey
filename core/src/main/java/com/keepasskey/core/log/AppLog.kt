package com.keepasskey.core.log

import android.util.Log
import kotlin.concurrent.Volatile

/**
 * 应用内诊断桥的事件出口（由宿主 Application 在冷启动时安装一次）。
 *
 * `AppLog.e/w` 带异常时，除 logcat 之外还把**原始事件**交给出口；出口侧负责用
 * [diagnosticText] 生成脱敏诊断文本并写入用户可导出的缓冲。core 不依赖 app 层实现，
 * 也不在此决定脱敏策略，避免在底层引入新的敏感面。
 */
typealias DiagnosticEventSink = (level: String, tag: String, message: String, throwable: Throwable) -> Unit

/**
 * 普通诊断行出口（由宿主 Application 在冷启动时安装一次）。
 *
 * `AppLog.i` 与不带异常的 `e/w` 此前只写 logcat，用户导出的应用内诊断缓冲里看不到；
 * 带异常的 `e/w` 才经 [DiagnosticEventSink] 转成脱敏栈帧。本出口把纯文案事件也接进
 * 同一份缓冲，避免「代码里打了日志、导出里没有」的接线错位。出口侧负责脱敏与循环防护。
 */
typealias DiagnosticLineSink = (level: String, tag: String, message: String) -> Unit

/** release 诊断栈的帧预算（跨 cause 链累计，每个异常另保底 1 帧定位锚点）与 cause 链深度上限，避免故障路径刷爆日志 */
const val MAX_DIAGNOSTIC_FRAMES = 32
const val MAX_DIAGNOSTIC_CAUSES = 4

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
 *
 * 稳定版可导出的诊断缓冲此前只能拿到业务调用点手写的「异常类名」一行：带异常的 e/w
 * 只把类型与栈帧写进 logcat，普通用户无法取出。现由 [diagnosticSink] 把同一批故障事件
 * 交给应用内诊断桥，桥侧复用 [diagnosticText] 生成同源脱敏文本后写入可导出缓冲。
 */
object AppLog {

    /** 进程级调试开关：宿主 Application（MainApplication）onCreate 时按 BuildConfig.DEBUG 置位 */
    @Volatile
    var debugEnabled: Boolean = false

    /**
     * 进程级诊断事件出口；null = 尚未安装（冷启动早期或纯 JVM 单测）。
     * 与 [debugEnabled] 同为进程级日志装配状态：只在 Application.onCreate 安装，永不由业务写入。
     */
    @Volatile
    var diagnosticSink: DiagnosticEventSink? = null

    /**
     * 进程级普通诊断行出口；null = 尚未安装（冷启动早期或纯 JVM 单测）。
     * 与 [diagnosticSink] 同为进程级日志装配状态，只在 Application.onCreate 安装。
     */
    @Volatile
    var diagnosticLineSink: DiagnosticLineSink? = null

    fun v(tag: String, message: String) {
        if (debugEnabled) safe { Log.v(tag, message) }
    }

    fun d(tag: String, message: String) {
        if (debugEnabled) safe { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) {
        safe { Log.i(tag, message) }
        publishLine("INFO", tag, message)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t == null) {
            safe { Log.w(tag, message) }
            publishLine("WARN", tag, message)
        } else if (debugEnabled) {
            safe { Log.w(tag, message, t) }
        } else {
            safe { Log.w(tag, diagnosticText(message, t)) }
        }
        if (t != null) publishDiagnostic("WARN", tag, message, t)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t == null) {
            safe { Log.e(tag, message) }
            publishLine("ERROR", tag, message)
        } else if (debugEnabled) {
            safe { Log.e(tag, message, t) }
        } else {
            safe { Log.e(tag, diagnosticText(message, t)) }
        }
        if (t != null) publishDiagnostic("ERROR", tag, message, t)
    }

    /**
     * 故障事件出 logcat 之外的第二通道：只转发**带异常**的 e/w（纯文案事件由业务调用点自行
     * 留痕，避免重复的降级告警刷满缓冲）。出口侧异常同样 fail-safe，绝不影响业务主流程。
     */
    private fun publishDiagnostic(level: String, tag: String, message: String, t: Throwable) {
        val sink = diagnosticSink ?: return
        try {
            sink(level, tag, message, t)
        } catch (_: Throwable) {
            // 诊断出口自身失败不得冒泡到故障路径
        }
    }

    /** 纯文案事件的第二通道；出口侧负责脱敏与缓冲回显的循环防护 */
    private fun publishLine(level: String, tag: String, message: String) {
        val sink = diagnosticLineSink ?: return
        try {
            sink(level, tag, message)
        } catch (_: Throwable) {
            // 诊断出口自身失败不得冒泡到业务路径
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // JVM 单测环境 android.util.Log 为未 mock 的 stub；日志输出永不影响业务主流程
        }
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
 * 本函数是 logcat 输出与应用内可导出诊断缓冲的**唯一**栈帧格式化入口：缓冲侧复用同一形态，
 * 保证用户导出的故障定位信息与 logcat 同源。调用方可按「详细日志模式」调小 [frameBudget]
 * 得到精简栈（默认预算即 release logcat 的既有形态）。
 *
 * 预算分配（设备用例所约束的契约，ENG-20261028-27）：**类型行一律输出**，帧由
 * [frameBudget] 跨 cause 链累计分配，但每个异常另保底 1 帧定位锚点——
 * 顶层异常帧数常达 30+，若让首个异常吃光预算，后续 cause 只剩类型行而失去「哪个方法抛出」
 * 的锚点（设备用例实测到该形态）。超出预算的帧标注 `... N more`；cause 链超过
 * [maxCauses] 层时标注 `... cause chain truncated`。
 */
fun diagnosticText(
    message: String,
    t: Throwable,
    frameBudget: Int = MAX_DIAGNOSTIC_FRAMES,
    maxCauses: Int = MAX_DIAGNOSTIC_CAUSES
): String = buildString {
    append(message)
    var remainingFrames = frameBudget
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
            val shown = maxOf(1, minOf(frames.size, remainingFrames))
            for (i in 0 until shown) append("\n\tat ").append(frames[i])
            if (shown < frames.size) append("\n\t... ").append(frames.size - shown).append(" more")
            remainingFrames = (remainingFrames - shown).coerceAtLeast(0)
        }
        val cause = current.cause
        val next = if (cause === current) null else cause
        if (next == null) break
        if (causeDepth >= maxCauses) {
            chainTruncated = true
            break
        }
        current = next
        isCause = true
        causeDepth++
    }
    if (chainTruncated) append("\n... cause chain truncated")
}
