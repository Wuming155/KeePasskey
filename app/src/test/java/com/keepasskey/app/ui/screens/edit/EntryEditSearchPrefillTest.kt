package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-352 AC①：搜索空态「新建凭据条目」的一次性预填链路。
 *
 * 锁定三条语义：① 新建形态消费预填且**只消费一次**；② 编辑形态**只清不填**
 * （陈旧搜索词不得串进下一次无关新建）；③ 无预填通道（null，纯 JVM 单测同型）
 * 时新建行为与整改前一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditSearchPrefillTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `新建形态消费搜索词预填且只消费一次`() = runTest {
        val host = CreateEntryPrefillHost()
        host.publish("  login.example.com  ")
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            createEntryPrefill = host
        )
        MainDispatcherGuard.track(viewModel)

        assertEquals("login.example.com", viewModel.uiState.value.title)
        // 消费即清空：宿主内不再有残留
        assertNull(host.takeTitle())
    }

    @Test
    fun `带协议搜索词同步预填URL字段`() = runTest {
        val host = CreateEntryPrefillHost()
        host.publish("https://example.com/login")
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository(),
            createEntryPrefill = host
        )
        MainDispatcherGuard.track(viewModel)

        assertEquals("https://example.com/login", viewModel.uiState.value.title)
        assertEquals("https://example.com/login", viewModel.uiState.value.url)
    }

    @Test
    fun `编辑形态只清不填（陈旧预填不串入既有条目）`() = runTest {
        val host = CreateEntryPrefillHost()
        host.publish("stale-search-term")
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("entryId" to "1")),
            FakeVaultRepository(),
            createEntryPrefill = host
        )
        MainDispatcherGuard.track(viewModel)
        testScheduler.runCurrent()

        // 既有条目标题不被陈旧搜索词覆盖（FakeVaultRepository id=1 为既有条目）
        assertEquals("Google Workspace", viewModel.uiState.value.title)
        // 无论是否应用都已取走
        assertNull(host.takeTitle())
    }

    @Test
    fun `无预填时新建保持空白标题（预填通道缺席不改变既有行为）`() = runTest {
        val viewModel = EntryEditViewModel(
            SavedStateHandle(mapOf("groupId" to "group_work")),
            FakeVaultRepository()
        )
        MainDispatcherGuard.track(viewModel)

        assertEquals("", viewModel.uiState.value.title)
    }
}
