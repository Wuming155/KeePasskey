package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
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

    private fun TestScope.createViewModel(
        entryId: String?,
        repository: FakeVaultRepository = FakeVaultRepository(),
        blocklistStore: AutofillBlocklistStore = AutofillBlocklistStore(null)
    ): EntryDetailViewModel {
        val handle = if (entryId != null) SavedStateHandle(mapOf("entryId" to entryId)) else SavedStateHandle()
        val viewModel = EntryDetailViewModel(
            null, handle, repository, FakeSettingsRepository(), null, blocklistStore
        )
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
        val viewModel = EntryDetailViewModel(
            null, handle, FakeVaultRepository(), settings, null, AutofillBlocklistStore(null)
        )
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

    // ===== TASK-44：详情页「为本应用禁用自动填充」入口 =====

    private suspend fun TestScope.addAndroidBoundEntry(
        repository: FakeVaultRepository,
        packageName: String
    ): String {
        val entry = UiVaultEntry(
            id = "bound_app_entry",
            title = "手机银行",
            username = "user",
            url = "android://$packageName",
            category = EntryCategory.LOGIN
        )
        repository.saveEntry(entry, null, null, emptyMap())
        return entry.id
    }

    @Test
    fun `未绑定应用的条目不提供禁用填充入口`() = runTest {
        // 条目 "1" 为 https:// 站点凭据，无 android:// 绑定
        val viewModel = createViewModel("1")

        assertNull(viewModel.uiState.value.autofillBoundPackage)
        assertFalse(viewModel.uiState.value.isAutofillBlockedForApp)
    }

    @Test
    fun `绑定应用的条目可写入黑名单并回显屏蔽态`() = runTest {
        val repository = FakeVaultRepository()
        val store = AutofillBlocklistStore(null)
        val entryId = addAndroidBoundEntry(repository, "com.example.bank")
        val viewModel = createViewModel(entryId, repository, store)

        assertEquals("com.example.bank", viewModel.uiState.value.autofillBoundPackage)
        assertFalse(viewModel.uiState.value.isAutofillBlockedForApp)

        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()

        assertTrue(store.isBlocked("com.example.bank"))
        assertTrue(viewModel.uiState.value.isAutofillBlockedForApp)
    }

    @Test
    fun `再次点击即从黑名单移除并回显恢复`() = runTest {
        val repository = FakeVaultRepository()
        val store = AutofillBlocklistStore(null)
        val entryId = addAndroidBoundEntry(repository, "com.example.bank")
        val viewModel = createViewModel(entryId, repository, store)

        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()
        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()

        assertFalse(store.isBlocked("com.example.bank"))
        assertFalse(viewModel.uiState.value.isAutofillBlockedForApp)
        assertEquals(
            com.keepasskey.app.R.string.detail_autofill_unblocked,
            viewModel.uiState.value.userMessage?.resId
        )
    }
}
