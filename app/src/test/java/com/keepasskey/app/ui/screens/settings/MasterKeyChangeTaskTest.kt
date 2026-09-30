package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.BreachRangeClient
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P2-354 AC③`：更换主密钥任务移出 UI scope 后的忙态守卫与回执。
 *
 * 旧缺陷形态：提交后立即 onDismiss、任务跑在 `rememberCoroutineScope`（切 Tab 即取消）、
 * 可重复打开对话框重复提交。现任务挂 `SettingsViewModel.viewModelScope`，忙态与回执
 * 都住在 ViewModel——本类判据：
 * 1. 提交后 uiState 出现 `isChangingMasterKey = true`（对话框据此保持进度且禁提交/禁关闭）；
 * 2. 忙态中的**第二次提交被同步拒绝**且入参当场清零（数组所有权已移交）；
 * 3. 完成后忙态回落 + 成功回执落位 + 首个入参清零；
 * 4. 回执经 clearMasterKeyChangeFeedback 一次性清除。
 *
 * 仓库用门闩挂起，制造**真实的在途窗口**——否则任务瞬时完成，忙态断言会退化成
 * 「读到的永远是终态」的空转断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterKeyChangeTaskTest {

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
    fun `换密忙态：在途期间投影 busy、第二次提交被拒、完成后回执并清零`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeVaultRepository()
        val repository = object : VaultRepository by fake {
            override suspend fun changeMasterPassword(newPassword: CharArray): KdbxResult<Unit> {
                // 门闩挂起：把「任务在途」窗口拉长到断言可稳定观察
                gate.await()
                return fake.changeMasterPassword(newPassword)
            }
        }
        val viewModel = buildViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val first = "Master-Key-Fake-1".toCharArray()
        viewModel.changeMasterPassword(first)
        // 调度到 viewModelScope：任务启动并在门闩上挂起，busy 投影进 uiState
        testScheduler.runCurrent()
        assertTrue(
            "在途期间 uiState 必须呈现 isChangingMasterKey（对话框据此保持进度且禁重复提交）",
            viewModel.uiState.value.isChangingMasterKey
        )

        // 忙态中的第二次提交：同步拒绝 + 入参当场清零（所有权自调用起移交 VM）
        val second = "Master-Key-Fake-2".toCharArray()
        viewModel.changeMasterPassword(second)
        assertTrue("忙路径必须同步清零被拒入参", second.all { it == '0' })
        assertFalse(
            "在途入参不得在调用点被预清零（清零属任务的 finally）",
            first.all { it == '0' }
        )

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertFalse("完成后忙态必须回落", viewModel.uiState.value.isChangingMasterKey)
        assertEquals(
            "成功回执必须落位（既有 Snackbar 路径据此展示）",
            R.string.set_master_key_updated,
            viewModel.uiState.value.masterKeyChangeFeedback?.resId
        )
        assertTrue("任务完成后首个入参必须清零", first.all { it == '0' })

        viewModel.clearMasterKeyChangeFeedback()
        testScheduler.runCurrent()
        assertNull("回执展示后必须可清除（一次性消息语义）", viewModel.uiState.value.masterKeyChangeFeedback)
    }

    // ── ISSUE-P2-398：改密成功后的重封印挂点 ────────────────────────────

    @Test
    fun `换密成功后触发重封印且宿主与新密码快照透传`() = runTest {
        val repository = FakeVaultRepository()
        val resealCalls = mutableListOf<Pair<FragmentActivity?, CharArray>>()
        var intactAtReseal = false
        val controller = SettingsMasterKeyChangeController(repository, this) { activity, chars ->
            intactAtReseal = chars.all { it != '0' }
            resealCalls.add(activity to chars.copyOf())
        }

        val password = "Reseal-New-Pass#1".toCharArray()
        controller.submit(password, HOST_ACTIVITY)
        advanceUntilIdle()

        assertEquals("成功路径必须恰好触发一次重封印", 1, resealCalls.size)
        assertEquals("宿主 Activity 必须透传（BiometricPrompt 依赖）", HOST_ACTIVITY, resealCalls.single().first)
        assertArrayEquals("重封印必须拿到新密码内容", "Reseal-New-Pass#1".toCharArray(), resealCalls.single().second)
        assertTrue("重封印窗口内入参尚未被 finally 清零（唯一能以新密码重封印的时机）", intactAtReseal)
        assertTrue("任务收尾必须清零入参", password.all { it == '0' })
    }

    @Test
    fun `换密失败不触发重封印`() = runTest {
        val fake = FakeVaultRepository()
        val repository = object : VaultRepository by fake {
            override suspend fun changeMasterPassword(newPassword: CharArray): KdbxResult<Unit> =
                KdbxResult.Failure(IllegalStateException("boom"), "改密失败")
        }
        var resealCalls = 0
        val controller = SettingsMasterKeyChangeController(repository, this) { _, _ ->
            resealCalls++
        }

        val password = "Should-Not-Reseal".toCharArray()
        controller.submit(password, HOST_ACTIVITY)
        advanceUntilIdle()

        assertEquals("失败路径不得触发重封印（封印载荷只承载当前有效密码）", 0, resealCalls)
        assertTrue(password.all { it == '0' })
    }

    private suspend fun buildViewModel(repository: VaultRepository): SettingsViewModel {
        val context = InMemorySharedPreferences().context()
        val settingsRepository = FakeSettingsRepository().apply {
            // 关闭冷启动同步：本用例只验证换密任务态，不引入无关的联网动作
            setSyncOnColdStart(false)
        }
        val credentialsStore = SyncCredentialsStore(InMemorySharedPreferences().context(), null)
        val coordinator = SyncCoordinator(
            context,
            DatabaseSession(),
            credentialsStore,
            DebugLogBuffer(),
            TEST_STRINGS
        )
        return SettingsViewModel(
            settingsRepository,
            repository,
            credentialsStore,
            coordinator,
            DebugLogBuffer(),
            ExtendedSettingsStore(null),
            PeriodicSyncScheduler(context, ExtendedSettingsStore(null)),
            com.keepasskey.app.data.repository.AutofillBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillSaveBlocklistStore(null),
            com.keepasskey.app.autofill.AutofillFieldBlocklistStore(null),
            BreachCheckCoordinator(NoOpBreachRangeClient),
            databaseSession = DatabaseSession(),
            stringsProvider = TEST_STRINGS
        ).also { MainDispatcherGuard.track(it) }
    }

    private object NoOpBreachRangeClient : BreachRangeClient {
        override suspend fun queryRange(prefix: String): Set<String> = emptySet()
    }

    private companion object {
        val TEST_STRINGS = StringsProvider { _, _ -> "" }

        /** JVM 可构造的宿主替身：仅作引用透传判据，不触达真实 BiometricPrompt */
        val HOST_ACTIVITY: FragmentActivity? = null
    }
}
