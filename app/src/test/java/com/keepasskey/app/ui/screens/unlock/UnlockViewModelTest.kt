package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UnlockViewModel 单元测试：
 * 覆盖主密码校验、错误处理、QuickUnlock 模式切换与生物识别流程驱动。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): UnlockViewModel {
        val viewModel = UnlockViewModel(FakeVaultRepository(), FakeSettingsRepository(), null, null, com.keepasskey.app.data.logger.DebugLogBuffer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `空密码点击解锁提示错误`() = runTest {
        val viewModel = createViewModel()

        viewModel.onPasswordChangeSecure(CharArray(0))
        viewModel.unlock()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertEquals(com.keepasskey.app.R.string.unlock_error_empty_password, state.errorMessage?.resId)
    }
    /**
     * P1-10 回归锁：已选择密钥文件时空密码合法（仅密钥文件解锁，
     * 对齐官方 KeePass 解锁框对空密码不添加密码分量的语义）。
     */
    @Test
    fun `空密码加密钥文件允许仅密钥文件解锁`() = runTest {
        val viewModel = createViewModel()
        var unlocked = false

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) {
                    unlocked = true
                }
            }
        }

        viewModel.onKeyFileSelected(ByteArray(32) { it.toByte() }, "vault.keyx")
        viewModel.onPasswordChangeSecure(CharArray(0))
        viewModel.unlock()
        testScheduler.runCurrent()

        assertTrue(unlocked)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `输入有效主密码成功解锁`() = runTest {
        val viewModel = createViewModel()
        var unlocked = false

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) {
                    unlocked = true
                }
            }
        }

        viewModel.onPasswordChangeSecure("ValidMasterPass#123".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        assertTrue(unlocked)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `生物识别缺少宿主上下文时fail-closed不假解锁`() = runTest {
        val viewModel = createViewModel()
        var unlocked = false

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) {
                    unlocked = true
                }
            }
        }

        viewModel.unlockWithBiometric(null)
        testScheduler.runCurrent()

        assertFalse(unlocked)
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `解锁模式切换与密码显隐切换`() = runTest {
        val viewModel = createViewModel()

        viewModel.switchUnlockMode(UnlockMode.QUICK_UNLOCK)
        testScheduler.runCurrent()
        assertEquals(UnlockMode.QUICK_UNLOCK, viewModel.uiState.value.unlockMode)

        viewModel.switchUnlockMode(UnlockMode.STANDARD)
        testScheduler.runCurrent()
        assertEquals(UnlockMode.STANDARD, viewModel.uiState.value.unlockMode)

        assertFalse(viewModel.uiState.value.isPasswordVisible)
        viewModel.onTogglePasswordVisibility()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPasswordVisible)
    }

    @Test
    fun `无活动数据库时hasDatabase为false`() = runTest {
        val emptyRepo = FakeVaultRepository(initialDatabases = emptyList())
        val viewModel = UnlockViewModel(emptyRepo, FakeSettingsRepository(), null, null, com.keepasskey.app.data.logger.DebugLogBuffer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        assertFalse("无数据库时 hasDatabase 必须为 false", viewModel.uiState.value.hasDatabase)
        assertEquals("", viewModel.uiState.value.databaseName)
    }
}
