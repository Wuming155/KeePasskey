package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.logger.DiagnosticLogBridge
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步入口守护的留痕契约：编排段异常必须先进可导出归档再交给调用方。
 *
 * 整改前多数 `onFailure` 只把异常类名塞进 UI，导出日志只剩「同步开始/结束」；
 * 本用例经真实诊断桥落盘，钉死「归档有类型与栈帧、不含异常原文、UI 仍有可理解提示」。
 */
class SyncGuardedLaunchTest {

    private val store = ExtendedSettingsStore(InMemorySharedPreferences().context())

    @After
    fun tearDown() {
        AppLog.diagnosticSink = null
        AppLog.diagnosticLineSink = null
        AppLog.debugEnabled = false
    }

    @Test
    fun `编排异常先写入诊断归档再交给调用方`() {
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, store).install()
        var uiFailureType: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        scope.launchGuarded(onFailure = { uiFailureType = it.javaClass.simpleName }) {
            throw IllegalStateException("raw-secret")
        }

        assertEquals("调用方仍须拿到可理解提示", "IllegalStateException", uiFailureType)
        val archived = buffer.exportText()
        assertTrue("归档行须带异常类型：$archived", archived.contains("IllegalStateException"))
        assertTrue("归档行须带定位栈帧：$archived", archived.contains("\tat "))
        assertFalse("异常原文不得进入归档", archived.contains("raw-secret"))
    }
}
