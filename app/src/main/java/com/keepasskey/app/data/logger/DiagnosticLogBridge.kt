package com.keepasskey.app.data.logger

import android.content.Context
import android.os.Build
import com.keepasskey.app.BuildConfig
import com.keepasskey.app.autofill.autofillEnvironmentLine
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.log.MAX_DIAGNOSTIC_CAUSES
import com.keepasskey.core.log.MAX_DIAGNOSTIC_FRAMES
import com.keepasskey.core.log.diagnosticText
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 稳定版故障诊断桥：把 `AppLog.e/w` 的异常事件接进用户可导出的诊断缓冲。
 *
 * 背景：release 的异常类型与有界栈帧此前**只进 logcat**，设置页缓冲里只剩业务调用点手写的
 * 「某步失败: 异常类名」一行，用户导出后无法判断失败发生在哪个类、哪个方法。本桥复用
 * [diagnosticText]（与 logcat 完全同源的脱敏栈帧格式化：不含异常 message / cause message /
 * suppressed），让普通用户不经 adb 也能拿到同样的定位信息。
 *
 * 「详细日志模式」在此有真实消费点：关闭时只保留 [BRIEF_FRAMES] 帧的精简栈，开启时取
 * logcat 的完整 [MAX_DIAGNOSTIC_FRAMES] 帧预算。普通诊断事件仍遵守 [DiagnosticLogGate]
 * 的用户开关；进程诊断头走 [DebugLogBuffer.audit] 审计通道，说明版本、构建类型、平台与
 * 开关状态，使导出/复制的日志能对应到具体安装包的运行环境。
 */
@Singleton
class DiagnosticLogBridge @Inject constructor(
    private val buffer: DebugLogBuffer,
    private val settingsStore: ExtendedSettingsStore
) {

    /**
     * 由 MainApplication.onCreate 安装一次；重复安装只替换同一个函数引用，不重复写头。
     *
     * @param context 生产传入 Application（用于取回上一条进程的诊断历史）；纯 JVM 单测传 null。
     */
    fun install(context: Context? = null) {
        AppLog.diagnosticSink = { level, tag, message, throwable ->
            val frameBudget = if (settingsStore.isVerboseSyncLogEnabled()) {
                MAX_DIAGNOSTIC_FRAMES
            } else {
                BRIEF_FRAMES
            }
            val text = diagnosticText(
                message = message,
                t = throwable,
                frameBudget = frameBudget,
                maxCauses = MAX_DIAGNOSTIC_CAUSES
            )
            buffer.log(level, tag, text)
        }
        // 纯文案的 i / 无异常 e/w 此前只进 logcat，用户导出里看不到；统一接进同一份缓冲。
        // KpLog 是缓冲回显 logcat 的 tag，必须过滤，否则形成「缓冲 → logcat → 缓冲」回环。
        AppLog.diagnosticLineSink = { level, tag, message ->
            if (tag != DebugLogBuffer.LOGCAT_TAG) buffer.log(level, tag, message)
        }
        if (context != null) {
            val dir = File(context.noBackupFilesDir, "diagnostic")
            DiagnosticLogHistory.configure(dir, settingsStore.diagnosticLogRetentionDays())
        }
        buffer.audit(TAG, processHeader())
    }

    /**
     * 由 `MainApplication.onCreate` 在 [install] 之后调用一次：把系统侧自动填充开关与
     * 装机时间写进审计通道。导出日志据此可直接判定「系统是否把本应用设为自动填充服务」，
     * 不必让用户翻系统设置，也能核对设备上跑的是不是刚打的包。
     */
    fun logAutofillEnvironment(context: Context) {
        buffer.audit(
            TAG,
            autofillEnvironmentLine(
                context = context,
                autofillChannelEnabled = settingsStore.isAutofillServiceEnabled(),
                offerSaveEnabled = settingsStore.isOfferSaveCredentialsEnabled()
            )
        )
    }

    private fun processHeader(): String = buildString {
        append("进程诊断头: 版本=").append(BuildConfig.VERSION_NAME)
        append(", 构建=").append(BuildConfig.BUILD_TYPE)
        append(", API=").append(Build.VERSION.SDK_INT)
        // Android 平台 stub 在纯 JVM 单测里会让 SUPPORTED_ABIS 为 null；此处只作环境描述，
        // 取不到时留空即可，绝不能让诊断头把进程启动或单测打挂。
        append(", ABI=").append(Build.SUPPORTED_ABIS?.firstOrNull().orEmpty())
        append(", 诊断日志=").append(if (settingsStore.isDiagnosticLogEnabled()) "开启" else "关闭")
        append(", 详细模式=").append(if (settingsStore.isVerboseSyncLogEnabled()) "开启" else "关闭")
        append(", 缓冲上限=").append(DebugLogBuffer.MAX_LINES)
    }

    private companion object {
        const val TAG = "Diagnostics"
        const val BRIEF_FRAMES = 8
    }
}
