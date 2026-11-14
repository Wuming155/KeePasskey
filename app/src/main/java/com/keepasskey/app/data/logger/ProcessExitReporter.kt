package com.keepasskey.app.data.logger

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上次进程退出原因的冷启动采集（`ISSUE-P2` C2）。
 *
 * 背景：进程消失后重新启动时，应用此前无法区分「系统回收 / 低内存 / ANR / native 崩溃 /
 * 用户划掉」，`P3 自动填充服务进程崩溃重启根因未定位` 因此长期只能靠 logcat 取证。
 * 本类读取系统保留的 [ApplicationExitInfo]，经审计通道写入只含枚举与数值的一行。
 *
 * 边界：只覆盖 Android 11+ 提供的 Java / 平台退出记录；native 崩溃由系统以
 * `REASON_CRASH_NATIVE` 归类，但本类**不**宣称能还原崩溃栈（栈仍需 tombstone / logcat）。
 * 不写库名、路径、账号或任何用户数据；读取失败只留异常类型。
 */
@Singleton
class ProcessExitReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buffer: DebugLogBuffer
) {

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ProcessExitReporter").apply { isDaemon = true }
    }

    /** 冷启动时调用一次：只上报上次启动之后的新记录，避免每次启动重复同一批历史。 */
    fun reportNewExits() {
        // 读取系统服务与偏好属 IO：冷启动主线程不等待，失败静默（内部已 runCatching）。
        executor.execute { readAndReport() }
    }

    private fun readAndReport() {
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val records = manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                MAX_RECORDS
            )
            if (records.isNullOrEmpty()) {
                audit("进程退出记录: 无记录")
                return
            }
            val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val lastReported = prefs.getLong(KEY_LAST_REPORTED_AT, 0L)
            val fresh = records
                .filter { it.timestamp > lastReported }
                .sortedBy { it.timestamp }
            if (fresh.isEmpty()) return
            fresh.forEach { info ->
                audit(
                    ProcessExitFormatter.format(
                        reason = info.reason,
                        status = info.status,
                        importance = info.importance,
                        pssKb = info.pss,
                        timestampMs = info.timestamp
                    )
                )
            }
            prefs.edit().putLong(KEY_LAST_REPORTED_AT, fresh.maxOf { it.timestamp }).apply()
        }.onFailure { failure ->
            audit("进程退出记录读取失败: ${failure.javaClass.simpleName}")
        }
    }

    private fun audit(message: String) {
        // 审计通道不受用户诊断开关约束：这是系统级运行事实，不是业务诊断事件。
        buffer.audit(TAG, message)
    }

    private companion object {
        const val TAG = "ProcessExit"
        const val PREFERENCES_NAME = "process_exit_history"
        const val KEY_LAST_REPORTED_AT = "last_reported_at_ms"
        const val MAX_RECORDS = 5
    }
}

/**
 * 退出原因格式化（纯函数，JVM 可单测）。只输出枚举名、状态码、重要性、PSS 与时刻。
 */
internal object ProcessExitFormatter {

    fun format(reason: Int, status: Int, importance: Int, pssKb: Long, timestampMs: Long): String =
        "进程退出记录: reason=${reasonLabel(reason)}, status=$status, importance=$importance, " +
            "pss=${pssKb}KB, at=${timestamp(timestampMs)}"

    fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }

    private fun timestamp(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
}
