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
}
