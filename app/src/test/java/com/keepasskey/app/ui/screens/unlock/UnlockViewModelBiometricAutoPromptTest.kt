package com.keepasskey.app.ui.screens.unlock

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * ISSUE-P3-01 解锁页生物识别自动唤起单测（含 ISSUE-P3-14 的消费侧资源映射验证）。
 *
 * 覆盖验收标准：
 * 1. 开关开启 + 存在已封印凭据时，进入解锁页自动唤起意图**恰好一次**；
 * 2. 生物识别成功后走**既有**解锁管线（解封主密码 → `unlockActiveDatabase` → 解锁成功事件）；
 * 3. 用户主动取消后回落主密码输入框，且不再自动重试（状态机终态，无死循环）。
 *
 * 测试边界如实说明：Android `BiometricPrompt` 与宿主 `FragmentActivity` 无法在 JVM 单测中构造，
 * 故「认证通过/取消/系统错误」三条结果路径经 [UnlockViewModel.handleBiometricResult]
 * （与系统回调解耦的可测入口）驱动；解密环节以 JDK AES-GCM 真实实现完成，
 * 覆盖「解封 → 解锁」的真实数据通路；`unlockWithBiometric` 启动侧仅保留 Activity 与硬件依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelBiometricAutoPromptTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 测试替身 ────────────────────────────────────────────────────────────

    /** 活动数据库 id（对齐 [FakeVaultRepository.initialMockDatabases] 中 isActive 的库） */
    private val activeDbId = "db_personal"

    /**
     * JVM 内存版封印凭据存储：`SharedPreferences` 以 `java.lang.reflect.Proxy` 内存实现替换，
     * 其余（IV/密文 Base64 编解码、按库存在性判定）全部走生产实现，保证与真机同源语义。
     */
    private class InMemorySealedCredentialStore {
        private val entries = mutableMapOf<String, Any?>()

        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    entries[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    entries.remove(args[0] as String)
                    proxy
                }
                "clear" -> {
                    entries.clear()
                    proxy
                }
                "apply", "commit" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor

        private val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> (entries[args[0] as String] as? String) ?: args[1] as? String
                "contains" -> entries.containsKey(args[0] as String)
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences

        val storage: BiometricCredentialStorage = BiometricCredentialStorage(
            object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            },
            keystoreManager = null
        )
    }

    /**
     * 分帧发射的数据库仓库（P3-01 竞态回归锁）：先发「无活动库」帧（使设置流先抵达并完成判定），
     * 再于虚拟时间 +1ms 发真实库列表，复现真机「设置先到、封印凭据后到」的抵达顺序。
     */
    private class StagedDatabaseRepository(
        private val delegate: VaultRepository,
        private val frames: List<List<VaultDatabaseInfo>>
    ) : VaultRepository by delegate {
        override fun getDatabases(): Flow<List<VaultDatabaseInfo>> = flow {
            frames.forEach { frame ->
                emit(frame)
                delay(1L)
            }
        }
    }

    /** 记录解封后送达仓库的主密码，用于断言生物识别成功确实走既有解锁管线（仅测试用） */
    private class RecordingVaultRepository(private val delegate: VaultRepository) : VaultRepository by delegate {
        var lastUnlockPassword: String? = null
            private set

        override suspend fun unlockActiveDatabase(
            passwordChars: CharArray,
            keyFileData: ByteArray?,
            readOnly: Boolean
        ): KdbxResult<Unit> {
            lastUnlockPassword = String(passwordChars)
            return delegate.unlockActiveDatabase(passwordChars, keyFileData, readOnly)
        }
    }

    // ── 测试脚手架 ──────────────────────────────────────────────────────────

    private fun TestScope.createViewModel(
        settings: FakeSettingsRepository,
        storage: BiometricCredentialStorage?,
        repository: VaultRepository = FakeVaultRepository()
    ): UnlockViewModel {
        val viewModel = UnlockViewModel(
            vaultRepository = repository,
            settingsRepository = settings,
            // 生物识别管理器需宿主 Activity 与 Android Keystore，JVM 侧注入 null 以走 fail-closed 分支
            biometricAuthManager = null,
            biometricCredentialStorage = storage,
            debugLog = DebugLogBuffer()
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return viewModel
    }

    private suspend fun enabledSettings(): FakeSettingsRepository =
        FakeSettingsRepository().also { it.setBiometricEnabled(true) }

    /** 显式关闭生物识别开关（[com.keepasskey.app.data.repository.UserSettings] 出厂默认 biometricEnabled = true） */
    private suspend fun disabledSettings(): FakeSettingsRepository =
        FakeSettingsRepository().also { it.setBiometricEnabled(false) }

    /** 写入一份「已封印凭据」占位密文；真实解密语义在成功路径用例中另行以 JDK AES-GCM 构造 */
    private fun InMemorySealedCredentialStore.sealPlaceholderCredential() {
        storage.saveEncryptedCredential(activeDbId, ByteArray(12) { 1 }, ByteArray(32) { 2 })
    }

    // ── 验收标准 1：开关开启 + 已封印凭据 → 自动唤起恰好一次 ────────────────

    @Test
    fun `开关开启且有封印凭据时自动唤起意图恰好一次`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val viewModel = createViewModel(enabledSettings(), storage.storage)

        val state = viewModel.uiState.value
        assertTrue("设置中生物识别开关应已开启", state.isBiometricEnabled)
        assertTrue("应识别到已封印凭据", state.isQuickUnlockAvailable)
        assertEquals("应推导为快速解锁模式", UnlockMode.QUICK_UNLOCK, state.unlockMode)
        assertEquals(
            "应进入「待消费」状态等待 UI 一次性意图",
            BiometricAutoPrompt.PENDING,
            state.biometricAutoPrompt
        )

        // 第一次消费 = 真正发起一次自动唤起；第二次（重组 / 切后台回前台再次透传）必须为空操作
        assertTrue("首次自动唤起意图必须真正发起", viewModel.onBiometricAutoPromptRequested(null))
        assertFalse("自动唤起意图每次进入解锁页至多消费一次", viewModel.onBiometricAutoPromptRequested(null))
        assertEquals(BiometricAutoPrompt.CONSUMED, viewModel.uiState.value.biometricAutoPrompt)
    }

    @Test
    fun `设置开启但无封印凭据时不自动唤起`() = runTest {
        val viewModel = createViewModel(enabledSettings(), InMemorySealedCredentialStore().storage)

        assertEquals(BiometricAutoPrompt.IDLE, viewModel.uiState.value.biometricAutoPrompt)
        assertEquals(UnlockMode.STANDARD, viewModel.uiState.value.unlockMode)
        assertFalse("无凭据时不得发起自动唤起", viewModel.onBiometricAutoPromptRequested(null))
    }

    @Test
    fun `生物识别开关关闭时即便有封印凭据也不自动唤起`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        // 用户显式关闭开关（UserSettings 出厂默认 biometricEnabled = true，必须显式置 false）
        val viewModel = createViewModel(disabledSettings(), storage.storage)

        assertFalse("开关应处于关闭态", viewModel.uiState.value.isBiometricEnabled)
        assertTrue(viewModel.uiState.value.isQuickUnlockAvailable)
        assertEquals(UnlockMode.STANDARD, viewModel.uiState.value.unlockMode)
        assertEquals(BiometricAutoPrompt.IDLE, viewModel.uiState.value.biometricAutoPrompt)
        assertFalse(viewModel.onBiometricAutoPromptRequested(null))
    }

    /**
     * P3-01 根因回归锁：设置流先抵达、封印凭据后抵达（真机实测的失败顺序）时，
     * 自动唤起判定必须被重算并最终进入 PENDING（原实现终态由先到者定格，永不自动弹窗）。
     */
    @Test
    fun `设置先到封印凭据后到时仍会自动唤起`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val repository = StagedDatabaseRepository(
            delegate = FakeVaultRepository(),
            frames = listOf(emptyList(), FakeVaultRepository.initialMockDatabases)
        )
        val viewModel = createViewModel(enabledSettings(), storage.storage, repository)

        // 首帧无活动库：设置虽已抵达，也只能停在主密码模式
        assertEquals(UnlockMode.STANDARD, viewModel.uiState.value.unlockMode)
        assertEquals(BiometricAutoPrompt.IDLE, viewModel.uiState.value.biometricAutoPrompt)

        testScheduler.advanceUntilIdle()

        assertEquals(UnlockMode.QUICK_UNLOCK, viewModel.uiState.value.unlockMode)
        assertEquals(BiometricAutoPrompt.PENDING, viewModel.uiState.value.biometricAutoPrompt)
        assertTrue(viewModel.onBiometricAutoPromptRequested(null))
    }

    @Test
    fun `重新进入解锁页（新实例）仍会重新自动唤起一次`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val settings = enabledSettings()

        val first = createViewModel(settings, storage.storage)
        assertTrue(first.onBiometricAutoPromptRequested(null))
        assertFalse(first.onBiometricAutoPromptRequested(null))

        val reentered = createViewModel(settings, storage.storage)
        assertEquals(BiometricAutoPrompt.PENDING, reentered.uiState.value.biometricAutoPrompt)
        assertTrue(reentered.onBiometricAutoPromptRequested(null))
    }

    // ── 验收标准 3：用户取消 → 回落主密码输入且不自动重试 ───────────────────

    @Test
    fun `用户取消后回落主密码输入且不再自动重试`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val viewModel = createViewModel(enabledSettings(), storage.storage)
        assertTrue(viewModel.onBiometricAutoPromptRequested(null))

        viewModel.handleBiometricResult(BiometricResult.Cancelled, storage.storage, activeDbId, ByteArray(0))

        val afterCancel = viewModel.uiState.value
        assertEquals("取消后应回落主密码输入模式", UnlockMode.STANDARD, afterCancel.unlockMode)
        assertNull("用户主动取消不应残留错误提示", afterCancel.errorMessage)
        assertEquals(BiometricAutoPrompt.CONSUMED, afterCancel.biometricAutoPrompt)

        // 再次透传意图（模拟重组 / 切后台回前台 / onResume 类钩子）必须为空操作：
        // 无重新发起即不会重新写入失败提示，反证「取消 → 自动重弹 → 再取消」死循环不可达
        assertFalse(viewModel.onBiometricAutoPromptRequested(null))
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(UnlockMode.STANDARD, viewModel.uiState.value.unlockMode)
    }

    @Test
    fun `取消后手动切回快速解锁也不会再次自动唤起`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val viewModel = createViewModel(enabledSettings(), storage.storage)
        assertTrue(viewModel.onBiometricAutoPromptRequested(null))
        viewModel.handleBiometricResult(BiometricResult.Cancelled, storage.storage, activeDbId, ByteArray(0))

        viewModel.switchUnlockMode(UnlockMode.QUICK_UNLOCK)

        assertEquals(UnlockMode.QUICK_UNLOCK, viewModel.uiState.value.unlockMode)
        assertEquals(BiometricAutoPrompt.CONSUMED, viewModel.uiState.value.biometricAutoPrompt)
        assertFalse(viewModel.onBiometricAutoPromptRequested(null))
    }

    // ── ISSUE-P3-14：完整性风险态文案在消费侧按资源解析 ─────────────────────

    @Test
    fun `完整性风险失败映射到已资源化文案并回落主密码输入`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val viewModel = createViewModel(enabledSettings(), storage.storage)

        viewModel.handleBiometricResult(
            BiometricResult.Error(
                BiometricAuthManager.ERROR_INTEGRITY_BLOCKED,
                BiometricAuthManager.INTEGRITY_BLOCKED_DIAGNOSTIC
            ),
            storage.storage,
            activeDbId,
            ByteArray(0)
        )

        val state = viewModel.uiState.value
        assertEquals(
            "风险态必须经已资源化文案输出（中英双语由 strings.xml 承载）",
            R.string.sec_biometric_integrity_blocked,
            state.errorMessage?.resId
        )
        assertEquals("风险态必须回落主密码输入", UnlockMode.STANDARD, state.unlockMode)
    }

    // ── 验收标准 2：生物识别成功 → 走既有解锁路径 ──────────────────────────

    /**
     * 生物识别成功后：以**真实** JDK AES-GCM 解封封印主密码 → `unlockActiveDatabase` → 解锁成功事件。
     * 断言解密出的主密码确实送达仓库、且发出成功事件，证明未新造旁路解锁通道。
     */
    @Test
    fun `生物识别成功后经既有解锁管线解锁`() = runTest {
        val secret = "ValidMasterPass#123"
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = encryptCipher.iv
        val sealedCiphertext = encryptCipher.doFinal(secret.toByteArray(Charsets.UTF_8))

        val storage = InMemorySealedCredentialStore().also {
            it.storage.saveEncryptedCredential(activeDbId, iv, sealedCiphertext)
        }
        val repository = RecordingVaultRepository(FakeVaultRepository())
        val viewModel = createViewModel(enabledSettings(), storage.storage, repository)

        var unlocked = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event -> if (event is UnlockEvent.UnlockSuccess) unlocked = true }
        }
        testScheduler.runCurrent()

        assertTrue(viewModel.onBiometricAutoPromptRequested(null))

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        viewModel.handleBiometricResult(
            BiometricResult.Success(decryptCipher),
            storage.storage,
            activeDbId,
            sealedCiphertext
        )
        testScheduler.advanceUntilIdle()

        assertTrue("生物识别成功后必须发出解锁成功事件", unlocked)
        assertEquals("解封出的主密码必须送达既有解锁管线", secret, repository.lastUnlockPassword)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    /** 授权 Cipher 缺失（系统未回传 CryptoObject）时：不得解锁、不得发成功事件，且必须解除加载态 */
    @Test
    fun `授权Cipher缺失时不假解锁`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val viewModel = createViewModel(enabledSettings(), storage.storage)

        var unlocked = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event -> if (event is UnlockEvent.UnlockSuccess) unlocked = true }
        }
        testScheduler.runCurrent()

        viewModel.handleBiometricResult(
            BiometricResult.Success(null),
            storage.storage,
            activeDbId,
            ByteArray(0)
        )
        testScheduler.advanceUntilIdle()

        assertFalse("无授权 Cipher 时绝不发出解锁成功事件", unlocked)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `缺乏宿主Activity时fail-closed并回落主密码输入`() = runTest {
        val storage = InMemorySealedCredentialStore().also { it.sealPlaceholderCredential() }
        val viewModel = createViewModel(enabledSettings(), storage.storage)

        viewModel.unlockWithBiometric(null)

        val state = viewModel.uiState.value
        assertEquals(R.string.sec_biometric_auth_failed, state.errorMessage?.resId)
        assertEquals(UnlockMode.STANDARD, state.unlockMode)
        assertFalse(state.isLoading)
    }
}
