package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.FakeUnlockThrottleStore
import com.keepasskey.app.security.UnlockThrottleManager
import com.keepasskey.app.security.UnlockThrottlePolicy
import com.keepasskey.app.security.UnlockThrottleRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * UnlockViewModel 单元测试：
 * 覆盖主密码校验、错误处理、QuickUnlock 模式切换与生物识别流程驱动。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): UnlockViewModel {
        val viewModel = UnlockViewModel(FakeVaultRepository(), FakeSettingsRepository(), null, null, com.keepasskey.app.data.logger.DebugLogBuffer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `空密码点击解锁提示错误`() = runTest {
        val viewModel = createViewModel()

        viewModel.onPasswordChangeSecure(CharArray(0))
        viewModel.unlock()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertEquals(com.keepasskey.app.R.string.unlock_error_empty_password, state.errorMessage?.resId)
    }
    /**
     * P1-10 回归锁：已选择密钥文件时空密码合法（仅密钥文件解锁，
     * 对齐官方 KeePass 解锁框对空密码不添加密码分量的语义）。
     */
    @Test
    fun `空密码加密钥文件允许仅密钥文件解锁`() = runTest {
        val viewModel = createViewModel()
        var unlocked = false

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) {
                    unlocked = true
                }
            }
        }

        viewModel.onKeyFileSelected(ByteArray(32) { it.toByte() }, "vault.keyx")
        viewModel.onPasswordChangeSecure(CharArray(0))
        viewModel.unlock()
        testScheduler.runCurrent()

        assertTrue(unlocked)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `输入有效主密码成功解锁`() = runTest {
        val viewModel = createViewModel()
        var unlocked = false

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) {
                    unlocked = true
                }
            }
        }

        viewModel.onPasswordChangeSecure("ValidMasterPass#123".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        assertTrue(unlocked)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `生物识别缺少宿主上下文时fail-closed不假解锁`() = runTest {
        val viewModel = createViewModel()
        var unlocked = false

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) {
                    unlocked = true
                }
            }
        }

        viewModel.unlockWithBiometric(null)
        testScheduler.runCurrent()

        assertFalse(unlocked)
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `解锁模式切换与密码显隐切换`() = runTest {
        val viewModel = createViewModel()

        viewModel.switchUnlockMode(UnlockMode.QUICK_UNLOCK)
        testScheduler.runCurrent()
        assertEquals(UnlockMode.QUICK_UNLOCK, viewModel.uiState.value.unlockMode)

        viewModel.switchUnlockMode(UnlockMode.STANDARD)
        testScheduler.runCurrent()
        assertEquals(UnlockMode.STANDARD, viewModel.uiState.value.unlockMode)

        assertFalse(viewModel.uiState.value.isPasswordVisible)
        viewModel.onTogglePasswordVisibility()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPasswordVisible)
    }

    @Test
    fun `无活动数据库时hasDatabase为false`() = runTest {
        val emptyRepo = FakeVaultRepository(initialDatabases = emptyList())
        val viewModel = UnlockViewModel(emptyRepo, FakeSettingsRepository(), null, null, com.keepasskey.app.data.logger.DebugLogBuffer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        assertFalse("无数据库时 hasDatabase 必须为 false", viewModel.uiState.value.hasDatabase)
        assertEquals("", viewModel.uiState.value.databaseName)
    }

    // ── ISSUE-P1-04：主密码解锁失败节流与无条件清零 ──────────────────────

    private fun TestScope.createThrottledViewModel(
        store: FakeUnlockThrottleStore,
        forceInvalidCredentials: Boolean
    ): UnlockViewModel {
        val manager = UnlockThrottleManager(store)
        val viewModel = UnlockViewModel(
            FakeVaultRepository(forceInvalidCredentials = forceInvalidCredentials),
            FakeSettingsRepository(),
            null,
            null,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            unlockThrottleManager = manager
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    @Test
    fun `主密码解锁失败后无条件清零驻留密码`() = runTest {
        val store = FakeUnlockThrottleStore()
        val viewModel = createThrottledViewModel(store, forceInvalidCredentials = true)

        viewModel.onPasswordChangeSecure("WrongPass#1".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(com.keepasskey.app.R.string.unlock_error_invalid_password, state.errorMessage?.resId)
        assertEquals(1, state.throttleFailureCount)
        assertEquals("失败后应递增擦除令牌驱动输入框清空", 1L, state.clearPasswordFieldToken)

        // 关键断言：passwordChars 已被无条件清零——不再输入直接重试必命中「空密码」拦截，
        // 反证失败态主密码不再滞留堆内存（原实现依赖 isLoading 判据致永不清零）
        viewModel.unlock()
        testScheduler.runCurrent()
        assertEquals(
            com.keepasskey.app.R.string.unlock_error_empty_password,
            viewModel.uiState.value.errorMessage?.resId
        )
    }

    @Test
    fun `连续失败累加计数并在达到阈值触发锁定`() = runTest {
        val store = FakeUnlockThrottleStore()
        val viewModel = createThrottledViewModel(store, forceInvalidCredentials = true)

        repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) {
            viewModel.onPasswordChangeSecure("WrongPass#1".toCharArray())
            viewModel.unlock()
            testScheduler.runCurrent()
        }

        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD, store.read("db_personal").failureCount)
        assertEquals(UnlockThrottlePolicy.FAILURE_THRESHOLD, viewModel.uiState.value.throttleFailureCount)
        assertTrue("达到阈值后应进入锁定", viewModel.uiState.value.throttleLockoutRemainingMs > 0L)
        assertEquals(
            com.keepasskey.app.R.string.unlock_error_locked_out_seconds,
            viewModel.uiState.value.errorMessage?.resId
        )

        // 锁定期内再次尝试：闸门 fail-closed，计数不再累加（不触碰解锁管线）
        viewModel.onPasswordChangeSecure("WrongPass#1".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()
        assertEquals(
            "锁定期内计数不得继续累加",
            UnlockThrottlePolicy.FAILURE_THRESHOLD,
            store.read("db_personal").failureCount
        )
    }

    @Test
    fun `锁定期内闸门拒绝解锁且不假成功`() = runTest {
        val store = FakeUnlockThrottleStore()
        store.seed(
            "db_personal",
            UnlockThrottleRecord(
                failureCount = 6,
                lockoutUntilEpochMs = System.currentTimeMillis() + 600_000L
            )
        )
        val viewModel = createThrottledViewModel(store, forceInvalidCredentials = false)
        var unlocked = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) unlocked = true
            }
        }

        // 即使输入「正确」主密码，锁定期内也必须被闸门拒绝（fail-closed）
        viewModel.onPasswordChangeSecure("CorrectPass#1".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        assertFalse("锁定期内不得解锁成功", unlocked)
        assertEquals(
            com.keepasskey.app.R.string.unlock_error_locked_out_minutes,
            viewModel.uiState.value.errorMessage?.resId
        )
        assertTrue("闸门拒绝路径亦应擦除输入框", viewModel.uiState.value.clearPasswordFieldToken > 0L)
    }

    @Test
    fun `成功解锁后节流计数归零`() = runTest {
        val store = FakeUnlockThrottleStore()
        store.seed("db_personal", UnlockThrottleRecord(failureCount = 3, lockoutUntilEpochMs = 0L))
        val viewModel = createThrottledViewModel(store, forceInvalidCredentials = false)
        var unlocked = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) unlocked = true
            }
        }

        viewModel.onPasswordChangeSecure("ValidMasterPass#123".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        assertTrue(unlocked)
        assertEquals(0, store.read("db_personal").failureCount)
        assertEquals(0, viewModel.uiState.value.throttleFailureCount)
        assertEquals(0L, viewModel.uiState.value.throttleLockoutRemainingMs)
    }
}
