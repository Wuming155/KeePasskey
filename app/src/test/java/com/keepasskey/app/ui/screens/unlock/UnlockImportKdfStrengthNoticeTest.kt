package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxKdfStrengthAssessment
import com.keepasskey.database.file.KdbxKdfStrengthDimension
import com.keepasskey.database.file.KdbxKdfWeakness
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P2-87 回归：解锁页导入外部密码库后，工作因子低于**本应用建库默认强度**的
 * **非阻断提示**（[UnlockUiState.infoMessage]）。
 *
 * 为什么提示落在本页：解锁页是用户导入后**确定停留**的页面（选择器页随导入立即退栈），
 * 且本页 infoMessage 已有渲染点（`UnlockContentSections`）。本组用例锁定四条边界：
 * 1. 导入成功且低于基线 ⇒ 置 infoMessage，且**不得**同时置 errorMessage（成功仍是成功）；
 * 2. 达标 ⇒ 不置；
 * 3. **未能评估（null）⇒ 不置**（读取/解析失败不等于弱因子，不得谎报）；
 * 4. 导入失败 ⇒ 只置 errorMessage、**不置** infoMessage（即便仓库同时返回了弱因子结论）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockImportKdfStrengthNoticeTest {

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

    /** 低于基线的结论样本（内存维度不足），用于驱动告警分支 */
    private fun belowBaselineAssessment() = KdbxKdfStrengthAssessment(
        weaknesses = listOf(
            KdbxKdfWeakness(
                dimension = KdbxKdfStrengthDimension.ARGON2_MEMORY_BYTES,
                baselineValue = 64L * 1024 * 1024,
                actualValue = 8192L
            )
        )
    )

    /**
     * 导入恒失败的仓库：只覆写待测的失败分支，其余（含 [FakeVaultRepository.kdfStrengthAssessment]
     * 下发的评估结论）委托给内存 Fake（对齐既有测试替身 `VaultRepository by …` 写法）。
     */
    private class ImportFailingVaultRepository(
        delegate: FakeVaultRepository = FakeVaultRepository()
    ) : VaultRepository by delegate {

        override suspend fun importExternalDatabase(
            name: String,
            path: String,
            syncType: String
        ): KdbxResult<Unit> = KdbxResult.Failure(IllegalStateException("模拟导入失败"), "模拟导入失败")
    }

    private fun TestScope.createViewModel(repository: VaultRepository): UnlockViewModel {
        val viewModel = UnlockViewModel(repository, FakeSettingsRepository(), null, null, DebugLogBuffer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    @Test
    fun `导入成功且工作因子低于建库默认强度时置信息提示且不置错误提示`() = runTest {
        val repo = FakeVaultRepository().apply { kdfStrengthAssessment = belowBaselineAssessment() }
        val viewModel = createViewModel(repo)

        viewModel.importExternalDatabase("weak_vault.kdbx", "content://provider/documents/1")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(R.string.unlock_msg_weak_kdf, state.infoMessage?.resId)
        // 非阻断：成功路径不得同时给出错误提示
        assertNull("导入成功不得置 errorMessage", state.errorMessage)
        assertFalse("导入结束必须退出加载态", state.isLoading)
    }

    @Test
    fun `导入成功且工作因子达标时不置信息提示`() = runTest {
        val repo = FakeVaultRepository().apply {
            kdfStrengthAssessment = KdbxKdfStrengthAssessment.AT_OR_ABOVE_BASELINE
        }
        val viewModel = createViewModel(repo)

        viewModel.importExternalDatabase("strong_vault.kdbx", "/tmp/strong.kdbx")
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.infoMessage)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    /** 未评估（未注入评估结论 / 仓库返回 null）时不得告警——读取失败不等于低于基线。 */
    @Test
    fun `导入成功但未能评估工作因子时不置信息提示`() = runTest {
        val viewModel = createViewModel(FakeVaultRepository())

        viewModel.importExternalDatabase("unknown_vault.kdbx", "/tmp/unreadable.kdbx")
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.infoMessage)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    /**
     * 导入失败：只置 errorMessage。此处刻意让仓库**同时**能给出「低于基线」的结论，
     * 以证明失败分支根本不消费它——失败结论与强度提示互斥。
     */
    @Test
    fun `导入失败时只置错误提示不置信息提示`() = runTest {
        val repo = ImportFailingVaultRepository(
            FakeVaultRepository().apply { kdfStrengthAssessment = belowBaselineAssessment() }
        )
        val viewModel = createViewModel(repo)

        viewModel.importExternalDatabase("failing_vault.kdbx", "/tmp/failing.kdbx")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertEquals(R.string.op_failed, state.errorMessage?.resId)
        assertNull("失败分支不得置 infoMessage", state.infoMessage)
    }

    /** 弱因子提示不得残留成对「当前库」的误导：再次导入一个达标的库后旧提示被清除。 */
    @Test
    fun `再次导入达标的库会清除上一次的弱因子提示`() = runTest {
        val repo = FakeVaultRepository().apply { kdfStrengthAssessment = belowBaselineAssessment() }
        val viewModel = createViewModel(repo)

        viewModel.importExternalDatabase("weak_vault.kdbx", "/tmp/weak.kdbx")
        testScheduler.runCurrent()
        assertEquals(R.string.unlock_msg_weak_kdf, viewModel.uiState.value.infoMessage?.resId)

        repo.kdfStrengthAssessment = KdbxKdfStrengthAssessment.AT_OR_ABOVE_BASELINE
        viewModel.importExternalDatabase("strong_vault.kdbx", "/tmp/strong.kdbx")
        testScheduler.runCurrent()

        assertNull("达标导入后不得残留弱因子提示", viewModel.uiState.value.infoMessage)
    }
}
