package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.data.repository.CreateKeyFileFactor
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.result.KdbxResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P2-354 AC①`：建库防连点（busy 守卫 + `isLoading` 真实投影）。
 *
 * 旧缺陷形态：向导确认按钮仅 `enabled = isFormValid`、ViewModel 无忙守卫——
 * Argon2 派生的秒级窗口内连点会并发建库。本类判据：
 * 1. 提交后 `isLoading` 在**任务在途期间**为 true（页面进度与向导 busy 的真相源）；
 * 2. 忙窗口内的第二次 `createDatabase` 被同步拒绝（门闩未放行前到达的连点）；
 * 3. 放行后只存在第一个库——第二个名字若出现即守卫失效。
 *
 * 仓库用门闩挂起制造真实在途窗口：无门闩时任务瞬时完成，「第二次被拒」与
 * 「两次都成功」在断言面上不可区分（守卫失效也会因为两次都完成而仅靠顺序侥幸通过）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePickerCreateBusyTest {

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
    fun `建库在途呈现 isLoading 且连点第二次被拒只落一个库`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeVaultRepository()
        val repository = object : VaultRepository by fake {
            override suspend fun createDatabaseWithKeyFile(
                name: String,
                masterPassword: CharArray,
                keyFileFactor: CreateKeyFileFactor,
                preset: CreateVaultPreset,
                targetUri: String?
            ): KdbxResult<Unit> {
                // 门闩挂起：把「建库在途」窗口拉长到断言可稳定观察
                gate.await()
                return fake.createDatabaseWithKeyFile(name, masterPassword, keyFileFactor, preset, targetUri)
            }
        }
        val viewModel = DatabasePickerViewModel(repository)
        // MainDispatcherPollutionGuardTest 判据二：构造 ViewModel 必须登记守卫（收尾取消 viewModelScope）
        MainDispatcherGuard.track(viewModel)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        viewModel.openCreateDialog()

        // 第一次提交：isCreatingFlow 同步置位（守卫在协程之外，挡同帧双击）
        viewModel.createDatabase(
            "busy_guard_vault_a.kdbx",
            "Fake-Pw-A#2026".toCharArray(),
            keyFile = false,
            preset = CreateVaultPreset.CHACHA20_ARGON2ID
        )
        // 第二次提交：门闩尚未放行、任务在途——正是连点时间窗
        viewModel.createDatabase(
            "busy_guard_vault_b.kdbx",
            "Fake-Pw-B#2026".toCharArray(),
            keyFile = false,
            preset = CreateVaultPreset.CHACHA20_ARGON2ID
        )

        // 调度到在途：任务停在门闩上，isLoading 投影进 uiState
        testScheduler.runCurrent()
        assertTrue(
            "在途建库必须投影为 isLoading（页面进度与向导 busy 的真相源）",
            viewModel.uiState.value.isLoading
        )
        assertTrue("在途期间弹窗必须保持打开", viewModel.uiState.value.showCreateDialog)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val names = viewModel.uiState.value.databases.map { it.name }
        assertTrue("第一次提交必须落库", "busy_guard_vault_a.kdbx" in names)
        assertFalse(
            "忙窗口内的第二次提交必须被拒绝（出现即守卫失效/重复建库）",
            "busy_guard_vault_b.kdbx" in names
        )
        assertFalse("建库完成后 isLoading 必须回落", viewModel.uiState.value.isLoading)
        assertFalse("建库成功后弹窗必须关闭", viewModel.uiState.value.showCreateDialog)
    }
}
