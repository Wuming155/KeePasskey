package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.security.UnlockAuthPolicy
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import javax.crypto.Cipher

/**
 * ISSUE-P1-22 回归：软件级 Keystore 下快速解锁封印的「显式降级确认」闸门（JVM 侧）。
 *
 * 覆盖 AC③「SOFTWARE 等级 → 封印被拒」分支（注入假探测结果）与 AC② 行为面：
 * - SOFTWARE / UNKNOWN → 未经确认不封印（弹窗挂起、确认记录不置位）；
 * - 确认 → 记录持久化（常驻声明依据），流程继续；
 * - 拒绝 → 生物识别开关关闭、不留确认记录；
 * - 硬件落位（TEE）→ 无需确认直接放行；
 * - 确认超时 → fail-closed 跳过封印。
 *
 * 说明：单测中封印凭据存储注入为 null（无法在 JVM 构造 SharedPreferences 依赖），
 * 故确认后的流程在「缺少存储」守卫处安全终止——本类断言聚焦确认闸门本身；
 * 封印成功路径由既有 UnlockViewModel 流程与设备侧用例覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickUnlockSealDowngradeTest {

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

    // ── 策略纯函数：落位降级判定（唯一语义声明点） ─────────────────────────

    @Test
    fun `SOFTWARE与UNKNOWN须降级确认而硬件落位放行`() {
        assertTrue(UnlockAuthPolicy.requiresDowngradeConsent(KeystoreManager.KeySecurityLevel.SOFTWARE))
        assertTrue(UnlockAuthPolicy.requiresDowngradeConsent(KeystoreManager.KeySecurityLevel.UNKNOWN))
        assertFalse(UnlockAuthPolicy.requiresDowngradeConsent(KeystoreManager.KeySecurityLevel.TRUSTED_ENVIRONMENT))
        assertFalse(UnlockAuthPolicy.requiresDowngradeConsent(KeystoreManager.KeySecurityLevel.STRONGBOX))
    }

    // ── 注入假探测结果驱动 UnlockViewModel 全流程 ──────────────────────────

    /** 注入假封印密钥供给：桌面 JVM 可得 AES/GCM Cipher，落位等级由用例指定（AC③ 假探测） */
    private fun provisionOf(level: KeystoreManager.KeySecurityLevel): (String) -> SealedKeyProvision? {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return { _: String -> SealedKeyProvision(cipher, level) }
    }

    private fun TestScope.createViewModel(
        provision: (String) -> SealedKeyProvision?,
        settings: FakeSettingsRepository = FakeSettingsRepository()
    ): Pair<UnlockViewModel, FakeSettingsRepository> {
        // ISSUE-P2-212：封印登记的**前置条件**是「生物识别开关已开启」——出厂默认已改为关闭，
        // 本类聚焦封印流程中的降级闸门，故在此显式开启以保留各用例原有语义。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            settings.setBiometricEnabled(true)
        }
        val viewModel = UnlockViewModel(
            FakeVaultRepository(),
            settings,
            BiometricAuthManager(KeystoreManager(context = null)),
            null,
            DebugLogBuffer()
        )
        // ISSUE-P1-22 AC③：注入假封印密钥落位探测（替代真实 Keystore 探测）
        viewModel.installSealKeyProvisionForTest(provision)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        return MainDispatcherGuard.track(viewModel) to settings
    }

    /** 注册解锁成功事件采集器（跨确认弹窗挂起持续存活），返回时刻快照读取器 */
    private fun TestScope.collectUnlockSuccess(viewModel: UnlockViewModel): () -> Boolean {
        var unlocked = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { event ->
                if (event is UnlockEvent.UnlockSuccess) unlocked = true
            }
        }
        return { unlocked }
    }

    private fun TestScope.unlockWithMasterPassword(viewModel: UnlockViewModel) {
        viewModel.onPasswordChangeSecure("ValidMasterPass#123".toCharArray())
        viewModel.unlock()
        testScheduler.runCurrent()
    }

    private suspend fun FakeSettingsRepository.current(): UserSettings = getSettings().first()

    @Test
    fun `SOFTWARE等级未经确认封印被拒并请求显式降级确认`() = runTest {
        val (viewModel, settings) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.SOFTWARE))
        val unlocked = collectUnlockSuccess(viewModel)

        unlockWithMasterPassword(viewModel)

        assertFalse("未经确认不得完成快速解锁登记流程（解锁成功事件挂起）", unlocked())
        assertTrue(
            "SOFTWARE 落位必须请求显式降级确认（弹窗挂起）",
            viewModel.uiState.value.quickUnlockDowngradeConsentPending
        )
        assertFalse("确认记录在用户决定前不得置位", settings.current().quickUnlockDowngradeAcknowledged)
    }

    @Test
    fun `UNKNOWN等级同样请求降级确认`() = runTest {
        val (viewModel, _) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.UNKNOWN))

        unlockWithMasterPassword(viewModel)

        assertTrue(
            "UNKNOWN（无法证明硬件落位）与 SOFTWARE 同策略",
            viewModel.uiState.value.quickUnlockDowngradeConsentPending
        )
    }

    @Test
    fun `用户确认后记录持久化且流程继续`() = runTest {
        val (viewModel, settings) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.SOFTWARE))
        val unlocked = collectUnlockSuccess(viewModel)

        unlockWithMasterPassword(viewModel)
        assertTrue(viewModel.uiState.value.quickUnlockDowngradeConsentPending)

        viewModel.onQuickUnlockDowngradeDecision(true)
        testScheduler.runCurrent()

        assertTrue("确认后必须持久化降级确认记录（AC②留痕）", settings.current().quickUnlockDowngradeAcknowledged)
        assertFalse("确认后弹窗挂起态必须清除", viewModel.uiState.value.quickUnlockDowngradeConsentPending)
        assertTrue("确认后登记流程继续，本次主密码解锁照常完成", unlocked())
    }

    @Test
    fun `用户拒绝后关闭生物识别且不留确认记录`() = runTest {
        val (viewModel, settings) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.SOFTWARE))
        val unlocked = collectUnlockSuccess(viewModel)

        unlockWithMasterPassword(viewModel)
        viewModel.onQuickUnlockDowngradeDecision(false)
        testScheduler.runCurrent()

        assertFalse("拒绝不得留下确认记录", settings.current().quickUnlockDowngradeAcknowledged)
        assertFalse("拒绝后生物识别开关应关闭（唯一可用路径已被拒绝）", settings.current().biometricEnabled)
        assertFalse(viewModel.uiState.value.quickUnlockDowngradeConsentPending)
        assertTrue("拒绝快速解锁不影响本次主密码解锁", unlocked())
    }

    @Test
    fun `已确认记录存在时SOFTWARE落位不再重复弹窗`() = runTest {
        val settings = FakeSettingsRepository()
        settings.setQuickUnlockDowngradeAcknowledged(true)
        val (viewModel, _) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.SOFTWARE), settings)
        val unlocked = collectUnlockSuccess(viewModel)

        unlockWithMasterPassword(viewModel)

        assertFalse("已有确认记录时不得再次弹窗", viewModel.uiState.value.quickUnlockDowngradeConsentPending)
        assertTrue(unlocked())
    }

    @Test
    fun `TEE硬件落位无需确认直接放行`() = runTest {
        val (viewModel, settings) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.TRUSTED_ENVIRONMENT))
        val unlocked = collectUnlockSuccess(viewModel)

        unlockWithMasterPassword(viewModel)

        assertFalse("硬件落位不得弹降级确认", viewModel.uiState.value.quickUnlockDowngradeConsentPending)
        assertFalse(settings.current().quickUnlockDowngradeAcknowledged)
        assertTrue("硬件落位不阻断解锁流程", unlocked())
    }

    @Test
    fun `降级确认超时未决时fail-closed跳过封印`() = runTest {
        val (viewModel, settings) =
            createViewModel(provisionOf(KeystoreManager.KeySecurityLevel.SOFTWARE))
        val unlocked = collectUnlockSuccess(viewModel)

        unlockWithMasterPassword(viewModel)
        assertTrue(viewModel.uiState.value.quickUnlockDowngradeConsentPending)

        // 推进虚拟时间越过确认弹窗挂起上限（60s）→ 按未决处理，跳过封印
        advanceTimeBy(61_000L)
        testScheduler.runCurrent()

        assertFalse("超时后弹窗挂起态必须清除", viewModel.uiState.value.quickUnlockDowngradeConsentPending)
        assertFalse("超时不得留下确认记录", settings.current().quickUnlockDowngradeAcknowledged)
        assertTrue("超时未决不影响本次主密码解锁", unlocked())
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
