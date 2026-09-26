package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.testutil.MainDispatcherGuard
import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
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

    private companion object {
        /** 用例自建的条目 id，不与假数据里的条目 1 冲突 */
        const val LOCK_ENTRY_ID = "pwd_entry_for_session_lock"

        /** 虚构假密码（非真实凭据，符合测试数据规约） */
        const val LOADED_FAKE_PASSWORD = "Loaded-Fake-Pw-1"

        /** `clearAllSecrets()` / `fill('0')` 的擦除字符——是字符 `'0'`，不是空字符 */
        const val ERASED_CHAR = '0'
    }
}
