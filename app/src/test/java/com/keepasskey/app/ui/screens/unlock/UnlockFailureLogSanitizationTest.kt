package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F-25（P3）解锁失败日志的脱敏断言。
 *
 * 原实现把 `activeDb`（库 id）、`keyFileLen`（密钥文件字节数）与异常原文 `message` 一并写进
 * **可导出**的调试日志缓冲：设置页「导出诊断日志」会把这些字段交给用户/支持方，
 * 泄漏「用户在解锁哪个库」「是否携带密钥文件及其大小」。
 *
 * 现口径收敛为「异常类名 + 布尔判定」，与仓内其它调用点一致
 * （例：`SafKeyFileAccess`「仅留痕异常类名，不外传 Uri / 异常 message」）。
 *
 * 断言直接面向 [DebugLogBuffer.exportText]（导出文本），即缺陷的实际暴露面。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockFailureLogSanitizationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `主密码解锁失败日志不含库 id 密钥文件长度与异常原文`() = runTest {
        val debugLog = DebugLogBuffer()
        val viewModel = createFailingViewModel(debugLog)

        // 模拟真实失败尝试：错误的密码 + 已选取的密钥文件（长度会被旧实现写进日志）
        viewModel.onKeyFileSelected(ByteArray(KEY_FILE_BYTES) { 0x2A }, KEY_FILE_NAME)
        viewModel.onPasswordChangeSecure("WrongPass#1".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        val exported = debugLog.exportText()

        // 正向：失败确实被留痕（脱敏不得演变为「静默无痕」）
        assertTrue(
            "失败留痕必须保留异常类名：$exported",
            exported.contains(EXPECTED_EXCEPTION_CLASS)
        )
        assertTrue(
            "失败留痕必须保留布尔判定（凭据错误标记）：$exported",
            exported.contains("invalidCreds=true")
        )

        // 反向：不得留库 id / 密钥文件长度字段 / 异常原文 message
        assertFalse("日志不得出现活动库 id：$exported", exported.contains(ACTIVE_DB_ID))
        assertFalse("日志不得出现库 id 字段名：$exported", exported.contains("activeDb"))
        assertFalse("日志不得出现密钥文件长度字段：$exported", exported.contains("keyFileLen"))
        assertFalse(
            "日志不得透传异常原文 message：$exported",
            exported.contains(INVALID_CREDENTIALS_MESSAGE)
        )
    }

    /** 导出前脱敏通道同样不得含库 id（URL/邮箱脱敏不覆盖库 id，故须源头不写入） */
    @Test
    fun `导出脱敏文本同样不含库 id`() = runTest {
        val debugLog = DebugLogBuffer()
        val viewModel = createFailingViewModel(debugLog)

        viewModel.onPasswordChangeSecure("WrongPass#1".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        assertFalse(
            "脱敏导出文本不得含库 id：${debugLog.exportSanitizedText()}",
            debugLog.exportSanitizedText().contains(ACTIVE_DB_ID)
        )
    }

    private fun TestScope.createFailingViewModel(debugLog: DebugLogBuffer): UnlockViewModel {
        // FakeVaultRepository 默认活动库 id = "db_personal"；forceInvalidCredentials 驱动认证失败分支
        val viewModel = UnlockViewModel(
            vaultRepository = FakeVaultRepository(forceInvalidCredentials = true),
            settingsRepository = FakeSettingsRepository(),
            biometricAuthManager = null,
            biometricCredentialStorage = null,
            debugLog = debugLog
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    private companion object {
        /** [FakeVaultRepository.initialMockDatabases] 中 isActive = true 的库 id（旧实现写入日志的字段） */
        const val ACTIVE_DB_ID = "db_personal"

        /** [com.keepasskey.database.exception.KdbxInvalidCredentialsException] 在失败 Fake 中的 message */
        const val INVALID_CREDENTIALS_MESSAGE = "主密码错误"

        /** 失败留痕保留的异常类名（simpleName，与仓内既有脱敏口径一致） */
        const val EXPECTED_EXCEPTION_CLASS = "KdbxInvalidCredentialsException"

        /** 密钥文件字节数：旧实现会写成 `keyFileLen=64` */
        const val KEY_FILE_BYTES = 64
        const val KEY_FILE_NAME = "vault.keyx"
    }
}
