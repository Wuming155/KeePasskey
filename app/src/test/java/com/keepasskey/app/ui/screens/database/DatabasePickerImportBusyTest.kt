package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.sync.CloudVaultImportRequest
import com.keepasskey.app.sync.CloudVaultImportResult
import com.keepasskey.app.sync.CloudVaultImporter
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P3-582 AC③`：来源导入的**提交互斥**（busy 守卫 + `isLoading` 真实投影）。
 *
 * 旧缺陷形态：`DatabasePickerViewModel.importDatabaseFromSource` 为裸 `viewModelScope.launch`，
 * 对话框确认按钮的 `enabled` 只是 UI 层，挡不住重帧内的二次触发——云端下载（秒级网络操作）
 * 窗口内连点会并发导入。本类判据：
 * 1. 提交后 `isLoading` 在**任务在途期间**为 true（页面 busy 的真相源）；
 * 2. 忙窗口内的第二次提交被同步拒绝（导入器只被调用一次）；
 * 3. 放行后 `isLoading` 回落（守卫在 finally 复位）。
 *
 * 导入器用门闩挂起制造真实在途窗口：无门闩时任务瞬时完成，「第二次被拒」与「两次都执行」
 * 在断言面上不可区分。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerImportBusyTest {

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

    private fun webDavRequest() = CloudVaultImportRequest.WebDav(
        name = "busy_import_vault",
        url = "dav.example.com/dav/",
        username = "user@example.com",
        password = "pw".toCharArray(),
        remotePath = "mailbox/vault.kdbx"
    )

    @Test
    fun `导入在途呈现 isLoading 且连点第二次被拒`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var importCalls = 0
        val importer = object : CloudVaultImporter {
            override suspend fun import(request: CloudVaultImportRequest): CloudVaultImportResult {
                importCalls++
                gate.await()
                return CloudVaultImportResult.Failure(UiMessage(R.string.op_failed, isError = true))
            }
        }
        val viewModel = DatabasePickerViewModel(FakeVaultRepository(), cloudVaultImporter = importer)
        MainDispatcherGuard.track(viewModel)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        // 第一次提交：isImportingFlow 同步置位（守卫在协程之外，挡同帧双击）
        viewModel.importDatabaseFromSource(OpenVaultSubmission.Cloud(webDavRequest()))
        // 第二次提交：门闩尚未放行、任务在途——正是连点时间窗
        viewModel.importDatabaseFromSource(OpenVaultSubmission.Cloud(webDavRequest()))

        testScheduler.runCurrent()
        assertTrue(
            "在途导入必须投影为 isLoading（页面 busy 的真相源）",
            viewModel.uiState.value.isLoading
        )

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("忙窗口内的第二次提交必须被拒（导入器只应被调用一次）", 1, importCalls)
        assertFalse("导入结束后 isLoading 必须回落", viewModel.uiState.value.isLoading)
    }
}
