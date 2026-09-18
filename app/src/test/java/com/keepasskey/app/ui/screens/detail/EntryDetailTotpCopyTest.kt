package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.testutil.MainDispatcherGuard
import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-184 回归：详情页 TOTP「复制」必须**真实写入受保护剪贴板**。
 *
 * 缺陷背景：该按钮的 `onClick` 原本只 `onShowMessage(UiMessage(detail_totp_copied))`——
 * 既不取码也不写剪贴板，用户按「已复制」提示粘贴会贴出**上一条目**的内容（谎报成功）。
 *
 * 本用例锁定两条契约（不依赖 UI 渲染）：
 * 1. **成功路径**：确有待复制之码进入受保护通道，且提示为「已复制」；
 * 2. **失败路径**：取不到码时**既不写剪贴板、也不发成功提示**（改发失败文案）——
 *    「不谎报成功」正是该缺陷的本质，必须有负向断言钉住，否则改回空实现仍能过测。
 *
 * 夹具取自 `FakeVaultRepository` 的既有默认条目：`2` 带 TOTP（`849 201`），`3` 未配置 TOTP。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailTotpCopyTest {

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

    private fun TestScope.createViewModel(
        entryId: String,
        clipboard: ClipboardSecurityChannel
    ): EntryDetailViewModel {
        val viewModel = EntryDetailViewModel(
            appContext = null,
            savedStateHandle = SavedStateHandle(mapOf("entryId" to entryId)),
            vaultRepository = FakeVaultRepository(),
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = clipboard,
            autofillBlocklistStore = AutofillBlocklistStore(null),
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    @Test
    fun `复制 TOTP 会把当前有效码写入受保护剪贴板`() = runTest {
        val clipboard = RecordingClipboardChannel()
        val viewModel = createViewModel("2", clipboard)

        viewModel.copyTotpCode()
        testScheduler.runCurrent()

        assertEquals(1, clipboard.sensitiveCopyCount)
        // 夹具条目 `2` 的投影码为 "849 201"，仓库按需通道剥去分隔空格后交付
        assertEquals("849201", clipboard.lastSensitiveText)
        assertEquals(R.string.detail_totp_copied, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `条目未配置 TOTP 时不写剪贴板也不谎报成功`() = runTest {
        val clipboard = RecordingClipboardChannel()
        val viewModel = createViewModel("3", clipboard)

        viewModel.copyTotpCode()
        testScheduler.runCurrent()

        assertEquals(0, clipboard.sensitiveCopyCount)
        assertNull(clipboard.lastSensitiveText)
        // 负向钉死：绝不出现「已复制」这一成功文案
        assertEquals(R.string.detail_totp_copy_failed, viewModel.uiState.value.userMessage?.resId)
    }

    /** 记录型剪贴板通道：只记录调用与内容，不触碰真实 ClipboardManager。 */
    private class RecordingClipboardChannel : ClipboardSecurityChannel {
        var sensitiveCopyCount = 0
        var lastSensitiveText: CharSequence? = null

        override fun copySensitiveText(
            label: CharSequence,
            text: CharSequence,
            customTimeoutSeconds: Int?
        ) {
            sensitiveCopyCount++
            lastSensitiveText = text
        }

        override fun copySensitiveChars(
            label: CharSequence,
            chars: CharArray,
            customTimeoutSeconds: Int?
        ) {
            sensitiveCopyCount++
            lastSensitiveText = String(chars)
        }

        override fun copyPlainText(label: CharSequence, text: CharSequence) = Unit
    }
}
