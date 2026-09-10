package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-04 密钥文件导入与记忆链路单测（app 层）：
 * 覆盖 a) 偏好开关对「记住/不记住」的控制；b) 持久化授权失效时静默降级为未记住；
 * c) UiState「已选择 / 未选择 / 清空」语义；d) 密钥文件因子是否真实进入复合密钥通道。
 *
 * 全部使用虚构的假密钥文件字节与假凭据，绝不使用真实密码或真实密钥文件。
 * SAF 选择器交互本身（OpenDocument / ContentResolver）无法在 JVM 单测中执行，
 * 见交接文档「未验证（需真机）」标注。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockKeyFileFlowTest {

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
        access: KeyFileAccess?,
        repo: FakeVaultRepository = FakeVaultRepository()
    ): UnlockViewModel {
        val viewModel = UnlockViewModel(
            vaultRepository = repo,
            settingsRepository = FakeSettingsRepository(),
            biometricAuthManager = null,
            biometricCredentialStorage = null,
            debugLog = DebugLogBuffer(),
            keyFileAccess = access
        )
        // 订阅 uiState，驱动 init 中的数据库流与密钥文件恢复协程
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    private fun newAccess(
        rememberEnabled: Boolean = true,
        persistPermissionSucceeds: Boolean = true,
        permissionValid: Boolean = true
    ) = FakeKeyFileAccess(
        settings = FakeSettingsRepository(),
        rememberEnabled = rememberEnabled,
        persistPermissionSucceeds = persistPermissionSucceeds,
        permissionValid = permissionValid
    )

    // ── a) 偏好开关决定是否记住（验收标准 2）─────────────────────────────

    @Test
    fun `偏好开启时解锁成功记住密钥文件Uri与显示名`() = runTest {
        val access = newAccess(rememberEnabled = true)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        val viewModel = createViewModel(access)

        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()
        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals(
            "解锁成功后必须记住本次使用的密钥文件 Uri",
            FakeKeyFileAccess.KEY_FILE_URI,
            access.persistedKeyFileUri()
        )
        assertEquals(
            "记忆内容仅含非密钥元数据（显示名）",
            FakeKeyFileAccess.DISPLAY_NAME,
            access.persistedKeyFileName()
        )
    }

    @Test
    fun `偏好关闭时不申请持久授权也不记住密钥文件`() = runTest {
        val access = newAccess(rememberEnabled = false)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        val viewModel = createViewModel(access)

        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()

        assertTrue("偏好关闭不申请持久化读授权（最小权限）", access.permissionRequests.isEmpty())
        assertTrue("偏好关闭时密钥文件仍可用于本次解锁", viewModel.uiState.value.hasKeyFile)

        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals("偏好关闭时不得记住 Uri", "", access.persistedKeyFileUri())
        assertEquals("偏好关闭时应清除既有记录", 1, access.forgetCount)
    }

    @Test
    fun `偏好关闭时清除历史记忆记录`() = runTest {
        val access = newAccess(rememberEnabled = false)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        access.remember(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        val viewModel = createViewModel(access)

        // 偏好关闭：进入解锁页即不得恢复历史记录
        assertFalse("偏好关闭时不得恢复记忆的密钥文件", viewModel.uiState.value.hasKeyFile)
        assertNull("恢复行为静默，不弹错误", viewModel.uiState.value.errorMessage)

        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals("偏好关闭后不得残留任何密钥文件元数据", "", access.persistedKeyFileUri())
    }

    // ── b) 持久化授权失效 / 不可持久化时降级（验收标准 2 边界）───────────

    @Test
    fun `持久授权失效时恢复静默降级为未记住并清理记录`() = runTest {
        val access = newAccess(rememberEnabled = true, permissionValid = false)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        access.remember(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        val viewModel = createViewModel(access)

        val state = viewModel.uiState.value
        assertFalse("授权失效不得恢复密钥文件", state.hasKeyFile)
        assertEquals("授权失效不得回显文件名", "", state.keyFileName)
        assertNull("恢复为系统自动行为，静默降级不弹错误", state.errorMessage)
        assertEquals("授权失效应清理失效记录", 1, access.forgetCount)
    }

    @Test
    fun `提供方不支持持久授权时本次可用但不记忆并给出提示`() = runTest {
        val access = newAccess(rememberEnabled = true, persistPermissionSucceeds = false)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        val viewModel = createViewModel(access)

        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()

        assertTrue("本次解锁仍可用（字节已读入）", viewModel.uiState.value.hasKeyFile)
        assertEquals(
            "必须给出可理解的降级提示",
            R.string.keyfile_permission_not_persisted,
            viewModel.uiState.value.infoMessage?.resId
        )

        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals("不可持久授权时不得写入记忆", "", access.persistedKeyFileUri())
    }

    @Test
    fun `记忆的密钥文件已不可读时清除记录并降级为未记住`() = runTest {
        val access = newAccess(rememberEnabled = true, permissionValid = true)
        access.remember(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        // 未注册来源字节 → 读取返回 Unreadable（模拟文件被删）
        val viewModel = createViewModel(access)

        assertFalse(viewModel.uiState.value.hasKeyFile)
        assertNull("自动恢复失败不弹错误", viewModel.uiState.value.errorMessage)
        assertEquals(1, access.forgetCount)
        assertEquals("", access.persistedKeyFileUri())
    }

    // ── c) UiState 语义：未选择 / 已选择 / 清空（验收标准 1 状态面）──────

    @Test
    fun `未选择密钥文件时UiState为未选择语义`() = runTest {
        val viewModel = createViewModel(newAccess())

        val state = viewModel.uiState.value
        assertFalse(state.hasKeyFile)
        assertEquals("", state.keyFileName)
    }

    @Test
    fun `选择与清空密钥文件的UiState语义`() = runTest {
        val access = newAccess()
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        val viewModel = createViewModel(access)

        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.hasKeyFile)
        assertEquals(FakeKeyFileAccess.DISPLAY_NAME, viewModel.uiState.value.keyFileName)

        viewModel.clearKeyFile()
        testScheduler.runCurrent()

        assertFalse("清空后必须回到未选择语义", viewModel.uiState.value.hasKeyFile)
        assertEquals("", viewModel.uiState.value.keyFileName)
    }

    @Test
    fun `读取失败时UiState回到未选择并给出错误`() = runTest {
        val access = newAccess()
        // 未注册来源 → Unreadable
        val viewModel = createViewModel(access)

        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.hasKeyFile)
        assertEquals("", state.keyFileName)
        assertEquals(R.string.unlock_keyfile_read_failed, state.errorMessage?.resId)
    }

    @Test
    fun `记忆有效时自动恢复密钥文件并进入UiState`() = runTest {
        val access = newAccess(rememberEnabled = true, permissionValid = true)
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        access.remember(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.DISPLAY_NAME)
        val repo = FakeVaultRepository()
        val viewModel = createViewModel(access, repo)

        val state = viewModel.uiState.value
        assertTrue("授权有效时必须自动恢复", state.hasKeyFile)
        assertEquals(FakeKeyFileAccess.DISPLAY_NAME, state.keyFileName)
        assertEquals(
            R.string.keyfile_restored_from_memory,
            state.infoMessage?.resId
        )

        // 恢复的密钥文件必须真实进入解锁通道（复合密钥第二因子）
        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertArrayEquals(
            "恢复的密钥文件字节必须透传至解锁通道",
            FakeKeyFileAccess.FAKE_KEY_FILE_BYTES,
            repo.lastUnlockKeyFileData
        )
    }

    // ── d) 密钥文件因子真实进入复合密钥通道（验收标准 3）────────────────

    @Test
    fun `携带与不携带密钥文件时解锁通道收到不同因子`() = runTest {
        val access = newAccess()
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        val repo = FakeVaultRepository()
        val viewModel = createViewModel(access, repo)

        // 场景 1：仅主密码
        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()
        assertNull("未选择密钥文件时解锁通道必须收到 null 因子", repo.lastUnlockKeyFileData)

        // 场景 2：主密码 + 密钥文件（同一假主密码，仅密钥文件因子不同）
        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()
        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()
        assertArrayEquals(
            "选择密钥文件后解锁通道必须收到该密钥文件字节",
            FakeKeyFileAccess.FAKE_KEY_FILE_BYTES,
            repo.lastUnlockKeyFileData
        )
    }

    // ── 失败语义分型：主密码错 vs 密钥文件相关（验收标准 3）─────────────

    @Test
    fun `携带密钥文件时凭据失败给出复合密钥并列提示`() = runTest {
        val access = newAccess()
        access.putSource(FakeKeyFileAccess.KEY_FILE_URI, FakeKeyFileAccess.FAKE_KEY_FILE_BYTES)
        val viewModel = createViewModel(
            access,
            FakeVaultRepository(forceInvalidCredentials = true)
        )

        viewModel.onKeyFileSelected(FakeKeyFileAccess.KEY_FILE_URI)
        testScheduler.runCurrent()
        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals(
            "携带密钥文件时不得谎称「主密码错」，须给出并列可行动提示",
            R.string.keyfile_or_password_mismatch,
            viewModel.uiState.value.errorMessage?.resId
        )
    }

    @Test
    fun `未携带密钥文件时凭据失败仍为主密码提示`() = runTest {
        val viewModel = createViewModel(
            newAccess(),
            FakeVaultRepository(forceInvalidCredentials = true)
        )

        viewModel.onPasswordChangeSecure(FAKE_PASSWORD)
        viewModel.unlock()
        testScheduler.runCurrent()

        assertEquals(
            R.string.unlock_error_invalid_password,
            viewModel.uiState.value.errorMessage?.resId
        )
    }

    private companion object {
        /** 虚构假主密码（规则要求：单测绝不使用真实密码） */
        val FAKE_PASSWORD = "FakeMasterPass#1".toCharArray()
    }
}
