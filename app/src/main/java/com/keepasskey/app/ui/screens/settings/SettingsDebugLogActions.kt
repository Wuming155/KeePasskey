package com.keepasskey.app.ui.screens.settings

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
    }
}
