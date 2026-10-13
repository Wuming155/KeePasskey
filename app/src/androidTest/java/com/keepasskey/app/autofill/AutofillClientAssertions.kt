package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** ISSUE-P3-608：直接读取独立客户端当前字段状态；认证页出现或旧 logcat 均不能替代它。 */
internal object AutofillClientAssertions {
    fun assertFilled(probe: DeviceAutofillProbe, packageName: String, timeoutMs: Long, runId: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            node = probe.awaitClientStatus(packageName, AutofillClientActivity.STATUS_PREFIX,
                (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1))
            val status = node?.let { probe.textOf(it) }.orEmpty()
            if (status.contains(" runId=$runId ") && status.contains(" usernameMatchesSeed=true") &&
                status.contains(" passwordMatchesSeed=true")) break
            android.os.SystemClock.sleep(200L)
        }
        assertNotNull("未找到独立客户端活动窗口的字段状态", node)
        val state = probe.textOf(node!!)
        val usernameLength = state.substringAfter(" usernameLength=", "").substringBefore(' ').toIntOrNull()
        val passwordLength = state.substringAfter(" passwordLength=", "").substringBefore(' ').toIntOrNull()
        assertEquals("客户端必须属于本次运行实例", runId, state.substringAfter(" runId=", "").substringBefore(' '))
        assertEquals("实际用户名长度必须与播种账号一致", AutofillSeedContract.USERNAME.length, usernameLength ?: -1)
        assertEquals("实际口令长度必须是本次播种口令长度", AutofillSeedContract.PASSWORD.length, passwordLength ?: -1)
        assertTrue("客户端用户名框未被填充", state.contains(" usernameFilled=true"))
        assertTrue("客户端口令框未被填充", state.contains(" passwordFilled=true"))
        assertTrue("实际用户名必须逐字符匹配本次合成种子", state.contains(" usernameMatchesSeed=true"))
        assertTrue("实际口令必须逐字符匹配本次合成种子", state.contains(" passwordMatchesSeed=true"))
    }
}
