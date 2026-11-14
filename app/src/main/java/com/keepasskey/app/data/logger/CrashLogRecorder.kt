package com.keepasskey.app.data.logger

import com.keepasskey.core.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级未捕获异常留痕（`APP-CRASH-01`）。
 *
 * ## 要补的缺口
 * [DebugLogBuffer.recordCrash] 之前没有任何调用方：只有**业务自己捕获**的异常经
 * `AppLog.e/w` 进入可导出归档，而真正逃出线程栈的异常（主线程、Binder 线程、协程根）
 * 仅进 logcat。用户导出诊断日志后只能看到「某服务被系统重启」，看不到「为什么」——
 * 自动填充服务进程崩溃重启一事即因此长期无法定性（见 `docs/ACTIVE_ISSUES.md`）。
 *
 * ## 行为与边界
 * - 处理器只做一件事：把**脱敏后的类型与有界栈帧**同步追加到留存归档，随后**原样交给**
 *   安装前的默认处理器——崩溃语义（系统崩溃弹窗 / 进程退出）不因本类改变，不吞异常、
 *   不阻止退出。
 * - 自身任何失败（磁盘满、归档未初始化、归档写失败）都不得替换原始崩溃：
 *   [DebugLogBuffer.recordCrash] 内部已 fail-safe，这里再包一层 `runCatching`。
 * - 线程名前缀截断与脱敏口径与普通日志行一致；异常 message / cause message 不外传。
 * - 只覆盖 **Java/Kotlin 未捕获异常**。native 崩溃（SIGSEGV / Rust abort）不会走到本处理器，
 *   仍需 `adb logcat` 或 tombstone，这一点不得由本类宣称覆盖。
 * - 用户关闭诊断日志时不记录（与 [DiagnosticLogGate] 既有口径一致）。
 */
@Singleton
class CrashLogRecorder @Inject constructor(
    private val buffer: DebugLogBuffer
) {

    private val installed = AtomicBoolean(false)

    /**
     * 在 `MainApplication.onCreate` 安装一次（幂等）。
     *
     * 必须在诊断桥 [DiagnosticLogBridge.install] 之后调用：归档目录由该步配置，
     * 未配置时 [DebugLogBuffer.recordCrash] 只落内存缓冲，进程退出即丢失。
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                buffer.recordCrash(threadName = thread.name, t = error)
            }.onFailure { logFailure ->
                // 记录失败不能替换原始崩溃：只留一行类型，不输出任何 message。
                AppLog.e(TAG, "未捕获异常留痕失败: ${logFailure.javaClass.simpleName}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val TAG = "CrashLogRecorder"
    }
}
