package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * VaultListViewModel 状态流转单元测试：
 * 覆盖文件夹导航、搜索过滤、排序、批量管理、回收站操作、TOTP 实时倒计时与消息资源。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 创建被测 ViewModel 并在后台订阅 uiState 以驱动 stateIn 的 WhileSubscribed 上游计算
     */
    private fun TestScope.createSubscribedViewModel(): VaultListViewModel {
        val viewModel = VaultListViewModel(FakeVaultRepository(), FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `进入分组后展示该分组的条目`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("group_work", "group_dev"), state.breadcrumbs.map { it.id })
        assertEquals(setOf("2", "4", "6"), state.entries.map { it.id }.toSet())
    }

    @Test
    fun `面包屑导航与返回上一级`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_work")
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        var state = viewModel.uiState.value
        assertEquals(listOf("group_work", "group_dev"), state.breadcrumbs.map { it.id })

        viewModel.navigateUp()
        testScheduler.runCurrent()
        state = viewModel.uiState.value
        assertEquals("group_work", state.currentGroupId)

        viewModel.navigateToBreadcrumb(null)
        testScheduler.runCurrent()
        state = viewModel.uiState.value
        assertNull(state.currentGroupId)
    }

    @Test
    fun `搜索时全局匹配条目标题`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.onSearchQueryChange("github")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.entries.any { it.id == "2" })
    }

    @Test
    fun `按名称升序排序`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        viewModel.setSortOption(VaultSortOption.NAME_ASC)
        testScheduler.runCurrent()

        val titles = viewModel.uiState.value.entries.map { it.title }
        assertEquals(titles.sortedBy { it.lowercase() }, titles)
    }

    @Test
    fun `批量选择与批量移入回收站`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = VaultListViewModel(repository, FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_dev")
        viewModel.startBatchMode("2")
        viewModel.toggleEntrySelection("4")
        viewModel.batchDeleteSelected()
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isBatchMode)
        val entries = repository.getEntries().first()
        assertEquals("group_recycle_bin", entries.find { it.id == "2" }!!.groupId)
        assertEquals("group_recycle_bin", entries.find { it.id == "4" }!!.groupId)
    }

    @Test
    fun `取消全部选中时自动退出批量模式`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.startBatchMode("2")
        viewModel.toggleEntrySelection("2")
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isBatchMode)
    }

    @Test
    fun `回收站内还原条目`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = VaultListViewModel(repository, FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_recycle_bin")
        viewModel.restoreEntry("entry_recycled_1")
        testScheduler.runCurrent()

        val restored = repository.getEntries().first().find { it.id == "entry_recycled_1" }!!
        assertNull(restored.groupId)
    }

    @Test
    fun `彻底删除回收站条目`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = VaultListViewModel(repository, FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.enterGroup("group_recycle_bin")
        viewModel.purgeEntry("entry_recycled_1")
        testScheduler.runCurrent()

        assertNull(repository.getEntries().first().find { it.id == "entry_recycled_1" })
    }

    @Test
    fun `清空回收站`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = VaultListViewModel(repository, FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        repository.deleteEntry("1")

        viewModel.emptyRecycleBin()
        testScheduler.runCurrent()

        assertTrue(repository.getEntries().first().none { it.groupId == "group_recycle_bin" })
    }

    @Test
    fun `带 TOTP 的条目使用全局实时剩余秒数`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        val expected = (30 - ((System.currentTimeMillis() / 1000) % 30)).toInt()
        val totpEntry = state.entries.find { it.id == "2" }!!
        assertNotNull(totpEntry.totpCode)
        assertEquals(expected, totpEntry.totpRemainingSeconds)
        // 无 TOTP 的条目不受影响
        assertNull(state.entries.find { it.id == "6" }!!.totpCode)
    }

    @Test
    fun `TOTP 剩余秒数始终处于有效周期内`() = runTest {
        val viewModel = createSubscribedViewModel()

        viewModel.enterGroup("group_dev")
        advanceTimeBy(2500)
        testScheduler.runCurrent()

        val seconds = viewModel.uiState.value.entries.find { it.id == "2" }!!.totpRemainingSeconds
        assertTrue(seconds in 1..30)
    }

    @Test
    fun `复制密码与复制用户名的消息资源`() = runTest {
        val viewModel = createSubscribedViewModel()
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val entry = viewModel.uiState.value.entries.find { it.id == "2" }!!

        viewModel.copyPassword(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.vault_copy_password_done, viewModel.uiState.value.userMessage?.resId)
        viewModel.clearUserMessage()
        testScheduler.runCurrent()

        viewModel.copyUsername(entry)
        testScheduler.runCurrent()
        assertEquals(R.string.vault_copy_username_done, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `复制无用户名条目给出缺失提示`() = runTest {
        val viewModel = createSubscribedViewModel()
        viewModel.enterGroup("group_dev")
        testScheduler.runCurrent()
        val noteEntry = viewModel.uiState.value.entries.find { it.id == "6" }!!.copy(username = "")

        viewModel.copyUsername(noteEntry)
        testScheduler.runCurrent()

        assertEquals(R.string.vault_copy_username_missing, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `新建文件夹归属当前分组`() = runTest {
        val repository = FakeVaultRepository()
        val viewModel = VaultListViewModel(repository, FakeSettingsRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        viewModel.enterGroup("group_work")
        testScheduler.runCurrent()

        viewModel.createGroup("新建分组")
        testScheduler.runCurrent()

        val created = repository.getGroups().first().find { it.name == "新建分组" }
        assertNotNull(created)
        assertEquals("group_work", created!!.parentId)
    }
}
