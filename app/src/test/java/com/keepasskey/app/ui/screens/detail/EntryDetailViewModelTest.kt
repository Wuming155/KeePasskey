package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * EntryDetailViewModel 单元测试：
 * 覆盖无效 entryId 的空状态处理与密码复制提示消息的资源化生成。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(entryId: String?): EntryDetailViewModel {
        val handle = if (entryId != null) SavedStateHandle(mapOf("entryId" to entryId)) else SavedStateHandle()
        val viewModel = EntryDetailViewModel(null, handle, FakeVaultRepository(), FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `有效 entryId 加载对应条目`() = runTest {
        val viewModel = createViewModel("1")

        val entry = viewModel.uiState.value.entry
        assertNotNull(entry)
        assertEquals("1", entry!!.id)
    }

    @Test
    fun `无效 entryId 呈现空条目状态而非崩溃或回退`() = runTest {
        val viewModel = createViewModel("不存在的条目")

        assertNull(viewModel.uiState.value.entry)
    }

    @Test
    fun `缺失 entryId 参数呈现空条目状态`() = runTest {
        val viewModel = createViewModel(null)

        assertNull(viewModel.uiState.value.entry)
    }

    @Test
    fun `密码复制消息按剪贴板超时时长生成`() = runTest {
        // 默认 UserSettings 的剪贴板超时为 30 秒
        val viewModel = createViewModel("1")

        val message = viewModel.uiState.value.passwordCopyMessage
        assertEquals(com.keepasskey.app.R.string.detail_password_copied_timeout_seconds, message.resId)
        assertEquals(listOf(30), message.args)
    }

    @Test
    fun `剪贴板超时关闭时生成不清空提示`() = runTest {
        val settings = FakeSettingsRepository()
        settings.setClipboardTimeout(0)
        val handle = SavedStateHandle(mapOf("entryId" to "1"))
        val viewModel = EntryDetailViewModel(null, handle, FakeVaultRepository(), settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val message = viewModel.uiState.value.passwordCopyMessage
        assertEquals(com.keepasskey.app.R.string.detail_password_copied_no_clear, message.resId)
    }

    @Test
    fun `密码可见性切换`() = runTest {
        val viewModel = createViewModel("1")

        viewModel.togglePasswordVisibility()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isPasswordVisible)
        viewModel.togglePasswordVisibility()
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isPasswordVisible)
    }
}
