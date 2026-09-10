package com.keepasskey.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.vault.ExtendedSettingsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(
        entryId: String = ENTRY_IN_DEV_GROUP,
        repository: FakeVaultRepository = FakeVaultRepository(),
        settingsSource: ExtendedSettingsSource = { ExtendedSettings() }
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
            extendedSettingsSource = settingsSource
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
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
}
