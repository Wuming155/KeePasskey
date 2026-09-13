package com.keepasskey.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.biometric.BiometricManager
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.ui.screens.unlock.BiometricEnrollmentCoordinator
import com.keepasskey.app.ui.screens.unlock.SealedKeyProvision
import com.keepasskey.app.ui.screens.unlock.UnlockUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-P1-22 设备侧（instrumented）实测：软件级 Keystore 环境下封印路径的真实平台语义
 * （AC③ 的设备侧一层，与宿主 JVM 注入假探测的回归互补）。
 *
 * ## 为什么必须有这一层
 *
 * JVM 宿主无法触及 AndroidKeyStore：`probeSecurityLevel` 的真实落位等级、
 * `KeyGenParameterSpec`（仅强生物识别 + per-operation 授权）密钥生成对「已录入生物识别」的
 * 硬性要求、以及 `KeyInfo.securityLevel` 探测，只能由真实平台语义裁决。模拟器
 * （无 TEE / StrongBox）恰是本条目针对的 SOFTWARE 落位环境。
 *
 * ## 环境边界（如实声明）
 *
 * 模拟器**未录入** Class 3 强生物识别，而生产封印密钥规范
 * （`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`）在**生成时**即要求设备已录入
 * 强生物识别——故本用例在设备侧实测三件事：
 * 1. **落位探测基线**：真实 AndroidKeyStore 密钥在模拟器上的实测落位为 `SOFTWARE`
 *    （本条目问题前提在 Android 运行时成立，非推算）；
 * 2. **fail-closed 封印拒绝**：未录入强生物识别时，生产默认供给链（建钥）失败 →
 *    不请求降级确认、不建立封印（与 [UnlockAuthPolicy.canSeal] 的 fail-closed 结论一致）；
 * 3. **降级确认闸门**（注入假 `SOFTWARE` 落位）：真实 Android 运行时下「确认 → 记录持久化」
 *    与「拒绝 → 关闭生物识别且不留痕」两条分支闭环。
 * 确认弹窗与真实 BiometricPrompt 联动的封印落地链路，需已录入 Class 3 强生物识别的设备
 * （模拟器无法录入，emulator console 无 enroll 子命令）。
 */
@RunWith(AndroidJUnit4::class)
class QuickUnlockSealDowngradeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 完整性闸门假实现：固定放行（本用例不测完整性信号，仅满足构造依赖） */
    private class AllowAllIntegrityGate : RuntimeIntegrityGate {
        override fun currentEnforcement(): IntegrityEnforcement = IntegrityEnforcement.ALLOWED
        override suspend fun awaitEnforcement(): IntegrityEnforcement = IntegrityEnforcement.ALLOWED
    }

    /** 组装封印协调器（各用例共用；封印密钥供给替身由调用方按需注入） */
    private fun buildCoordinator(
        uiState: MutableStateFlow<UnlockUiState>,
        settings: RealSettingsRepository,
        authManager: BiometricAuthManager
    ): BiometricEnrollmentCoordinator = BiometricEnrollmentCoordinator(
        uiState = uiState,
        settingsRepository = settings,
        activeDbId = { DB_ID },
        keyFileBytes = { null },
        biometricAuthManager = authManager,
        biometricCredentialStorage = BiometricCredentialStorage(context),
        unlockPasskeyManager = null,
        debugLog = DebugLogBuffer()
    )

    @Test
    fun `模拟器AndroidKeyStore真实密钥落位实测为SOFTWARE`() {
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val authManager = BiometricAuthManager(keystoreManager, AllowAllIntegrityGate())
        try {
            // 生产同规格生成（落位属性与授权规格无关）：无认证门控密钥，
            // 规避「未录入生物识别无法生成 per-operation 认证密钥」的平台硬约束
            keystoreManager.getOrCreateKey(
                alias = PROBE_ALIAS,
                requireUserAuth = false,
                invalidateOnBiometricEnrollment = false
            )
            val level = authManager.getKeySecurityLevelForDatabase(PROBE_DB_ID)
            assertEquals(
                "模拟器（软件 Keystore）真实密钥落位实测应为 SOFTWARE（ISSUE-P1-22 设备侧基线；" +
                    "探测经 SecretKeyFactory——原 KeyFactory 误用恒 UNKNOWN 已同批整改）",
                KeystoreManager.KeySecurityLevel.SOFTWARE,
                level
            )
        } finally {
            keystoreManager.deleteKey(PROBE_ALIAS)
        }
    }

    @Test
    fun `未录入强生物识别时封印fail-closed且不请求降级确认`() = runBlocking<Unit> {
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val authManager = BiometricAuthManager(keystoreManager, AllowAllIntegrityGate())
        val settings = RealSettingsRepository(context)
        settings.setBiometricEnabled(true)
        settings.setQuickUnlockDowngradeAcknowledged(false)

        // 设备条件实测：模拟器无已录入 Class 3 强生物识别
        val status = authManager.canAuthenticate(context, BiometricManager.Authenticators.BIOMETRIC_STRONG)
        assertNotEquals(
            "本用例前提：环境未录入强生物识别（若已录入请改跑完整确认链路用例）",
            BiometricStatus.AVAILABLE,
            status
        )
        assertFalse(
            "canSeal 闸门必须拒绝（ISSUE-P1-08 既有语义的设备侧复核）",
            UnlockAuthPolicy.canSeal(status)
        )

        val uiState = MutableStateFlow(UnlockUiState())
        val coordinator = buildCoordinator(uiState, settings, authManager)

        // 生产默认供给链（不注入替身）：真实建钥抛 InvalidAlgorithmParameterException
        //（未录入生物识别无法生成 per-operation 认证密钥）→ 供给失败 → fail-closed 跳过封印
        coordinator.requestBiometricEnrollment(null, "deviceTestPass#1".toCharArray())

        assertFalse("供给失败路径不得请求降级确认", uiState.value.quickUnlockDowngradeConsentPending)
        assertFalse(
            "未录入强生物识别 + 未确认降级 → 不得建立封印（AC③ 设备侧实测）",
            BiometricCredentialStorage(context).hasEncryptedCredential(DB_ID)
        )
        assertFalse(
            "用户未做任何决定，确认记录不得置位",
            settings.getSettings().first().quickUnlockDowngradeAcknowledged
        )
        assertTrue(
            "生物识别开关保持原状（非用户拒绝路径不得改写用户设置）",
            settings.getSettings().first().biometricEnabled
        )
    }

    /**
     * AC② 设备侧一层：注入假 `SOFTWARE` 落位（真实平台无法在模拟器产出已录入生物识别的
     * per-operation 认证密钥，故此处以替身驱动闸门本身），在真实 Android 运行时验证
     * 「用户拒绝 → 关闭生物识别且不留确认记录」与「用户确认 → 确认记录持久化」两条分支。
     */
    @Test
    fun `SOFTWARE落位降级确认闸门在设备侧闭环`() = runBlocking<Unit> {
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val authManager = BiometricAuthManager(keystoreManager, AllowAllIntegrityGate())
        val settings = RealSettingsRepository(context)
        val storage = BiometricCredentialStorage(context)

        val uiState = MutableStateFlow(UnlockUiState())
        val coordinator = buildCoordinator(uiState, settings, authManager)
        // 注入假落位探测（JVM 同样路径的替身；此处验证其在 Android 运行时的闸门行为）
        coordinator.sealKeyProvisionOverride = { _: String ->
            SealedKeyProvision(
                cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"),
                securityLevel = KeystoreManager.KeySecurityLevel.SOFTWARE
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // ── 分支一：用户拒绝 ──────────────────────────────────────────
            storage.clearCredential(DB_ID)
            settings.setBiometricEnabled(true)
            settings.setQuickUnlockDowngradeAcknowledged(false)

            val declineJob = scope.launch {
                coordinator.requestBiometricEnrollment(null, "deviceTestPass#1".toCharArray())
            }
            withTimeout(TIMEOUT_MS) { uiState.first { it.quickUnlockDowngradeConsentPending } }
            coordinator.completeDowngradeConsent(false)
            declineJob.join()

            assertFalse("拒绝后生物识别开关应关闭", settings.getSettings().first().biometricEnabled)
            assertFalse(
                "拒绝不得留下确认记录",
                settings.getSettings().first().quickUnlockDowngradeAcknowledged
            )
            assertFalse(uiState.value.quickUnlockDowngradeConsentPending)
            assertFalse(
                "未经确认不得建立封印（AC③ 设备侧实测）",
                storage.hasEncryptedCredential(DB_ID)
            )

            // ── 分支二：用户确认 ──────────────────────────────────────────
            settings.setBiometricEnabled(true)
            settings.setQuickUnlockDowngradeAcknowledged(false)

            val confirmJob = scope.launch {
                coordinator.requestBiometricEnrollment(null, "deviceTestPass#1".toCharArray())
            }
            withTimeout(TIMEOUT_MS) { uiState.first { it.quickUnlockDowngradeConsentPending } }
            coordinator.completeDowngradeConsent(true)
            confirmJob.join()

            assertTrue(
                "确认后必须持久化降级确认记录（AC② 设备侧实测）",
                settings.getSettings().first().quickUnlockDowngradeAcknowledged
            )
            assertFalse(uiState.value.quickUnlockDowngradeConsentPending)
            assertTrue(
                "确认路径不得改写生物识别开关",
                settings.getSettings().first().biometricEnabled
            )

            // ── 分支三：已有确认记录 → 不再重复弹窗 ────────────────────────
            val secondJob = scope.launch {
                coordinator.requestBiometricEnrollment(null, "deviceTestPass#1".toCharArray())
            }
            secondJob.join()
            assertFalse(
                "已有确认记录不得再次请求确认",
                uiState.value.quickUnlockDowngradeConsentPending
            )
        } finally {
            scope.cancel()
            coordinator.sealKeyProvisionOverride = null
            storage.clearCredential(DB_ID)
            settings.setQuickUnlockDowngradeAcknowledged(false)
        }
    }

    private companion object {
        const val DB_ID = "db_p1_22_device_test"
        const val TIMEOUT_MS = 10_000L

        // 落位探测用：与封印密钥同一别名规则（KEY_ALIAS + "_" + dbId），dbId 专用于本用例
        const val PROBE_DB_ID = "db_p1_22_probe"
        const val PROBE_ALIAS = "${KeystoreManager.BIOMETRIC_KEY_ALIAS}_$PROBE_DB_ID"
    }
}
