package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.testutil.MainDispatcherGuard
import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.UnsavedEditRegistry
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * EntryEditViewModel 保存链路单元测试（ISSUE-P2-03：原 FakeVaultRepository 自测重构为被测
 * ViewModel 行为验证）：
 * - 新建条目保存成功发出 SaveSuccess 事件并落库；
 * - 更新既有条目时由仓库契约自动归档一份历史修订（修订保留语义经 ViewModel 真实驱动验证）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditViewModelTest {

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

    @Test
    fun `新建条目保存成功并发出 SaveSuccess 事件`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            repository
        )
        MainDispatcherGuard.track(viewModel)
        val events = mutableListOf<EntryEditEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }

        viewModel.onTitleChange("新条目")
        viewModel.onUsernameChange("new-user")
        viewModel.saveEntry()
        testScheduler.runCurrent()

        assertEquals(listOf(EntryEditEvent.SaveSuccess), events)
        val stored = repository.getEntries().first().find { it.title == "新条目" }
        assertNotNull(stored)
        assertEquals("new-user", stored!!.username)
        assertEquals("group_work", stored.groupId)
    }

    @Test
    fun `更新既有条目时自动归档一份历史修订`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("entryId" to "1")),
            repository
        )
        MainDispatcherGuard.track(viewModel)
        testScheduler.runCurrent()
        assertEquals("Google Workspace", viewModel.uiState.value.title)

        val before = repository.getEntry("1").first()!!
        val originalUsername = before.username
        val originalRevisionCount = before.revisions.size

        viewModel.onTitleChange("改名条目")
        viewModel.saveEntry()
        testScheduler.runCurrent()

        val updated = repository.getEntry("1").first()!!
        assertEquals("改名条目", updated.title)
        // 仓库契约：更新保存自动归档修订，修订快照保留旧凭据字段
        assertEquals(originalRevisionCount + 1, updated.revisions.size)
        assertEquals(originalUsername, updated.revisions.first().username)
    }

    /**
     * `ISSUE-P3-188` 剩余清单第 4 项第二段（§190）：`EntryEditViewModel` 的「锁定即擦除」回调
     * （`SessionLockGuard(databaseSession) { clearAllSecrets() }`）此前无宿主直调用例。
     *
     * 三层证据，逐层加强：
     * 1. **预填通道置空**——`loadedPassword` 由 `clearAllSecrets()` 显式置 `null`；
     * 2. **数组本体填零**——只丢引用而不 `fill('0')`，明文仍会随数组副本留在堆上（本用例握着锁定前的
     *    引用，能区分这两种做法；`clearAllSecrets` 用的是字符 `'0'` 而非 `\u0000`）；
     * 3. **私有副本确实擦除**——编辑态口令只存在于私有 `passwordChars`，不进 UiState（只有
     *    `passwordLength` 进），故唯一可断言的观测点是**保存落库的内容**：若锁定只清了通道而没清私有
     *    副本，锁定后那次 `saveEntry()` 就会把明文再写回去。
     *
     * 不在此断言 `isDirty` / `passwordLength`：`clearAllSecrets()` 只擦明文副本、不回写 UiState，
     * 长度读数残留属既有呈现口径（长度非机密），钉在这里等于替该口径背书。
     */
    @Test
    fun `会话锁定后编辑态口令明文与预填通道一并擦除`() = runTest {
        val repository = FakeVaultRepository()
        repository.saveEntry(
            UiVaultEntry(
                id = LOCK_ENTRY_ID,
                title = "锁定擦除用例条目",
                username = "user",
                url = "https://example.com"
            ),
            LOADED_FAKE_PASSWORD.toCharArray(),
            null,
            emptyMap()
        )
        val session = DatabaseSession()
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("entryId" to LOCK_ENTRY_ID)),
            repository,
            databaseSession = session
        )
        MainDispatcherGuard.track(viewModel)
        testScheduler.runCurrent()

        val prefillBeforeLock = viewModel.loadedPassword.value
        assertNotNull("前提：既有条目口令应经一次性预填通道下发", prefillBeforeLock)
        assertEquals(
            "前提：私有副本长度应等于按需解密出的口令长度",
            LOADED_FAKE_PASSWORD.length,
            viewModel.uiState.value.passwordLength
        )
        assertEquals(
            "前提：预填通道里就是那段明文",
            LOADED_FAKE_PASSWORD,
            String(prefillBeforeLock!!)
        )

        session.lock()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertNull("锁定后预填通道必须置空", viewModel.loadedPassword.value)
        assertTrue(
            "锁定必须把预填数组本体填零，不得只丢引用",
            prefillBeforeLock.all { it == ERASED_CHAR }
        )

        viewModel.saveEntry()
        testScheduler.runCurrent()
        val storedAfterLock = repository.getEntryPasswordChars(LOCK_ENTRY_ID)
        assertTrue(
            "锁定后保存不得再带口令明文（证私有副本已擦除，而非仅清了预填通道）",
            storedAfterLock == null || storedAfterLock.isEmpty()
        )
    }

    /**
     * `PD-47`（`ISSUE-P3-332`）：设置仓库的「禁止截屏与录屏」开关必须流入
     * [EntryEditViewModel.flagSecureEnabled]——它是扫码取景对话框 `FLAG_SECURE` 的唯一取值来源，
     * 断流即遮罩恒开关无效（正是用户直报的失效形态）。同时锁定「未注入仓库时恒 `true`」的
     * fail-closed 缺省：缺省为 false 会让纯 JVM 单测形态默认放宽遮罩，违反保守方向。
     */
    @Test
    fun `防截屏开关经设置仓库流入状态流且缺省 fail-closed`() = runTest {
        val settings = FakeSettingsRepository()
        val viewModel = EntryEditViewModel(
            SavedStateHandle(),
            FakeVaultRepository(),
            settingsRepository = settings
        )
        MainDispatcherGuard.track(viewModel)
        testScheduler.runCurrent()
        assertTrue("出厂默认（UserSettings()）应为开启", viewModel.flagSecureEnabled.value)

        settings.setFlagSecureEnabled(false)
        testScheduler.runCurrent()
        assertFalse("关闭开关后状态流必须跟随为 false", viewModel.flagSecureEnabled.value)

        val noRepoViewModel = EntryEditViewModel(SavedStateHandle(), FakeVaultRepository())
        MainDispatcherGuard.track(noRepoViewModel)
        assertTrue(
            "未注入 SettingsRepository（纯 JVM 单测形态）必须恒 true（fail-closed，不因缺注入放宽遮罩）",
            noRepoViewModel.flagSecureEnabled.value
        )
    }

    /**
     * ISSUE-P2-355 AC③：编辑页必须向全局注册脏态提供者——锁定前「是否存在未保存编辑」
     * 的判定完全依赖本注册（security 侧不反向依赖 ui）。注册随 init 发生、随 onCleared 注销；
     * 本用例锁定「注册已发生 + 提供者随编辑翻转」的前半环（注销半环由 UnsavedEditRegistryTest 锁定）。
     */
    @Test
    fun `编辑页注册脏态提供者并随编辑翻转`() = runTest {
        val registry = UnsavedEditRegistry()
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            unsavedEditRegistry = registry
        )
        MainDispatcherGuard.track(viewModel)
        testScheduler.runCurrent()

        assertFalse("初始未编辑不得报告脏表单", registry.hasUnsavedEdits())

        viewModel.onTitleChange("脏态条目")

        assertTrue("编辑发生后必须报告脏表单", registry.hasUnsavedEdits())
    }

    /**
     * `ISSUE-P3-359` AC②：标题必填校验必须落到**字段级**错误位——保存被拒置位、
     * 用户重新输入即清除（标题框据此渲染 `isError + supportingText` 并聚焦）。
     * 与既有 Snackbar（`userMessage`）并存断言，锁住「消息不丢、字段位新增」两条线。
     */
    @Test
    fun `标题空白保存置字段级错误并随输入清除`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = EntryEditViewModel(SavedStateHandle(), repository)
        MainDispatcherGuard.track(viewModel)
        val events = mutableListOf<EntryEditEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }
        testScheduler.runCurrent()

        viewModel.saveEntry()
        testScheduler.runCurrent()

        assertTrue("标题空白被拒必须置字段级错误位", viewModel.uiState.value.titleError)
        assertEquals(
            "既有一次性消息通道必须保留（与 inline 错误并存）",
            R.string.edit_title_required,
            viewModel.uiState.value.userMessage?.resId
        )

        viewModel.onTitleChange("修复后的标题")
        assertFalse("任何标题输入必须即时清除字段级错误位", viewModel.uiState.value.titleError)

        viewModel.saveEntry()
        testScheduler.runCurrent()
        assertEquals("修复后保存必须放行", listOf(EntryEditEvent.SaveSuccess), events)
        assertNotNull("放行即落库并记下条目 id", viewModel.uiState.value.entryId)
        assertFalse("成功后错误位保持清除", viewModel.uiState.value.titleError)
    }

    /**
     * `ISSUE-P3-359` AC⑤：打开既有条目异步解密期间必须置 `isLoading`
     * （表单遮罩 + 进度的唯一驱动源），载入完成回落并填充表单；
     * 加载期保存请求被守卫拦下——表单数据尚不可信时不得提交。
     */
    @Test
    fun `打开既有条目载入期间置加载态并完成回落`() = runTest {
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("entryId" to "1")),
            FakeVaultRepository()
        )
        MainDispatcherGuard.track(viewModel)

        assertTrue(
            "loadEntry 在 init 内同步置位：构造返回时即应处于加载态（首帧即在遮罩之下）",
            viewModel.uiState.value.isLoading
        )

        viewModel.saveEntry()
        assertFalse("加载期保存必须被守卫拦截（不得进入忙态）", viewModel.uiState.value.isSaving)

        testScheduler.runCurrent()
        assertFalse("载入完成后加载态必须回落", viewModel.uiState.value.isLoading)
        assertEquals(
            "载入完成后表单应填充既有条目",
            "Google Workspace",
            viewModel.uiState.value.title
        )
    }

    /**
     * `ISSUE-P3-359` AC⑤ 的失败半环：条目不存在（被删除 / 无效 id）时
     * 加载态同样回落——否则遮罩永久悬挂，编辑页彻底不可用。
     */
    @Test
    fun `条目不存在时加载态同样回落不再悬挂`() = runTest {
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("entryId" to "no-such-id")),
            FakeVaultRepository()
        )
        MainDispatcherGuard.track(viewModel)
        assertTrue("无效 id 同样先进入加载态", viewModel.uiState.value.isLoading)

        testScheduler.runCurrent()
        assertFalse("未找到路径必须结束加载态（遮罩不得永久悬挂）", viewModel.uiState.value.isLoading)
    }

    private companion object {
        /** 用例自建的条目 id，不与假数据里的条目 1 冲突 */
        const val LOCK_ENTRY_ID = "pwd_entry_for_session_lock"

        /** 虚构假密码（非真实凭据，符合测试数据规约） */
        const val LOADED_FAKE_PASSWORD = "Loaded-Fake-Pw-1"

        /** `clearAllSecrets()` / `fill('0')` 的擦除字符——是字符 `'0'`，不是空字符 */
        const val ERASED_CHAR = '0'
    }
}
