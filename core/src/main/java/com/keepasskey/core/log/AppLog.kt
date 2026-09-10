package com.keepasskey.core.log

import android.util.Log
import kotlin.concurrent.Volatile

/**
 * 统一日志包装器（ISSUE-P1-10 / ZT-10 整改）。
 *
 * 全仓业务代码禁止直接调用 `android.util.Log`，统一经本包装器输出：
 * 1. **v/d 仅调试期输出**：[debugEnabled] 由宿主 Application 按 `BuildConfig.DEBUG` 置位，
 *    release 构建另由 R8 `-assumenosideeffects` 直接剥离调用点（双保险）；
 * 2. **e/w 异常脱敏**：release 下只保留异常类名，绝不透出异常 message 与堆栈——
 *    message 属不可信外部输入（可能携带 rpId / userName / 主机地址等敏感标识），
 *    堆栈行也可能引用含敏感数据的代码上下文；
 * 3. **fail-safe**：Log 输出失败（含 JVM 单测环境无 android.util.Log 实现）一律静默，
 *    日志永不阻断业务主流程。
 */
object AppLog {

    /** 进程级调试开关：宿主 Application（MainApplication）onCreate 时按 BuildConfig.DEBUG 置位 */
    @Volatile
    var debugEnabled: Boolean = false

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
            safe { Log.w(tag, sanitize(message, t)) }
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t == null) {
            safe { Log.e(tag, message) }
        } else if (debugEnabled) {
            safe { Log.e(tag, message, t) }
        } else {
            safe { Log.e(tag, sanitize(message, t)) }
        }
    }

    /** release 脱敏：异常仅落全限定类名，message 与堆栈一律不外传 */
    private fun sanitize(message: String, t: Throwable): String = "$message <${t.javaClass.name}>"

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // JVM 单测环境 android.util.Log 为未 mock 的 stub；日志输出永不影响业务主流程
        }
    }
}
