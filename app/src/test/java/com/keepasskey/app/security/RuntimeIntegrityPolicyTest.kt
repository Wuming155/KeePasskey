package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuntimeIntegrityPolicy 风险映射矩阵单元测试（ISSUE-P2-08 / ZT-13）。
 *
 * 覆盖「信号 → 风险等级 → 敏感通道降级策略」的完整分级，纯 JVM 可测（零 Android 依赖）。
 */
class RuntimeIntegrityPolicyTest {

    @Test
    fun `全无风险信号时放行全部通道`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE)

        assertEquals(RuntimeRiskLevel.TRUSTED, report.level)
        assertEquals(IntegrityEnforcement.ALLOWED, report.enforcement)
        assertFalse(report.enforcement.disableBiometricQuickUnlock)
        assertFalse(report.enforcement.disableAutofill)
        assertFalse(report.enforcement.requireRiskNotice)
    }

    @Test
    fun `调试器附加属动态攻击特征并降级为最高风险`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(debuggerAttached = true))

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
        assertTrue(report.enforcement.disableBiometricQuickUnlock)
        assertTrue(report.enforcement.disableAutofill)
        assertTrue(report.enforcement.requireRiskNotice)
    }

    @Test
    fun `root 痕迹属动态攻击特征`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(rootArtifactsDetected = true))

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
        assertTrue(report.enforcement.disableAutofill)
    }

    @Test
    fun `Magisk 痕迹属动态攻击特征`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(magiskDetected = true))

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
        assertTrue(report.enforcement.disableAutofill)
    }

    @Test
    fun `钩子框架注入属动态攻击特征`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(hookFrameworkDetected = true))

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
        assertTrue(report.enforcement.disableBiometricQuickUnlock)
        assertTrue(report.enforcement.disableAutofill)
    }

    @Test
    fun `应用可调试仅升级为可疑并禁用生物快速解锁`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(appDebuggable = true))

        assertEquals(RuntimeRiskLevel.ELEVATED, report.level)
        assertTrue(report.enforcement.disableBiometricQuickUnlock)
        // 可疑级保留自动填充可用，避免误伤普通用户
        assertFalse(report.enforcement.disableAutofill)
        assertTrue(report.enforcement.requireRiskNotice)
    }

    @Test
    fun `非受信任安装来源仅升级为可疑`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(untrustedInstallSource = true))

        assertEquals(RuntimeRiskLevel.ELEVATED, report.level)
        assertTrue(report.enforcement.disableBiometricQuickUnlock)
        assertFalse(report.enforcement.disableAutofill)
    }

    @Test
    fun `动态特征优先于静态可疑特征`() {
        val report = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(
                debuggerAttached = true,
                appDebuggable = true,
                untrustedInstallSource = true
            )
        )

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
        assertTrue(report.enforcement.disableAutofill)
    }

    @Test
    fun `扫描未完成时按保守策略短暂禁用敏感通道`() {
        val enforcement = IntegrityEnforcement.UNDETERMINED

        assertTrue(enforcement.disableBiometricQuickUnlock)
        assertTrue(enforcement.disableAutofill)
        // 未判定不等同于已判定为风险，不触发风险提示，避免 UI 闪烁
        assertFalse(enforcement.requireRiskNotice)
    }

    // ===== ISSUE-P3-53：一次性缓存的时变信号可由实时重扫升级 =====

    @Test
    fun `冷启动后附加调试器可被后续实时判定升级为最高风险`() {
        // 缓存快照为干净（首次扫描发生在附加调试器之前）
        val cached = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE)
        assertEquals(RuntimeRiskLevel.TRUSTED, cached.level)

        val escalated = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = cached,
            debuggerAttached = true,
            hookFrameworkDetected = false
        )

        assertEquals(RuntimeRiskLevel.COMPROMISED, escalated.level)
        assertTrue(escalated.enforcement.disableBiometricQuickUnlock)
        assertTrue(escalated.enforcement.disableAutofill)
    }

    @Test
    fun `实时钩子框架信号同样升级为最高风险`() {
        val cached = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE)

        val escalated = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = cached,
            debuggerAttached = false,
            hookFrameworkDetected = true
        )

        assertEquals(RuntimeRiskLevel.COMPROMISED, escalated.level)
    }

    @Test
    fun `无实时信号时维持缓存快照不变`() {
        val cached = RuntimeIntegrityPolicy.evaluate(IntegritySignals(appDebuggable = true))

        val unchanged = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = cached,
            debuggerAttached = false,
            hookFrameworkDetected = false
        )

        assertEquals(cached, unchanged)
    }

    @Test
    fun `未判定快照在无实时信号时保持未判定`() {
        val unchanged = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = RuntimeIntegrityReport.UNDETERMINED,
            debuggerAttached = false,
            hookFrameworkDetected = false
        )

        assertEquals(RuntimeRiskLevel.UNDETERMINED, unchanged.level)
        assertTrue(unchanged.enforcement.disableBiometricQuickUnlock)
        assertTrue(unchanged.enforcement.disableAutofill)
    }
}
