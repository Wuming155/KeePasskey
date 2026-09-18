package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.testutil.MainDispatcherGuard
import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.testutil.awaitOffMainComputation
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.vault.ExtendedSettingsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * ISSUE-P3-17 详情页 3 个显示偏好的**真实接线**单测：
 * `maskPasswordsDefault` / `maskTotpDefault`（初始遮掩态，且**不得**覆盖用户显式操作）
 * 与 `showGroupInEntry`（详情页所属分组路径）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailDisplayPreferencesTest {

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

    private fun TestScope.createViewModel(
        entryId: String = ENTRY_IN_DEV_GROUP,
        repository: FakeVaultRepository = FakeVaultRepository(),
        settingsSource: ExtendedSettingsSource = { ExtendedSettings() },
        // §171：会话锁定擦除用例需要真实会话（lock() 在未开库的实例上即会通知观测器）
        databaseSession: com.keepasskey.database.session.DatabaseSession? = null
    ): EntryDetailViewModel {
        val viewModel = EntryDetailViewModel(
            appContext = null,
            savedStateHandle = SavedStateHandle(mapOf("entryId" to entryId)),
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            clipboardSecurityManager = null,
            autofillBlocklistStore = AutofillBlocklistStore(null),
            stringsProvider = null,
            debugLog = null,
            customIconAdmin = null,
            displayDispatcher = UnconfinedTestDispatcher(testScheduler),
            extendedSettingsSource = settingsSource,
            databaseSession = databaseSession
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    // ===== maskPasswordsDefault：仅决定初始态 =====

    @Test
    fun `偏好默认遮掩时密码初始为掩码态`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskPasswordsDefault = true) }
        )

        assertFalse("maskPasswordsDefault=true 时初始应遮掩", viewModel.uiState.value.isPasswordVisible)
        assertNull(viewModel.uiState.value.revealedPassword)
    }

    @Test
    fun `偏好默认不遮掩时密码初始即为明文态`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskPasswordsDefault = false) }
        )

        assertTrue(viewModel.uiState.value.isPasswordVisible)
    }

    @Test
    fun `偏好默认不遮掩时进入页面按需解密出明文`() = runTest {
        val repository = FakeVaultRepository()
        repository.saveEntry(
            UiVaultEntry(
                id = PASSWORD_ENTRY_ID,
                title = "默认明文条目",
                username = "user",
                url = "https://example.com"
            ),
            FAKE_PASSWORD.toCharArray(),
            null,
            emptyMap()
        )
        val viewModel = createViewModel(
            entryId = PASSWORD_ENTRY_ID,
            repository = repository,
            settingsSource = { ExtendedSettings(maskPasswordsDefault = false) }
        )

        viewModel.onScreenEntered()
        testScheduler.runCurrent()
        // ISSUE-P2-58 AC④：按需解密 + 熵估算已移出主线程，须等真实线程回写后再断言
        testScheduler.awaitOffMainComputation {
            viewModel.uiState.value.revealedPassword == FAKE_PASSWORD
        }

        assertTrue(viewModel.uiState.value.isPasswordVisible)
        assertEquals(
            "偏好声明默认明文时，「可见」必须真有明文，不能只翻标志位",
            FAKE_PASSWORD,
            viewModel.uiState.value.revealedPassword
        )
    }

    @Test
    fun `偏好默认遮掩时进入页面不预解密任何明文`() = runTest {
        // 安全回归：默认遮掩（生产默认值）下不得因进入页面而无授权预解密
        val repository = FakeVaultRepository()
        repository.saveEntry(
            UiVaultEntry(
                id = PASSWORD_ENTRY_ID,
                title = "默认遮掩条目",
                username = "user",
                url = "https://example.com"
            ),
            FAKE_PASSWORD.toCharArray(),
            null,
            emptyMap()
        )
        val viewModel = createViewModel(
            entryId = PASSWORD_ENTRY_ID,
            repository = repository,
            settingsSource = { ExtendedSettings(maskPasswordsDefault = true) }
        )

        viewModel.onScreenEntered()
        testScheduler.runCurrent()
        // ISSUE-P2-58 AC④：解密已移出主线程——须让真实线程跑完再断言「无预解密」，
        // 否则该负例会被「尚未回写」的空窗期蒙混通过（`condition` 恒 false，等待到超时为止）
        testScheduler.awaitOffMainComputation(timeoutMs = 200) { false }

        assertFalse(viewModel.uiState.value.isPasswordVisible)
        assertNull(
            "默认遮掩时不得预解密：revealedPassword 必须为空",
            viewModel.uiState.value.revealedPassword
        )
    }

    @Test
    fun `用户手动展开后偏好快照再次刷新不得重新盖上`() = runTest {
        // 语义红线：偏好是「默认值」而非「强制覆盖」
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskPasswordsDefault = true) }
        )
        assertFalse(viewModel.uiState.value.isPasswordVisible)

        viewModel.togglePasswordVisibility()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPasswordVisible)

        // 设置流再次发射 / 页面重进刷新偏好（仍为 true = 默认遮掩）
        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertTrue(
            "偏好刷新不得覆盖用户本次会话的显式展开",
            viewModel.uiState.value.isPasswordVisible
        )
    }

    @Test
    fun `用户手动收起后偏好为不遮掩亦不得重新展开`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskPasswordsDefault = false) }
        )
        assertTrue(viewModel.uiState.value.isPasswordVisible)

        viewModel.togglePasswordVisibility()
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isPasswordVisible)

        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertFalse(
            "偏好刷新不得覆盖用户本次会话的显式收起",
            viewModel.uiState.value.isPasswordVisible
        )
    }

    // ===== maskTotpDefault =====

    @Test
    fun `偏好默认遮掩 TOTP 时验证码初始为掩码态`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskTotpDefault = true) }
        )

        assertFalse(viewModel.uiState.value.isTotpVisible)
    }

    @Test
    fun `偏好不遮掩 TOTP 时验证码初始可见`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskTotpDefault = false) }
        )

        assertTrue(viewModel.uiState.value.isTotpVisible)
    }

    @Test
    fun `用户展开 TOTP 后偏好刷新不得重新盖上`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskTotpDefault = true) }
        )
        assertFalse(viewModel.uiState.value.isTotpVisible)

        viewModel.toggleTotpVisibility()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isTotpVisible)

        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isTotpVisible)
    }

    @Test
    fun `切换条目后字段回到偏好声明的默认态`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(maskPasswordsDefault = true, maskTotpDefault = true) }
        )
        viewModel.togglePasswordVisibility()
        viewModel.toggleTotpVisibility()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPasswordVisible)
        assertTrue(viewModel.uiState.value.isTotpVisible)

        viewModel.setEntryId("1")
        testScheduler.runCurrent()

        assertFalse("切换条目后应回到偏好默认遮掩态", viewModel.uiState.value.isPasswordVisible)
        assertFalse(viewModel.uiState.value.isTotpVisible)
    }

    // ===== showGroupInEntry =====

    @Test
    fun `开启详情页分组标识时下发条目所属分组完整路径`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(showGroupInEntry = true) }
        )

        assertEquals(
            "工作与生产力 / 研发与基础设施",
            viewModel.uiState.value.groupPath
        )
    }

    @Test
    fun `关闭详情页分组标识时不下发分组路径`() = runTest {
        val viewModel = createViewModel(
            settingsSource = { ExtendedSettings(showGroupInEntry = false) }
        )

        assertNull(viewModel.uiState.value.groupPath)
    }

    @Test
    fun `开关变化经页面重进刷新后立即生效`() = runTest {
        var snapshot = ExtendedSettings(showGroupInEntry = false)
        val viewModel = createViewModel(settingsSource = { snapshot })
        assertNull(viewModel.uiState.value.groupPath)

        snapshot = ExtendedSettings(showGroupInEntry = true)
        viewModel.onScreenEntered()
        testScheduler.runCurrent()

        assertEquals("工作与生产力 / 研发与基础设施", viewModel.uiState.value.groupPath)
    }

    private companion object {
        /** 假数据中位于 group_work / group_dev 下的条目 id */
        const val ENTRY_IN_DEV_GROUP = "2"

        /** 预置密码的测试条目 id（用例自建，不与假数据冲突） */
        const val PASSWORD_ENTRY_ID = "pwd_entry_for_default_mask"

        /** 虚构假密码（非真实凭据，符合测试数据规约） */
        const val FAKE_PASSWORD = "Fake-Pwd-1"
    }
    /**
     * `ISSUE-P3-188` 剩余清单第 5 项（§171 补）：详情页 VM 的「锁定即擦除」回调路径此前**无宿主直调用例**
     * （五个 VM 里只有 `GeneratorViewModel` 与 `AutofillPickerViewModel` 有）。§170 把注册/注销三件套
     * 收敛成 `SessionLockGuard` 之后，成对性由 `SessionLockGuardTest` 把守，而**本 VM 传入的擦除动作**
     * 需要自己的用例：默认明文偏好下先按需解密，再触发 `lock()`，断言明文与强度读数一并撤回。
     *
     * 不在此断言 `isPasswordVisible`：`clearAll()` 会连 `passwordMaskOverride` 一起复位，
     * 而 `maskPasswordsDefault = false` 时按 [FieldMaskPolicy] 推导「仍应可见」——
     * 锁定后会话本身已无条目投影可解密，故这里只把「明文不得驻留」这一条钉住。
     */
    @Test
    fun `会话锁定后已按需解密出的密码明文与强度读数一并撤回`() = runTest {
        val repository = FakeVaultRepository()
        repository.saveEntry(
            UiVaultEntry(
                id = PASSWORD_ENTRY_ID,
                title = "锁定擦除用例条目",
                username = "user",
                url = "https://example.com"
            ),
            FAKE_PASSWORD.toCharArray(),
            null,
            emptyMap()
        )
        val session = com.keepasskey.database.session.DatabaseSession()
        val viewModel = createViewModel(
            entryId = PASSWORD_ENTRY_ID,
            repository = repository,
            settingsSource = { ExtendedSettings(maskPasswordsDefault = false) },
            databaseSession = session
        )

        viewModel.onScreenEntered()
        testScheduler.runCurrent()
        testScheduler.awaitOffMainComputation {
            viewModel.uiState.value.revealedPassword == FAKE_PASSWORD
        }
        assertEquals("前提：默认不遮掩偏好应已按需解密出明文", FAKE_PASSWORD, viewModel.uiState.value.revealedPassword)
        assertNotNull("前提：按需解密应同时给出真实熵读数", viewModel.uiState.value.passwordStrengthBits)

        session.lock()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertNull("锁定后必须撤回已揭示的密码明文（ISSUE-P2-65）", viewModel.uiState.value.revealedPassword)
        assertNull("锁定后必须一并撤回口令强度读数", viewModel.uiState.value.passwordStrengthBits)
    }
}
