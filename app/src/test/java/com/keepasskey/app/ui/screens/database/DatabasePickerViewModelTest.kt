package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeVaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * DatabasePickerViewModel 单元测试：
 * 覆盖密码库列表加载、新建库向导、导入外部库与多库切换选择。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): Pair<DatabasePickerViewModel, FakeVaultRepository> {
        val repo = FakeVaultRepository()
        val viewModel = DatabasePickerViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return Pair(viewModel, repo)
    }

    @Test
    fun `选择数据库触发选中事件并更新激活态`() = runTest {
        val (viewModel, _) = createViewModel()
        var selectedId: String? = null

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is DatabasePickerEvent.DatabaseSelected) {
                    selectedId = event.id
                }
            }
        }

        viewModel.selectDatabase("db_work")
        testScheduler.runCurrent()

        assertEquals("db_work", selectedId)
        val active = viewModel.uiState.value.databases.firstOrNull { it.isActive }
        assertEquals("db_work", active?.id)
    }

    @Test
    fun `新建密码库成功后关闭弹窗并弹出提示`() = runTest {
        val (viewModel, _) = createViewModel()

        viewModel.openCreateDialog()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showCreateDialog)

        val pwd = "TestPassword#2026".toCharArray()
        viewModel.createDatabase("new_secure_vault.kdbx", pwd, keyFile = false, preset = "ChaCha20 + Argon2id")
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.showCreateDialog)
        assertNotNull(viewModel.uiState.value.userMessage)
        assertEquals(R.string.db_picker_msg_created, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `导入外部密码库成功后关闭弹窗并弹出提示`() = runTest {
        val (viewModel, _) = createViewModel()

        viewModel.openOpenSourceDialog()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showOpenSourceDialog)

        viewModel.importDatabaseFromSource(
            OpenVaultSourceType.LOCAL,
            "external_vault.kdbx",
            "content://com.android.providers.downloads.documents/document/123"
        )
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.showOpenSourceDialog)
        assertNotNull(viewModel.uiState.value.userMessage)
        assertEquals(R.string.db_picker_msg_opened, viewModel.uiState.value.userMessage?.resId)
    }

    @Test
    fun `移除数据库成功后弹出提示`() = runTest {
        val (viewModel, _) = createViewModel()

        viewModel.removeDatabase("db_work")
        testScheduler.runCurrent()

        assertNotNull(viewModel.uiState.value.userMessage)
        assertEquals(R.string.db_picker_msg_removed, viewModel.uiState.value.userMessage?.resId)
        assertNull(viewModel.uiState.value.databases.find { it.id == "db_work" })
    }
}
