package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.resetAppLogBridge
import com.keepasskey.app.testutil.installAppLogBridge
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页「读取 / 检测失败静默返回」点位的留痕契约。
 *
 * 这些分支按设计不阻断用户操作（回退表单值 / 按未配置处理），但必须留下异常类型，
 * 否则「功能没触发」在导出日志里没有任何线索。
 */
class SettingsFailureLoggingTest {

    @Before
    fun setUp() {
        // 冷启动闸门标志是 process-static（生产语义要求进程内只触发一次）：同 JVM 内
        // 先跑的用例会消费掉「唯一一次」，后续用例的留痕断言因此拿到空列表。
        // 复位口是实例方法（静态标志不对类外暴露），故借一个空实现实例调用。
        SettingsColdStartSyncGate(
            settingsRepository = FakeSettingsRepository(),
            onTriggerSync = {},
            scope = CoroutineScope(UnconfinedTestDispatcher())
        ).resetForTest()
    }

    @After
    fun tearDown() {
        resetAppLogBridge()
        AppLog.diagnosticSink = null
        AppLog.diagnosticLineSink = null
    }

    @Test
    fun `冷启动同步检测异常留痕`() {
        val logged = mutableListOf<String>()
        AppLog.diagnosticLineSink = { _, _, message -> logged += message }
        val repository = object : SettingsRepository by FakeSettingsRepository() {
            override fun getSettings(): Flow<UserSettings> =
                throw IllegalStateException("settings unavailable")
        }
        val gate = SettingsColdStartSyncGate(
            settingsRepository = repository,
            onTriggerSync = {},
            scope = CoroutineScope(UnconfinedTestDispatcher())
        )

        gate.checkAndTrigger()

        assertTrue(
            "检测异常须带类型留痕：$logged",
            logged.any { it.contains("冷启动同步检测失败") && it.contains("IllegalStateException") }
        )
    }

    @Test
    fun `读取已保存远端凭据失败留痕`() {
        val buffer = DebugLogBuffer()
        installAppLogBridge(buffer)
        val host = SettingsRemoteBrowseHost(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            savedWebDavPassword = { throw IllegalStateException("webdav decrypt failed") },
            loadS3Snapshot = { throw IllegalStateException("s3 decrypt failed") }
        )

        host.browseWebDav(url = "", username = "", password = CharArray(0), remotePath = "/")
        host.browseS3(
            endpoint = "",
            bucket = "",
            region = "",
            accessKey = CharArray(0),
            secretKey = CharArray(0),
            objectKey = "vault.kdbx",
            usePathStyle = false
        )

        val text = buffer.exportText()
        assertTrue("WebDAV 凭据读取失败须留痕：$text", text.contains("读取已保存 WebDAV 凭据失败"))
        assertTrue("S3 凭据读取失败须留痕：$text", text.contains("读取已保存 S3 凭据失败"))
        assertTrue(text.contains("IllegalStateException"))
    }
}
