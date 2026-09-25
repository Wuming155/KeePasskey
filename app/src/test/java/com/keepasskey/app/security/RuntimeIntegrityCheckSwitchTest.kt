package com.keepasskey.app.security

import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-236 / PD-15：运行环境完整性检测**总开关**的行为与接线回归。
 *
 * ## 为什么需要这一组用例
 *
 * 本开关是**用户主动放松安全控制**的唯一入口，它的两种失效形态都不体现在「算法算错」上：
 *
 * 1. **放松过头**——把开关从「只解除通道降级」误做成「连等级判定也一起关掉」，
 *    于是设置页会在真有 Root / 注入框架时显示「无风险」（**谎报安全**，比不检测更坏）；
 * 2. **根本没接上**——`UserSettings` 加了字段但没有人消费，或只在一个门控入口短路
 *    （`currentEnforcement` 放了、`awaitEnforcement` 仍拦自动填充），用户看到的仍是
 *    「开了开关指纹照样不可用」。
 *
 * 故本文件分两层：**裁决内核走行为断言**（纯函数，确定性），**接线走源码比对**
 * （体例沿用原 `AccessibilityNoticeWiringTest` / `AlgoHotPathGuardsTest`——
 * 这类失效只有静态比对能稳定捕获，行为用例在宿主 JVM 上无法构造 Root 环境）。
 */
class RuntimeIntegrityCheckSwitchTest {

    // ===================== 一、裁决内核：关闭只解除降级，不改变判定 =====================

    @Test
    fun `关闭检测时 root 命中不再阻断任何通道`() {
        val report = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(rootArtifactsDetected = true),
            enforcementEnabled = false
        )

        assertEquals(IntegrityEnforcement.ALLOWED, report.enforcement)
        assertFalse(report.enforcement.disableBiometricQuickUnlock)
        assertFalse(report.enforcement.disableAutofill)
        assertFalse(report.enforcement.requireRiskNotice)
    }

    @Test
    fun `关闭检测时其余动态攻击特征同样不再阻断`() {
        val signals = listOf(
            IntegritySignals(debuggerAttached = true),
            IntegritySignals(beingTraced = true),
            IntegritySignals(magiskDetected = true),
            IntegritySignals(hookFrameworkDetected = true),
            IntegritySignals(appDebuggable = true)
        )

        signals.forEach { signal ->
            val report = RuntimeIntegrityPolicy.evaluate(signal, enforcementEnabled = false)
            assertEquals(
                "任一命中信号都不得在关闭态降级通道：$signal",
                IntegrityEnforcement.ALLOWED,
                report.enforcement
            )
        }
    }

    @Test
    fun `关闭检测仍如实报出等级与命中清单而不谎报无风险`() {
        val report = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(rootArtifactsDetected = true, magiskDetected = true),
            enforcementEnabled = false
        )

        assertEquals(
            "关闭的是「降级」不是「判定」——快照不得因用户关掉阻断就报 TRUSTED",
            RuntimeRiskLevel.COMPROMISED,
            report.level
        )
        assertTrue(report.signals.rootArtifactsDetected)
        assertTrue(report.signals.magiskDetected)
    }

    @Test
    fun `放行态不得携带阻断归因`() {
        val report = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(rootArtifactsDetected = true),
            enforcementEnabled = false
        )

        assertTrue(
            "biometricBlockReasons 是「为什么被禁」的消费侧数据源；通道既已放行，" +
                "就不得再向该数据源提供一份本就未生效的归因",
            report.enforcement.biometricBlockReasons.isEmpty()
        )
    }

    // ISSUE-P3-324：原「关闭检测不影响无障碍提示的正交语义」随无障碍信号链路整体移除而删除
    // （thirdPartyAccessibilityEnabled / requireAccessibilityNotice 已不存在，无对象可测）。

    @Test
    fun `开启检测时既有 fail-closed 后果逐项不变`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(rootArtifactsDetected = true))

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
        assertTrue(report.enforcement.disableBiometricQuickUnlock)
        assertTrue(report.enforcement.disableAutofill)
        assertTrue(report.enforcement.requireRiskNotice)
        assertEquals(listOf(IntegrityBlockReason.ROOT_ARTIFACTS), report.enforcement.biometricBlockReasons)
    }

    @Test
    fun `关闭检测时实时信号升级不再降级`() {
        val base = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE, enforcementEnabled = false)

        val escalated = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = base,
            debuggerAttached = true,
            hookFrameworkDetected = true,
            beingTraced = true,
            enforcementEnabled = false
        )

        assertEquals(RuntimeRiskLevel.COMPROMISED, escalated.level)
        assertEquals(IntegrityEnforcement.ALLOWED, escalated.enforcement)
    }

    // ===================== 二、门控入口：关闭即放行，不等待扫描 =====================

    @Test
    fun `门控在检测关闭（出厂默认）时立即放行不等待首次扫描`() {
        // 未注入设置仓库 = 纯 JVM 单测路径 ⇒ 保持出厂默认「关闭」；
        // 此刻快照仍是 UNDETERMINED（从未扫描），旧口径会 fail-closed 阻断，
        // 本用例正是钉死「关闭后连保守分支也不再适用」。
        val detector = RuntimeIntegrityDetector(
            context = null,
            tracedProcessProbe = FixedTracedProcessProbe(null),
            settingsRepository = null
        )

        assertEquals(IntegrityEnforcement.ALLOWED, detector.currentEnforcement())
        assertEquals(IntegrityEnforcement.ALLOWED, runBlocking { detector.awaitEnforcement() })
    }

    // ===================== 三、接线守卫：两条门控入口都必须短路 =====================

    @Test
    fun `探测器订阅总开关且两条门控入口都短路放行`() {
        val source = detectorSource

        assertTrue(
            "必须订阅 UserSettings.integrityCheckEnabled（加了字段没人读 = 开关无效）",
            source.contains("it.integrityCheckEnabled")
        )
        assertEquals(
            "currentEnforcement（指纹）与 awaitEnforcement（自动填充 / CM）两个入口都必须短路；" +
                "少一处即出现「开了开关指纹能用、自动填充仍被拦」的半接状态",
            2,
            Regex("if \\(!enforcementEnabled\\) return IntegrityEnforcement\\.ALLOWED")
                .findAll(source).count()
        )
        assertTrue(
            "快照必须按当前开关口径裁决（不得用关闭态的策略代表开启态）",
            source.contains("RuntimeIntegrityPolicy.evaluate(detectSignals(), enforcementEnabled)")
        )
    }

    @Test
    fun `出厂默认关闭且持久化读回缺省亦为关闭`() {
        assertFalse(UserSettings().integrityCheckEnabled)
        assertFalse(SettingsUiState().integrityCheckEnabled)
        assertTrue(
            "持久化层缺省必须与 UserSettings 一致（未写入过即关闭）",
            settingsRepositorySource.contains("prefs[KEY_INTEGRITY_CHECK_ENABLED] ?: false")
        )
    }

    @Test
    fun `设置页暴露开关并常驻声明关闭代价`() {
        val screen = securityScreenSource

        assertTrue(screen.contains("onIntegrityCheckToggle"))
        assertTrue(screen.contains("R.string.sec_integrity_check_title"))
        assertTrue(
            "关闭态必须常驻显示代价说明（默认即关闭，不得让用户自行推断失去了什么）",
            screen.contains("R.string.sec_integrity_check_off_notice")
        )
        assertTrue(screen.contains("!uiState.integrityCheckEnabled"))
    }

    @Test
    fun `开关经 ViewModel 与导航图接成可直写偏好`() {
        assertTrue(viewModelSource.contains("fun setIntegrityCheckEnabled(enabled: Boolean)"))
        assertTrue(
            viewModelSource.contains("preferences.setIntegrityCheckEnabled(enabled)")
        )
        assertTrue(
            "导航图必须把回调接到 ViewModel（否则开关点了没有任何落盘）",
            navGraphSource.contains(
                "onIntegrityCheckToggle = settingsViewModel::setIntegrityCheckEnabled"
            )
        )
    }

    @Test
    fun `中英三键齐备`() {
        val keys = listOf(
            "sec_integrity_check_title",
            "sec_integrity_check_sub",
            "sec_integrity_check_off_notice"
        )
        keys.forEach { key ->
            assertTrue("中文文案缺失：$key", zhStringsSource.contains("name=\"$key\""))
            assertTrue("英文文案缺失：$key", enStringsSource.contains("name=\"$key\""))
        }
    }

    // ===================== 辅助 =====================

    /** 固定返回值的 ptrace 探测替身（本文件只关心门控短路，不关心 TracerPid 语义） */
    private class FixedTracedProcessProbe(private val pid: Int?) : TracedProcessProbe {
        override fun tracerPid(): Int? = pid
    }

    private val detectorSource
        get() = readSource("app/src/main/java/com/keepasskey/app/security/RuntimeIntegrityDetector.kt")

    private val settingsRepositorySource
        get() = readSource("app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt")

    private val securityScreenSource
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsScreen.kt"
        )

    private val viewModelSource
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt")

    private val navGraphSource
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraph.kt") +
            readSource("app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraphRoutes.kt")

    private val zhStringsSource
        get() = readSource("app/src/main/res/values/strings.xml")

    private val enStringsSource
        get() = readSource("app/src/main/res/values-en/strings.xml")

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
