package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-117 回归：`clearPasswordOnLeave` 的行为接线 + 未提交主密码驻留窗口。
 *
 * 原缺陷：该开关全仓 9 处命中**全是持久化 / 投影 / UI 回调，零行为消费方**；
 * 同时 `UnlockViewModel.passwordChars` 在提交前长期驻留（跨后台 / 旋转）。
 *
 * 断言口径：以「清空后再提交必命中『空密码』拦截」为**可观测证据**证明缓冲区确已清零
 * （[UnlockViewModel.passwordChars] 为私有，不引入测试专用后门）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelClearOnLeaveTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // 先取消本用例登记的 ViewModel 作用域、再恢复 Main（ISSUE-P3-189，见 MainDispatcherGuard）。
        MainDispatcherGuard.tearDown()
    }

    private fun TestScope.createViewModel(clearOnLeave: Boolean): UnlockViewModel {
        val extendedStore = ExtendedSettingsStore(null).apply {
            publish(ExtendedSettings(clearPasswordOnLeave = clearOnLeave))
        }
        val viewModel = UnlockViewModel(
            FakeVaultRepository(),
            FakeSettingsRepository(),
            null,
            null,
            DebugLogBuffer(),
            extendedSettingsStore = extendedStore
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    @Test
    fun `开关开启时离开页面清空未提交主密码`() = runTest {
        val viewModel = createViewModel(clearOnLeave = true)

        viewModel.onPasswordChangeSecure("Uncommitted#2026".toCharArray())
        viewModel.onScreenLeft()
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals(
            "开关开启时离开页面必须清空未提交主密码（缓冲区已空）",
            R.string.unlock_error_empty_password,
            viewModel.uiState.value.errorMessage?.resId
        )
    }

    @Test
    fun `开关关闭时离开页面保留已输入内容`() = runTest {
        val viewModel = createViewModel(clearOnLeave = false)

        viewModel.onPasswordChangeSecure("Kept#2026".toCharArray())
        viewModel.onScreenLeft()
        viewModel.unlock()
        testScheduler.runCurrent()

        assertNotEquals(
            "开关关闭时不得清空用户已输入内容（行为网关为开关本身）",
            R.string.unlock_error_empty_password,
            viewModel.uiState.value.errorMessage?.resId
        )
    }

    @Test
    fun `开关开启时离开页面驱动输入框擦除令牌`() = runTest {
        val viewModel = createViewModel(clearOnLeave = true)
        val before = viewModel.uiState.value.clearPasswordFieldToken

        viewModel.onPasswordChangeSecure("Uncommitted#2026".toCharArray())
        viewModel.onScreenLeft()

        assertEquals(
            "清空缓冲区必须同步递增擦除令牌（驱动输入框显示态归零）",
            before + 1,
            viewModel.uiState.value.clearPasswordFieldToken
        )
    }
}
