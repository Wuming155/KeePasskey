package com.keepasskey.app.data.logger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.core.log.AppLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 诊断归档的设备侧契约（`ISSUE-P2` E5）：真实私有目录、真实诊断桥、跨缓冲导出与清除。
 *
 * JVM 用例覆盖了裁剪与并发；本用例补的是只有设备才能证明的「应用私有目录 + Context 装配」
 * 这一段：桥安装后写事件 → 导出正文取自归档 → 清除后归档不残留。
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticLogArchiveDeviceTest {

    @Test
    fun `归档跨缓冲实例可导出且清除后不残留`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val buffer = DebugLogBuffer()
        DiagnosticLogBridge(buffer, ExtendedSettingsStore(context)).install(context)

        AppLog.i(TAG, "device-archive-entry")

        val exported = buffer.exportSanitizedText()
        assertTrue("归档正文须包含本进程事件：$exported", exported.contains("device-archive-entry"))
        assertTrue("归档须含进程诊断头：$exported", exported.contains("进程诊断头"))

        buffer.clear()

        val cleared = buffer.exportSanitizedText()
        assertFalse("用户清除后归档不得残留正文：$cleared", cleared.contains("device-archive-entry"))
    }

    private companion object {
        const val TAG = "DeviceArchiveTest"
    }
}
