package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        Dispatchers.resetMain()
    }

    @Test
    fun `新建条目保存成功并发出 SaveSuccess 事件`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            repository
        )
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
}
