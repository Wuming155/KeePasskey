package com.keepasskey.app.ui.screens.settings

import com.keepasskey.core.log.AppLog
import com.keepasskey.app.data.logger.DebugLogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 调试日志真实缓冲快照及刷新/清除动作。 */
internal class SettingsDebugLogActions(debugLogBuffer: DebugLogBuffer) {
    private val buffer = debugLogBuffer

    /** 调试日志真实缓冲快照（随刷新/清除动作更新） */
    private val debugLogLinesFlow = MutableStateFlow(buffer.snapshot())

    val debugLogLines: StateFlow<List<String>> = debugLogLinesFlow

    fun refreshDebugLogs() {
        debugLogLinesFlow.value = buffer.snapshot()
    }

    fun clearDebugLogs() {
        buffer.clear()
        debugLogLinesFlow.value = emptyList()
        // 治理动作留痕：用户清空后归档里仍能看出「日志被主动清除」，而不是无声断点。
        // clear() 先把删除任务排入归档 executor，本行随后追加，故审计行不会被同批删除。
        AppLog.audit(TAG, "日志清除: 用户主动清除了诊断日志")
    }

    private companion object {
        const val TAG = "LogClearAudit"
    }
}
