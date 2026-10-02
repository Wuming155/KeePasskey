package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-437 AC①：解锁链阶段型过程文案的状态流转单测。
 *
 * 覆盖三条可 JVM 驱动的阶段路径：
 * - 主密码解锁：进入 unlockActiveDatabase 管线即呈现 [UnlockStage.DERIVING_KEYS]，成功/失败终态撤下；
 * - 密钥文件 SAF 现读：读取期间呈现 [UnlockStage.READING_KEY_FILE]，读取终态撤下；
 * - 生物识别解封收尾（completeBiometricUnlock）：同样呈现派生阶段并在成功后撤下。
 * （UNSEALING 阶段的挂出点在 unlockWithBiometric，依赖 Android Keystore 宿主，JVM 侧
 *  按 UnlockViewModelBiometricAutoPromptTest 同口径注入 null 走 fail-closed，真机验证覆盖。）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockStageFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    /** 派生闸门仓库：unlockActiveDatabase 挂起至放行，制造可观测的「派生中」窗口 */
    private class GatedVaultRepository(
        private val gate: CompletableDeferred<KdbxResult<Unit>>
    ) : VaultRepository by FakeVaultRepository() {
        override suspend fun unlockActiveDatabase(
            passwordChars: CharArray,
            keyFileData: ByteArray?,
            readOnly: Boolean
        ): KdbxResult<Unit> = gate.await()
    }

    /** 读取闸门通道：read 挂起至放行，制造可观测的「读取密钥文件中」窗口 */
    private class GatedKeyFileAccess(
        private val gate: CompletableDeferred<Unit>
    ) : KeyFileAccess by FakeKeyFileAccess() {
        override suspend fun read(uri: String): KeyFileReadResult {
            gate.await()
            return KeyFileReadResult.Success(ByteArray(8) { 7 }, "k.keyx")
        }
    }

    private fun TestScope.createViewModel(
        repository: VaultRepository = FakeVaultRepository(),
        keyFileAccess: KeyFileAccess? = null
    ): UnlockViewModel {
        val viewModel = UnlockViewModel(
            vaultRepository = repository,
            settingsRepository = FakeSettingsRepository(),
            biometricAuthManager = null,
            biometricCredentialStorage = null,
            debugLog = DebugLogBuffer(),
            cryptoDispatcher = testDispatcher,
            keyFileAccess = keyFileAccess
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        // _events 为零缓冲 SharedFlow：生物识别用例在测试体内直调 completeBiometricUnlock，
        // 成功路径的 events.emit 需有订阅者才不悬挂（主密码路径经 viewModelScope 子协程发射不受影响）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel)
    }

    @Test
    fun `主密码解锁派生期间呈现DERIVING_KEYS并在成功后撤下`() = runTest {
        val gate = CompletableDeferred<KdbxResult<Unit>>()
        val viewModel = createViewModel(repository = GatedVaultRepository(gate))

        viewModel.onPasswordChangeSecure("ValidMasterPass#123".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()

        // 派生管线挂起中：阶段文案已呈现且加载态在
        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(UnlockStage.DERIVING_KEYS, viewModel.uiState.value.loadStage)

        gate.complete(KdbxResult.Success(Unit))
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.loadStage)
    }

    @Test
    fun `主密码解锁失败后撤下阶段文案`() = runTest {
        val gate = CompletableDeferred<KdbxResult<Unit>>()
        val viewModel = createViewModel(repository = GatedVaultRepository(gate))

        viewModel.onPasswordChangeSecure("WrongPass#123".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()
        assertEquals(UnlockStage.DERIVING_KEYS, viewModel.uiState.value.loadStage)

        gate.complete(
            KdbxResult.Failure(
                com.keepasskey.database.exception.KdbxInvalidCredentialsException("主密码错误"),
                "主密码错误"
            )
        )
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.loadStage)
    }

    @Test
    fun `密钥文件现读期间呈现READING_KEY_FILE并在读取终态撤下`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val viewModel = createViewModel(keyFileAccess = GatedKeyFileAccess(gate))

        viewModel.onKeyFileSelected("content://test/key.keyx")
        testScheduler.runCurrent()

        assertEquals(UnlockStage.READING_KEY_FILE, viewModel.uiState.value.loadStage)

        gate.complete(Unit)
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.loadStage)
        assertTrue(viewModel.uiState.value.hasKeyFile)
    }

    @Test
    fun `生物识别解封收尾呈现DERIVING_KEYS并在成功后撤下`() = runTest {
        val gate = CompletableDeferred<KdbxResult<Unit>>()
        val viewModel = createViewModel(repository = GatedVaultRepository(gate))

        // 仅主密码的 v1 载荷（不带密钥文件）：会话驻留也没有时不现读（无记忆记录静默跳过），
        // 直接进入 unlockActiveDatabase 管线 → 呈现派生阶段。
        // BiometricCredentialStorage 构造期即 getSharedPreferences（PREFS_NAME 常量键，无敏感读写），
        // 故注入内存替身而非抛异常桩——本用例只驱动阶段流转，不解封真实凭据。
        val payload = BiometricSealedPayloadCodec.encode("SealedPass#123".toCharArray(), null)
        val storage = BiometricCredentialStorage(
            com.keepasskey.app.testutil.InMemorySharedPreferences().context(),
            keystoreManager = null
        )
        // 与主密码路径同型：completeBiometricUnlock 是挂起函数（管线内 gate.await 挂起），
        // 必须经后台协程驱动、由闸门放行推进——测试体内直调会自锁（await 等自己 complete）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.completeBiometricUnlock(payload, storage, "db-1")
        }
        testScheduler.runCurrent()

        // 生产链中 isLoading 由前置段 unlockWithBiometric（UNSEALING）置位；本用例直调的
        // 解封收尾缝只负责阶段置位（DERIVING_KEYS）与终态撤下，不重复承载 isLoading
        assertEquals(UnlockStage.DERIVING_KEYS, viewModel.uiState.value.loadStage)

        gate.complete(KdbxResult.Success(Unit))
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.loadStage)
    }
}
