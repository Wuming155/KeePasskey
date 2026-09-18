package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.testutil.MainDispatcherGuard
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
import org.junit.Assert.assertNotEquals
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
        // 先取消本用例登记的 ViewModel 作用域、再恢复 Main（ISSUE-P3-189，见 MainDispatcherGuard）。
        MainDispatcherGuard.tearDown()
    }

    private fun TestScope.createViewModel(
        entryId: String?,
        repository: FakeVaultRepository = FakeVaultRepository(),
        blocklistStore: AutofillBlocklistStore = AutofillBlocklistStore(null),
        clipboardSecurityManager: com.keepasskey.app.security.ClipboardSecurityChannel? = null
    ): EntryDetailViewModel {
        val handle = if (entryId != null) SavedStateHandle(mapOf("entryId" to entryId)) else SavedStateHandle()
        val viewModel = EntryDetailViewModel(
            appContext = null,
            savedStateHandle = handle,
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = clipboardSecurityManager,
            autofillBlocklistStore = blocklistStore,
            // 与其余详情页用例一致：注入测试调度器，避免 flowOn(Dispatchers.Default) 的真实线程
            // 在校验结束后才回跳到已 resetMain 的 Main 上，产生跨用例的 UncaughtExceptionsBeforeTest
            displayDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
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
        MainDispatcherGuard.track(viewModel)
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

    // ===== ISSUE-P3-15：非法/缺失绑定包名的提示文案边界（不得谎报已屏蔽/已恢复） =====

    @Test
    fun `非法绑定包名时 toggle 不写黑名单且提示无法识别应用标识`() = runTest {
        val repository = FakeVaultRepository()
        val store = AutofillBlocklistStore(null)
        // 含连字符的包名可被 extractAndroidBoundPackage 解析（入口呈现），但 normalize 判定非法
        val entryId = addAndroidBoundEntry(repository, "com-example-bank")
        val viewModel = createViewModel(entryId, repository, store)

        assertEquals("com-example-bank", viewModel.uiState.value.autofillBoundPackage)
        // 前置事实：填充侧 fail-closed 已把不可识别包名判为「已屏蔽」——这正是文案会误报的根源
        assertTrue(store.isBlocked("com-example-bank"))

        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()

        // 不执行任何写操作（既不 add 也不 remove）
        assertTrue(store.blockedPackages.value.isEmpty())
        // 三态语义：仍不可识别，且填充侧 fail-closed 判定未被改写
        assertTrue(store.isBlocked("com-example-bank"))
        val resId = viewModel.uiState.value.userMessage?.resId
        assertEquals(com.keepasskey.app.R.string.autofill_block_unidentifiable_package, resId)
        // 不得产出任何「已屏蔽 / 已恢复」语义的用户可见输出
        assertNotEquals(com.keepasskey.app.R.string.detail_autofill_blocked, resId)
        assertNotEquals(com.keepasskey.app.R.string.detail_autofill_unblocked, resId)
    }

    @Test
    fun `缺失绑定包名时 toggle 不写黑名单且提示无法识别应用标识`() = runTest {
        val repository = FakeVaultRepository()
        val store = AutofillBlocklistStore(null)
        // "android://" 绑定为空包名 → autofillBoundPackage 为 null（入口本不呈现，防御路径亦不得谎报）
        val entryId = addAndroidBoundEntry(repository, "")
        val viewModel = createViewModel(entryId, repository, store)

        assertNull(viewModel.uiState.value.autofillBoundPackage)

        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()

        assertTrue(store.blockedPackages.value.isEmpty())
        assertFalse(viewModel.uiState.value.isAutofillBlockedForApp)
        assertEquals(
            com.keepasskey.app.R.string.autofill_block_unidentifiable_package,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    @Test
    fun `合法绑定包名的 toggle 行为与文案保持改动前语义`() = runTest {
        val repository = FakeVaultRepository()
        val store = AutofillBlocklistStore(null)
        val entryId = addAndroidBoundEntry(repository, "com.example.valid")
        val viewModel = createViewModel(entryId, repository, store)

        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()

        assertEquals(listOf("com.example.valid"), store.blockedPackages.value)
        assertTrue(viewModel.uiState.value.isAutofillBlockedForApp)
        assertEquals(
            com.keepasskey.app.R.string.detail_autofill_blocked,
            viewModel.uiState.value.userMessage?.resId
        )

        viewModel.toggleAutofillBlockForApp()
        testScheduler.runCurrent()

        assertTrue(store.blockedPackages.value.isEmpty())
        assertFalse(viewModel.uiState.value.isAutofillBlockedForApp)
        assertEquals(
            com.keepasskey.app.R.string.detail_autofill_unblocked,
            viewModel.uiState.value.userMessage?.resId
        )
    }

    // ===== ISSUE-P1-25：用户名复制的通道分流（口令面引用 → 敏感通道） =====

    /** 记录桩：断言复制走了哪条通道（纯 JVM，不触碰 Android 剪贴板） */
    private class RecordingClipboardChannel : com.keepasskey.app.security.ClipboardSecurityChannel {
        var sensitiveCopies = 0
        var plainCopies = 0
        var lastPlain: CharSequence? = null

        override fun copySensitiveText(
            label: CharSequence,
            text: CharSequence,
            customTimeoutSeconds: Int?
        ) {
            sensitiveCopies++
        }

        override fun copySensitiveChars(
            label: CharSequence,
            chars: CharArray,
            customTimeoutSeconds: Int?
        ) {
            sensitiveCopies++
        }

        override fun copyPlainText(label: CharSequence, text: CharSequence) {
            plainCopies++
            lastPlain = text
        }
    }

    @Test
    fun `UserName 含口令面引用的复制走敏感通道并调度擦除`() = runTest {
        // ISSUE-P1-25 AC③：{REF:P@…} 即便被 P0-08 白名单掩码，复制也必须走敏感通道
        // （EXTRA_IS_SENSITIVE + 自动擦除调度由真实 ClipboardSecurityManager 在敏感通道内完成）
        val channel = RecordingClipboardChannel()
        val viewModel = createViewModel("1", clipboardSecurityManager = channel)

        viewModel.copyUsername("标题", "{REF:P@T:Secret}")
        testScheduler.runCurrent()

        assertEquals(1, channel.sensitiveCopies)
        assertEquals(0, channel.plainCopies)
    }

    @Test
    fun `UserName 检索面为P的引用同样走敏感通道`() = runTest {
        val channel = RecordingClipboardChannel()
        val viewModel = createViewModel("1", clipboardSecurityManager = channel)

        viewModel.copyUsername("标题", "{REF:U@P:secret}")
        testScheduler.runCurrent()

        assertEquals(1, channel.sensitiveCopies)
        assertEquals(0, channel.plainCopies)
    }

    @Test
    fun `UserName 无引用的复制行为不变走明文通道`() = runTest {
        val channel = RecordingClipboardChannel()
        val viewModel = createViewModel("1", clipboardSecurityManager = channel)

        viewModel.copyUsername("标题", "octocat")
        testScheduler.runCurrent()

        assertEquals(0, channel.sensitiveCopies)
        assertEquals(1, channel.plainCopies)
        assertEquals("octocat", channel.lastPlain)
    }
}
