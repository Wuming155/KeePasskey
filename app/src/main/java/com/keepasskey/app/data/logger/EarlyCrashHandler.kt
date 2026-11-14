package com.keepasskey.app.data.logger

import android.content.Context
import com.keepasskey.core.log.AppLog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `attachBaseContext` 阶段的早期崩溃留痕（`ISSUE-P2` A5）。
 *
 * 背景：[CrashLogRecorder] 在 `Application.onCreate` 中段安装，`androidx.startup` 的
 * `InitializationProvider`、静态初始化块与 `attachBaseContext` 都早于它；这些阶段抛出的
 * 未捕获异常此前只进 logcat，用户可导出的归档零记录。
 *
 * 设计约束：
 * - **不引用任何 Hilt 注入字段 / Android 依赖注入对象**：只做「脱敏行 + 同步追加」；
 * - 归档路径与 [DiagnosticLogHistory] 相同（`noBackupFilesDir/diagnostic/diagnostic_history.log`），
 *   后续进程启动时自然并入历史；
 * - [CrashLogRecorder] 安装后调用 [markReady]，本处理器只转交、不再写第二行（避免双记录）；
 * - 自身任何失败都不得改变崩溃语义：先 `runCatching`，随后**原样转交**安装前的处理器。
 */
internal object EarlyCrashHandler {

    private const val TAG = "EarlyCrash"
    private const val CRASH_TAG = "Crash"
    private const val CRASH_MESSAGE = "未捕获异常，进程即将退出（早期初始化阶段）"
    private const val MAX_FRAMES = 8
    private const val DIR = "diagnostic"
    private const val FILE_NAME = "diagnostic_history.log"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var ready = false

    @Volatile
    private var archiveFile: File? = null

    private val fileLock = Any()

    /** 幂等安装；必须在 `attachBaseContext` 中调用（早于 ContentProvider 初始化）。 */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        archiveFile = runCatching {
            File(File(context.noBackupFilesDir, DIR), FILE_NAME)
        }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (!ready) {
                runCatching { writeCrashLine(thread.name, error) }
                    .onFailure { failure ->
                        AppLog.e(TAG, "早期崩溃留痕失败: ${failure.javaClass.simpleName}")
                    }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 主处理器就绪后调用：此后本处理器只负责转交，不再写归档（单一写入口）。
     * 由 `MainApplication.onCreate` 在 [CrashLogRecorder.install] 之后调用一次。
     */
    fun markReady() {
        ready = true
    }

    private fun writeCrashLine(threadName: String, error: Throwable) {
        val file = archiveFile ?: return
        val now = LocalDateTime.now()
        val line = buildLine(
            date = now.format(DATE_FORMAT),
            time = now.format(TS_FORMAT),
            threadName = threadName,
            error = error
        )
        synchronized(fileLock) {
            runCatching { file.parentFile?.mkdirs() }
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    /**
     * 纯格式化（JVM 可测）：日期、时刻、线程名、固定级别/tag、异常类型与有界栈帧。
     * 不写异常 message；输出仍经 [DiagnosticLogRedactor] 兜底脱敏。
     */
    internal fun buildLine(date: String, time: String, threadName: String, error: Throwable): String {
        val thread = threadName.ifEmpty { "<unnamed>" }.take(MAX_THREAD_NAME)
        val raw = buildString {
            append("[").append(date).append("] [").append(time).append("] [")
            append(thread).append("] [ERROR] [").append(CRASH_TAG).append("] ").append(CRASH_MESSAGE)
            append('\n').append(error.javaClass.name)
            error.stackTrace.take(MAX_FRAMES).forEach { frame ->
                append("\n\tat ").append(frame)
            }
        }
        return runCatching { DiagnosticLogRedactor.redact(raw) }.getOrDefault(raw)
    }

    private const val MAX_THREAD_NAME = 48
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
}
