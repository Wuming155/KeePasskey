package com.keepasskey.app.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.biometric.BiometricManager
import java.security.KeyStore
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
 * 1. **落位探测基线（环境分支）**：真实 AndroidKeyStore 密钥的实测落位必须与设备硬件能力一致——
 *    模拟器（无 TEE / StrongBox）为 `SOFTWARE`（本条目问题前提在 Android 运行时成立，非推算），
 *    真机（有 TEE）为 `TRUSTED_ENVIRONMENT` / `STRONGBOX`。**原实现硬编码 `SOFTWARE`**，
 *    等于把模拟器环境语义当成设备侧通用语义，在真机上必然失败（2026-09-14 arm64 真机实测：
 *    `expected:<SOFTWARE> but was:<TRUSTED_ENVIRONMENT>`），已改为环境分支断言；
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

    /** 组装封印协调器（各用例共用；封印密钥供给替身由调用方按需注入） */
    private fun buildCoordinator(
        uiState: MutableStateFlow<UnlockUiState>,
        settings: RealSettingsRepository,
        authManager: BiometricAuthManager
    ): BiometricEnrollmentCoordinator = BiometricEnrollmentCoordinator(
        uiState = uiState,
        settingsRepository = settings,
        activeDbId = { DB_ID },
        // ISSUE-P1-431（§431）封印载荷瘦身：协调器已不再接收 keyFileBytes（密钥文件字节不进
        // Keystore 载荷），原具名实参残留致 `:app:androidTest` 编译失败 → 第四层真机实跑被卡死。
        biometricAuthManager = authManager,
        biometricCredentialStorage = BiometricCredentialStorage(context),
        debugLog = DebugLogBuffer()
    )

    @Test
    fun `AndroidKeyStore真实密钥落位在设备侧返回真实等级`() {
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val authManager = BiometricAuthManager(keystoreManager)
        try {
            // 生产同规格生成（落位属性与授权规格无关）：无认证门控密钥，
            // 规避「未录入生物识别无法生成 per-operation 认证密钥」的平台硬约束
            keystoreManager.getOrCreateKey(
                alias = PROBE_ALIAS,
                requireUserAuth = false,
                invalidateOnBiometricEnrollment = false
            )
            val level = authManager.getKeySecurityLevelForDatabase(PROBE_DB_ID)
            val softwareKeystore = isSoftwareKeystoreEnvironment()
            println(
                "[Keystore 落位设备侧实测] level=$level, 软件Keystore环境=$softwareKeystore" +
                    ", MODEL=${Build.MODEL}, PRODUCT=${Build.PRODUCT}, HARDWARE=${Build.HARDWARE}" +
                    ", SDK=${Build.VERSION.SDK_INT}, ABI=${Build.SUPPORTED_ABIS.joinToString()}"
            )
            assertNotEquals(
                "落位探测不得为 UNKNOWN（UNKNOWN 即探测链路失效——原 KeyFactory 误用恒 UNKNOWN 的回归锁；" +
                    "探测经 SecretKeyFactory 已同批整改）",
                KeystoreManager.KeySecurityLevel.UNKNOWN,
                level
            )
            if (softwareKeystore) {
                assertEquals(
                    "软件 Keystore 环境（模拟器）真实密钥落位实测应为 SOFTWARE" +
                        "（ISSUE-P1-22 问题前提的设备侧证据）",
                    KeystoreManager.KeySecurityLevel.SOFTWARE,
                    level
                )
            } else {
                assertTrue(
                    "真机（非模拟器）真实密钥落位应为硬件级 TEE / StrongBox，实测 $level" +
                        "（若落位为 SOFTWARE，须先核实该机是否确无硬件密钥库，不得直接放宽断言）",
                    level == KeystoreManager.KeySecurityLevel.TRUSTED_ENVIRONMENT ||
                        level == KeystoreManager.KeySecurityLevel.STRONGBOX
                )
            }
        } finally {
            keystoreManager.deleteKey(PROBE_ALIAS)
        }
    }

    @Test
    fun `未录入强生物识别时封印fail-closed且不请求降级确认`() = runBlocking<Unit> {
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val authManager = BiometricAuthManager(keystoreManager)
        val settings = RealSettingsRepository(context)
        settings.setBiometricEnabled(true)
        settings.setQuickUnlockDowngradeAcknowledged(false)

        // 设备条件实测：模拟器无已录入 Class 3 强生物识别。
        // ISSUE-P2-192 余量第 8 项：环境前提从 assertNotEquals 硬断言改为 Assume——
        // 已录入强生物识别的设备上本用例（fail-closed 分支）前提不成立，跳过并记入 skipped 数，
        // 完整确认链路在真机（已录入）上由确认闸门用例覆盖，不再被误读为「生产有 bug」。
        val status = authManager.canAuthenticate(context, BiometricManager.Authenticators.BIOMETRIC_STRONG)
        org.junit.Assume.assumeTrue(
            "本用例前提：环境未录入强生物识别（实际 $status；已录入请改跑完整确认链路用例）",
            status != BiometricStatus.AVAILABLE
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
        val authManager = BiometricAuthManager(keystoreManager)
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

    /**
     * ENG-20261028-44 设备侧实测：
     * 1. 不传 KeystoreManager 时无法清理硬件别名，不得错误标记全清（fail-closed），
     *    且必须保留遗留断言 prefs 键作为重试索引，绝不提前丢 ID；
     * 2. 多库场景下真实硬件别名删除失败注入：仅成功者清理 prefs 键，失败库的硬件别名与
     *    prefs 键均完整保留，不标全清；
     * 3. 故障恢复后重试：沿保留的重试索引成功完成剩余硬件别名删除，两步皆确认成功后落完成标记；
     * 4. 完成标记置位后，第二实例或重启不再重复 Keystore 扫描与删除操作。
     *
     * 行为证据：断言使用真实的 `com.keepasskey.biometric_credentials` prefs 与平台 `AndroidKeyStore`
     * 别名存在性（containsAlias），不使用生产全局计数器或源码结构锁定；使用虚构库 ID 与测试别名并在 finally 清理。
     */
    @Test
    fun `生物迁移生命周期在设备侧闭环`() {
        val prefs = context.getSharedPreferences("com.keepasskey.biometric_credentials", Context.MODE_PRIVATE)
        val originalDone = if (prefs.contains("legacy_migration_done")) prefs.getBoolean("legacy_migration_done", false) else null
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val keyStore = KeyStore.getInstance(KeystoreManager.ANDROID_KEY_STORE).apply { load(null) }

        val testPasskeyAlias = KeystoreManager.legacyUnlockPasskeyAliasFor(MIGRATION_TEST_DB_ID)
        val testFailDbId = "${MIGRATION_TEST_DB_ID}_fail"
        val testFailPasskeyAlias = KeystoreManager.legacyUnlockPasskeyAliasFor(testFailDbId)
        val testQuickUnlockAlias = KeystoreManager.LEGACY_QUICK_UNLOCK_KEY_ALIAS

        val pubKey = "${MIGRATION_TEST_DB_ID}_passkey_pub"
        val macKey = "${MIGRATION_TEST_DB_ID}_passkey_mac"
        val failPubKey = "${testFailDbId}_passkey_pub"

        try {
            // ── 段 1：不传 KeystoreManager 时不能错误标全清，且不得提前删除 prefs 重试索引 ───────
            prefs.edit()
                .putString(pubKey, "legacy_pub_data")
                .putString(macKey, "legacy_mac_data")
                .remove("legacy_migration_done")
                .apply()

            // 构造未提供 KeystoreManager 的实例（JVM 桩调用或缺省注入场景）
            BiometricCredentialStorage(context, keystoreManager = null)
            assertFalse(
                "未传入 KeystoreManager 时无法操作硬件密钥库，不得错误标记迁移完成（ENG-20261028-44）",
                prefs.getBoolean("legacy_migration_done", false)
            )
            assertTrue(
                "未传入 KeystoreManager 时必须完整保留遗留断言 prefs 键，不得提前丢失重试索引",
                prefs.contains(pubKey) && prefs.contains(macKey)
            )

            // ── 段 2：真实硬件删除失败时保留失败库的重试索引，故障恢复后重试全清 ───────
            // 播种两个库的硬件密钥与遗留 prefs 键
            keystoreManager.getOrCreateKey(testPasskeyAlias, false, false)
            keystoreManager.getOrCreateKey(testFailPasskeyAlias, false, false)
            keystoreManager.getOrCreateKey(testQuickUnlockAlias, false, false)

            prefs.edit()
                .putString(pubKey, "legacy_pub_data")
                .putString(macKey, "legacy_mac_data")
                .putString(failPubKey, "legacy_pub_fail")
                .remove("legacy_migration_done")
                .apply()

            assertTrue(keyStore.containsAlias(testPasskeyAlias))
            assertTrue(keyStore.containsAlias(testFailPasskeyAlias))
            assertTrue(keyStore.containsAlias(testQuickUnlockAlias))

            // 注入故障：仅对 testFailPasskeyAlias 抛出异常，其余正常调用 keystoreManager.deleteKey
            var failInjected = true
            val failingStorage = BiometricCredentialStorage(
                context = context,
                keystoreManager = keystoreManager,
                keyDeleter = { alias ->
                    if (failInjected && alias == testFailPasskeyAlias) {
                        throw RuntimeException("Injected hardware Keystore failure for $alias")
                    }
                    keystoreManager.deleteKey(alias)
                }
            )

            // 验证故障下的安全状态：
            // 1. 成功库的硬件别名已删，失败库的硬件别名依然在 AndroidKeyStore 中
            assertFalse("成功库别名必须已从 AndroidKeyStore 删除", keyStore.containsAlias(testPasskeyAlias))
            assertTrue("失败库别名必须依然保留在 AndroidKeyStore 中", keyStore.containsAlias(testFailPasskeyAlias))
            // 2. 成功库的 prefs 键已清理，失败库的 prefs 键必须保留作为重试索引（未丢 ID！）
            assertFalse("成功库 prefs 键必须已清除", prefs.contains(pubKey))
            assertTrue("失败库 prefs 键必须保留作为重试索引（绝对未丢失 ID）", prefs.contains(failPubKey))
            // 3. 不得标记完成
            assertFalse("硬件删除未全部成功前不得写入完成标记", prefs.getBoolean("legacy_migration_done", false))

            // 恢复无故障状态（模拟下次启动重试）
            failInjected = false
            val retryStorage = BiometricCredentialStorage(
                context = context,
                keystoreManager = keystoreManager,
                keyDeleter = { alias -> keystoreManager.deleteKey(alias) }
            )

            // 验证重试后全清：
            assertFalse("重试后失败库别名必须已从 AndroidKeyStore 删除", keyStore.containsAlias(testFailPasskeyAlias))
            assertFalse("重试后失败库 prefs 键必须被清除", prefs.contains(failPubKey))
            assertFalse("QuickUnlock 别名也已从 AndroidKeyStore 删除", keyStore.containsAlias(testQuickUnlockAlias))
            assertTrue("所有步骤确认成功后必须写入完成标记", prefs.getBoolean("legacy_migration_done", false))

            // ── 段 3：第二实例 / 重启不重复 Keystore 扫描 ────────────────────────
            // 重新在 Keystore 播种测试别名：若第二实例重复扫描，该别名会被再次删除
            keystoreManager.getOrCreateKey(
                alias = testQuickUnlockAlias,
                requireUserAuth = false,
                invalidateOnBiometricEnrollment = false
            )
            assertTrue("播种别名以检验跳过逻辑", keyStore.containsAlias(testQuickUnlockAlias))

            val secondStorage = BiometricCredentialStorage(context, keystoreManager)

            assertTrue(
                "完成标记已为 true 时，第二实例构造不得重复扫描或删除 Keystore 别名",
                keyStore.containsAlias(testQuickUnlockAlias)
            )
        } finally {
            runCatching { keystoreManager.deleteKey(testPasskeyAlias) }
            runCatching { keystoreManager.deleteKey(testFailPasskeyAlias) }
            runCatching { keystoreManager.deleteKey(testQuickUnlockAlias) }
            runCatching {
                prefs.edit()
                    .remove(pubKey)
                    .remove(macKey)
                    .remove(failPubKey)
                    .apply()
            }
            if (originalDone != null) {
                prefs.edit().putBoolean("legacy_migration_done", originalDone).apply()
            } else {
                prefs.edit().remove("legacy_migration_done").apply()
            }
        }
    }

    /**
     * ENG-20261028-44 主线程构造成本量化：
     * 测量三种典型场景下主线程单次构造实际耗时（仅度量 constructor 瞬时耗时，不计测试脚手架）：
     * 1. 无遗留初次：无遗留数据且未标记完成时的首次构造；
     * 2. 含真实测试别名首次：含遗留硬件别名与 prefs 键的首次真实迁移构造；
     * 3. 已标记后再次实例：完成标记已置位后的快速路径构造。
     * 多次采样输出 min/max/median/mean 并写入证据文件。
     */
    @Test
    fun `主线程构造实际耗时量化`() {
        val prefs = context.getSharedPreferences("com.keepasskey.biometric_credentials", Context.MODE_PRIVATE)
        val originalDone = if (prefs.contains("legacy_migration_done")) prefs.getBoolean("legacy_migration_done", false) else null
        val keystoreManager = KeystoreManager(context, DebugLogBuffer())
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val perfPasskeyAlias = KeystoreManager.legacyUnlockPasskeyAliasFor(MIGRATION_PERF_DB_ID)
        val perfPubKey = "${MIGRATION_PERF_DB_ID}_passkey_pub"
        val sampleCount = 10

        val samplesCleanFirst = ArrayList<Double>(sampleCount)
        val samplesMigratingFirst = ArrayList<Double>(sampleCount)
        val samplesAlreadyMarked = ArrayList<Double>(sampleCount)

        try {
            // ── 场景 1：无遗留初次构造 ───────────────────────────────────────────
            repeat(sampleCount) {
                prefs.edit().remove("legacy_migration_done").apply()
                var durationMs = 0.0
                instrumentation.runOnMainSync {
                    val t0 = System.nanoTime()
                    val storage = BiometricCredentialStorage(context, keystoreManager)
                    val t1 = System.nanoTime()
                    durationMs = (t1 - t0) / 1_000_000.0
                }
                samplesCleanFirst.add(durationMs)
            }

            // ── 场景 2：含真实测试别名首次迁移构造 ───────────────────────────────
            repeat(sampleCount) {
                prefs.edit()
                    .putString(perfPubKey, "perf_legacy_pub")
                    .remove("legacy_migration_done")
                    .apply()
                keystoreManager.getOrCreateKey(perfPasskeyAlias, false, false)

                var durationMs = 0.0
                instrumentation.runOnMainSync {
                    val t0 = System.nanoTime()
                    val storage = BiometricCredentialStorage(context, keystoreManager)
                    val t1 = System.nanoTime()
                    durationMs = (t1 - t0) / 1_000_000.0
                }
                samplesMigratingFirst.add(durationMs)
            }

            // ── 场景 3：已标记后再次构造（单例再次解析/重启快速路径） ───────────
            prefs.edit().putBoolean("legacy_migration_done", true).apply()
            repeat(sampleCount) {
                var durationMs = 0.0
                instrumentation.runOnMainSync {
                    val t0 = System.nanoTime()
                    val storage = BiometricCredentialStorage(context, keystoreManager)
                    val t1 = System.nanoTime()
                    durationMs = (t1 - t0) / 1_000_000.0
                }
                samplesAlreadyMarked.add(durationMs)
            }

            fun stats(list: List<Double>): String {
                val sorted = list.sorted()
                val min = sorted.first()
                val max = sorted.last()
                val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                val mean = sorted.average()
                return "min=${String.format(java.util.Locale.US, "%.3f", min)}ms, " +
                    "max=${String.format(java.util.Locale.US, "%.3f", max)}ms, " +
                    "median=${String.format(java.util.Locale.US, "%.3f", median)}ms, " +
                    "mean=${String.format(java.util.Locale.US, "%.3f", mean)}ms"
            }

            val report = buildString {
                appendLine("=== ENG-44 生物凭据存储主线程构造成本测量 ===")
                appendLine("设备型号: ${Build.MODEL} (${Build.PRODUCT}), API: ${Build.VERSION.SDK_INT}")
                appendLine("采样次数: 每场景 $sampleCount 次")
                appendLine("1. 无遗留初次构造: ${stats(samplesCleanFirst)}")
                appendLine("2. 含真实别名首次迁移: ${stats(samplesMigratingFirst)}")
                appendLine("3. 已标记后再次实例（快速路径）: ${stats(samplesAlreadyMarked)}")
            }
            println(report)

            // 写出到应用文件目录以便提取证据
            runCatching {
                val evidenceDir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = java.io.File(evidenceDir, "eng44_migration_cost.txt")
                file.writeText(report)
            }
        } finally {
            runCatching { keystoreManager.deleteKey(perfPasskeyAlias) }
            runCatching { prefs.edit().remove(perfPubKey).apply() }
            if (originalDone != null) {
                prefs.edit().putBoolean("legacy_migration_done", originalDone).apply()
            } else {
                prefs.edit().remove("legacy_migration_done").apply()
            }
        }
    }

    /**
     * ENG-20261028-37 设备侧实测：
     * 1. 证实生产 elapsedRealtime 进程内倒计时不受墙钟前拨/回拨影响；
     * 2. 真实系统调时若平台/AVD 不支持（SET_TIME 需要签名级权限），生产时钟注入模拟与平台 clock 实测分开如实标注；
     * 3. 跨进程墙钟恢复截断不产生异常长等待（回拨截断至策略上限，前移正常过期放行）。
     *
     * 行为证据：使用生产 `SharedPrefsUnlockThrottleStore` 验证真实 SharedPreferences 持久化往返，
     * 使用虚构独立的库 ID 并在 finally 中 reset 清理，不读取真实用户数据。
     */
    @Test
    fun `解锁节流单调推进与跨进程恢复截断在设备侧闭环`() {
        // ── 平台 clock 实测与系统调时能力探测 ─────────────────────────────────
        val realElapsedBefore = SystemClock.elapsedRealtime()
        assertTrue("Android 平台 elapsedRealtime 必须有效且大于 0", realElapsedBefore > 0L)

        val originalWallTime = System.currentTimeMillis()
        var systemTimeAdjustSupported = false
        try {
            // 探测真实系统调时能力：普通应用/测试无 SET_TIME 权限通常会抛 SecurityException
            val canSet = SystemClock.setCurrentTimeMillis(originalWallTime)
            if (canSet) {
                systemTimeAdjustSupported = true
            }
        } catch (_: SecurityException) {
            systemTimeAdjustSupported = false
        }
        println(
            "[ENG-20261028-37 平台 clock 实测] elapsedRealtime=$realElapsedBefore, " +
                "真实系统调时支持=$systemTimeAdjustSupported (无 SET_TIME 签名权限属预期平台安全边界)"
        )

        val store = SharedPrefsUnlockThrottleStore(context)
        try {
            // 确保测试前初始状态干净
            store.reset(THROTTLE_TEST_DB_ID)

            // ── 段 1：生产 elapsedRealtime 进程内不受墙钟前拨 / 回拨影响 ──────────
            var wallShiftMs = 0L
            val testClock = object : ThrottleClock() {
                override fun wallMillis(): Long = System.currentTimeMillis() + wallShiftMs
                override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
            }
            val manager = UnlockThrottleManager(store, clock = testClock)

            // 连续失败达到阈值（5 次），触发 30 秒锁定
            val elapsedBeforeLock = SystemClock.elapsedRealtime()
            repeat(UnlockThrottlePolicy.FAILURE_THRESHOLD) {
                manager.registerFailure(THROTTLE_TEST_DB_ID)
            }
            val initialGate = manager.gate(THROTTLE_TEST_DB_ID)
            val elapsedAfterGate = SystemClock.elapsedRealtime()

            assertTrue("达到阈值必须触发锁定", initialGate is ThrottleGate.Locked)
            val initialRemaining = (initialGate as ThrottleGate.Locked).remainingMs
            val maxElapsedLock = (elapsedAfterGate - elapsedBeforeLock).coerceAtLeast(1L)
            assertTrue(
                "首次退避剩余时间必须处于 [基准 - 调用耗时, 基准] 紧确边界内，实测 $initialRemaining ms (耗时 ${maxElapsedLock}ms)",
                initialRemaining <= UnlockThrottlePolicy.BASE_BACKOFF_MS &&
                    initialRemaining >= UnlockThrottlePolicy.BASE_BACKOFF_MS - maxElapsedLock
            )

            // 模拟墙钟前拨 2 小时：进程内倒计时必须不受影响，不得提前解锁
            wallShiftMs = 2L * 60L * 60L * 1000L
            val elapsedBeforeForward = SystemClock.elapsedRealtime()
            val forwardGate = manager.gate(THROTTLE_TEST_DB_ID)
            val elapsedAfterForward = SystemClock.elapsedRealtime()

            assertTrue("锁定期内墙钟前移 2 小时不得提前解锁（由 elapsedRealtime 隔离）", forwardGate is ThrottleGate.Locked)
            val forwardRemaining = (forwardGate as ThrottleGate.Locked).remainingMs
            val maxElapsedForward = (elapsedAfterForward - elapsedBeforeLock).coerceAtLeast(1L)
            assertTrue(
                "前拨后剩余等待仅受 elapsedRealtime 推进，实测 $forwardRemaining ms (初始 $initialRemaining ms)",
                forwardRemaining <= initialRemaining &&
                    forwardRemaining >= UnlockThrottlePolicy.BASE_BACKOFF_MS - maxElapsedForward
            )

            // 模拟墙钟回拨 2 小时：进程内倒计时必须不受影响，不得延长等待
            wallShiftMs = -2L * 60L * 60L * 1000L
            val elapsedBeforeBackward = SystemClock.elapsedRealtime()
            val backwardGate = manager.gate(THROTTLE_TEST_DB_ID)
            val elapsedAfterBackward = SystemClock.elapsedRealtime()

            assertTrue("锁定期内墙钟回拨 2 小时不得延长锁定", backwardGate is ThrottleGate.Locked)
            val backwardRemaining = (backwardGate as ThrottleGate.Locked).remainingMs
            val maxElapsedBackward = (elapsedAfterBackward - elapsedBeforeLock).coerceAtLeast(1L)
            assertTrue(
                "回拨后剩余等待不受回拨虚增，实测 $backwardRemaining ms (前拨后 $forwardRemaining ms)",
                backwardRemaining <= forwardRemaining &&
                    backwardRemaining >= UnlockThrottlePolicy.BASE_BACKOFF_MS - maxElapsedBackward
            )

            // ── 段 2：新实例读取持久记录时的墙钟恢复截断（冷启动读取未产生异常长等待） ────
            // 模拟冷启动新实例：进程死亡期间墙钟被回拨 7 天
            // 磁盘记录中的 lockoutUntilEpochMs 距当前回拨后的墙钟差距达到 7 天 + 30 秒
            val rewindClock = object : ThrottleClock() {
                override fun wallMillis(): Long = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
                override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
            }
            val restartedManager = UnlockThrottleManager(store, clock = rewindClock)
            val recoveredGate = restartedManager.gate(THROTTLE_TEST_DB_ID)
            assertTrue("新实例读取持久记录时必须继续维持节流", recoveredGate is ThrottleGate.Locked)
            val recoveredRemaining = (recoveredGate as ThrottleGate.Locked).remainingMs
            val policyCeiling = UnlockThrottlePolicy.backoffMillisFor(UnlockThrottlePolicy.FAILURE_THRESHOLD)
            assertTrue(
                "跨进程墙钟回拨导致的虚增截止必须截断到策略上限（上限 $policyCeiling ms，实测 $recoveredRemaining ms），杜绝异常长等待",
                recoveredRemaining <= policyCeiling && recoveredRemaining > 0L
            )

            // 模拟冷启动新实例：正常流逝并越过截止时间
            val expiredClock = object : ThrottleClock() {
                override fun wallMillis(): Long = System.currentTimeMillis() + 60_000L
                override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
            }
            val expiredManager = UnlockThrottleManager(store, clock = expiredClock)
            val expiredGate = expiredManager.gate(THROTTLE_TEST_DB_ID)
            assertTrue("持久截止到期后新实例放行", expiredGate is ThrottleGate.Allowed)

            // ── 段 3：真实平台调时权限探测与恢复 ──────────────────────────────────
            if (systemTimeAdjustSupported) {
                try {
                    SystemClock.setCurrentTimeMillis(originalWallTime + 5000L)
                    val afterShiftElapsed = SystemClock.elapsedRealtime()
                    assertTrue("平台调时后 elapsedRealtime 保持单调推进", afterShiftElapsed >= realElapsedBefore)
                } finally {
                    SystemClock.setCurrentTimeMillis(originalWallTime)
                }
            }
        } finally {
            store.reset(THROTTLE_TEST_DB_ID)
        }
    }

    private companion object {
        const val DB_ID = "db_p1_22_device_test"
        const val TIMEOUT_MS = 10_000L

        // 落位探测用：与封印密钥同一别名规则（KEY_ALIAS + "_" + dbId），dbId 专用于本用例
        const val PROBE_DB_ID = "db_p1_22_probe"
        const val PROBE_ALIAS = "${KeystoreManager.BIOMETRIC_KEY_ALIAS}_$PROBE_DB_ID"

        // ENG-20261028-44 设备侧实测虚构测试库 ID 与基准库 ID
        const val MIGRATION_TEST_DB_ID = "db_eng44_device_test"
        const val MIGRATION_PERF_DB_ID = "db_eng44_perf_test"

        // ENG-20261028-37 设备侧实测虚构节流库 ID
        const val THROTTLE_TEST_DB_ID = "db_eng37_device_throttle"

        /**
         * 判定当前是否为「软件 Keystore 环境」（模拟器 / 无硬件密钥库）：决定落位断言分支。
         *
         * 平台**未**提供「本机是否具备 TEE」的公开查询（仅有 `FEATURE_STRONGBOX_KEYSTORE`），
         * 故以模拟器特征判定：`PRODUCT` / `HARDWARE` 为 AOSP 模拟器取值（`sdk*` / `ranchu` / `goldfish`），
         * `FINGERPRINT` 含 `generic`（旧版 AOSP 镜像）。真机三者均不命中。
         */
        fun isSoftwareKeystoreEnvironment(): Boolean =
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.FINGERPRINT.contains("generic", ignoreCase = true)
    }
}
