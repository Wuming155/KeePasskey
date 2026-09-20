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
    fun `动态特征优先于静态可疑特征`() {
        val report = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(
                debuggerAttached = true,
                appDebuggable = true
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
        ,
            beingTraced = false
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
        ,
            beingTraced = false
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
        ,
            beingTraced = false
        )

        assertEquals(cached, unchanged)
    }

    @Test
    fun `未判定快照在无实时信号时保持未判定`() {
        val unchanged = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = RuntimeIntegrityReport.UNDETERMINED,
            debuggerAttached = false,
            hookFrameworkDetected = false
        ,
            beingTraced = false
        )

        assertEquals(RuntimeRiskLevel.UNDETERMINED, unchanged.level)
        assertTrue(unchanged.enforcement.disableBiometricQuickUnlock)
        assertTrue(unchanged.enforcement.disableAutofill)
    }

    // ===== ISSUE-P2-63：非 suspend 门控的快照新鲜度（陈旧即 fail-closed） =====

    @Test
    fun `从未扫描的快照视为陈旧`() {
        assertTrue(
            RuntimeIntegrityPolicy.isSnapshotStale(
                snapshotAtMillis = 0L, nowMillis = 1_000L, freshnessWindowMillis = 500L
            )
        )
    }

    @Test
    fun `超出新鲜度窗口的快照视为陈旧`() {
        assertTrue(
            RuntimeIntegrityPolicy.isSnapshotStale(
                snapshotAtMillis = 1_000L, nowMillis = 1_501L, freshnessWindowMillis = 500L
            )
        )
    }

    @Test
    fun `窗口内（含边界）的快照不视为陈旧`() {
        assertFalse(
            RuntimeIntegrityPolicy.isSnapshotStale(
                snapshotAtMillis = 1_000L, nowMillis = 1_400L, freshnessWindowMillis = 500L
            )
        )
        assertFalse(
            "恰好等于窗口宽度不应判为陈旧",
            RuntimeIntegrityPolicy.isSnapshotStale(
                snapshotAtMillis = 1_000L, nowMillis = 1_500L, freshnessWindowMillis = 500L
            )
        )
    }

    // ===== 缺陷 3（P2）：requireRiskNotice 的生产消费点（设置页风险提示接线） =====
    //
    // 原状：requireRiskNotice 只在测试里被引用，生产零消费——「必须给出明确风险提示」不成立。
    // 现由设置页安全分区经 RuntimeIntegrityPolicy.requiresRiskNotice(report) 单一判定渲染提示卡。

    @Test
    fun `可信档不要求风险提示`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE)

        assertFalse(RuntimeIntegrityPolicy.requiresRiskNotice(report))
    }

    @Test
    fun `可疑档与已妥协档均要求风险提示`() {
        val elevated = RuntimeIntegrityPolicy.evaluate(IntegritySignals(appDebuggable = true))
        val compromised = RuntimeIntegrityPolicy.evaluate(IntegritySignals(rootArtifactsDetected = true))

        assertTrue(RuntimeIntegrityPolicy.requiresRiskNotice(elevated))
        assertTrue(RuntimeIntegrityPolicy.requiresRiskNotice(compromised))
    }

    @Test
    fun `未判定快照不要求风险提示`() {
        // 未判定不等于已判定为风险：避免 UI 闪烁与误报
        assertFalse(RuntimeIntegrityPolicy.requiresRiskNotice(RuntimeIntegrityReport.UNDETERMINED))
    }

    @Test
    fun `快照缺失时按不提示处理`() {
        // 单测/异常装配下不得回填「有风险」假值
        assertFalse(RuntimeIntegrityPolicy.requiresRiskNotice(null))
    }

    @Test
    fun `提示判定与 UI 取文案的等级范围严格一致`() {
        // requiresRiskNotice == true ⟺ level ∈ {ELEVATED, COMPROMISED}：
        // 保证 UI 在提示分支内拿到的等级必然非空（按等级取字符串资源不会落空）
        val signalMatrix = listOf(
            IntegritySignals.NONE,
            IntegritySignals(appDebuggable = true),
            IntegritySignals(debuggerAttached = true),
            IntegritySignals(rootArtifactsDetected = true),
            IntegritySignals(magiskDetected = true),
            IntegritySignals(hookFrameworkDetected = true),
            IntegritySignals(debuggerAttached = true, appDebuggable = true),
            // ISSUE-P2-44：无障碍信号置位时等级仍为 TRUSTED，且**不**要求风险卡
            IntegritySignals(thirdPartyAccessibilityEnabled = true)
        )

        signalMatrix.forEach { signals ->
            val report = RuntimeIntegrityPolicy.evaluate(signals)
            val noticeRequired = RuntimeIntegrityPolicy.requiresRiskNotice(report)
            val noticeLevel = report.level == RuntimeRiskLevel.ELEVATED ||
                report.level == RuntimeRiskLevel.COMPROMISED
            assertEquals("信号 $signals 的提示判定与风险等级不一致", noticeLevel, noticeRequired)
        }

        // 未判定档同样保持「不提示」的一致性
        assertFalse(RuntimeIntegrityPolicy.requiresRiskNotice(RuntimeIntegrityReport.UNDETERMINED))
    }

    // ===== ISSUE-P2-44：无障碍信号「只提示、不降级」 =====

    @Test
    fun `启用第三方无障碍服务时提示位为真但等级与通道均不变`() {
        val report = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(thirdPartyAccessibilityEnabled = true)
        )

        assertTrue(RuntimeIntegrityPolicy.requiresAccessibilityNotice(report))
        // 核心不变量：无障碍是**合法可及性配置**，不得据此降级任何敏感通道
        assertEquals(RuntimeRiskLevel.TRUSTED, report.level)
        assertFalse(report.enforcement.disableBiometricQuickUnlock)
        assertFalse(report.enforcement.disableAutofill)
        // 与「完整性风险提示」正交：不得因此渲染风险卡
        assertFalse(RuntimeIntegrityPolicy.requiresRiskNotice(report))
    }

    @Test
    fun `无障碍提示位与风险等级正交`() {
        val withA11y = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(rootArtifactsDetected = true, thirdPartyAccessibilityEnabled = true)
        )
        val withoutA11y = RuntimeIntegrityPolicy.evaluate(
            IntegritySignals(rootArtifactsDetected = true)
        )

        assertTrue(RuntimeIntegrityPolicy.requiresAccessibilityNotice(withA11y))
        assertFalse(RuntimeIntegrityPolicy.requiresAccessibilityNotice(withoutA11y))
        // 等级与通道降级不因无障碍信号而改变
        assertEquals(withoutA11y.level, withA11y.level)
        assertEquals(
            withoutA11y.enforcement.disableAutofill,
            withA11y.enforcement.disableAutofill
        )
    }

    @Test
    fun `未注入快照或无该信号时无障碍提示为假`() {
        assertFalse(RuntimeIntegrityPolicy.requiresAccessibilityNotice(null))
        assertFalse(
            RuntimeIntegrityPolicy.requiresAccessibilityNotice(RuntimeIntegrityReport.UNDETERMINED)
        )
        assertFalse(
            RuntimeIntegrityPolicy.requiresAccessibilityNotice(
                RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE)
            )
        )
    }

    // ===== ISSUE-P3-83：ptrace（TracerPid）实时信号 =====

    @Test
    fun `实时 ptrace 信号升级为最高风险并同时禁用两条通道`() {
        val cached = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE)

        val escalated = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = cached,
            debuggerAttached = false,
            hookFrameworkDetected = false,
            beingTraced = true
        )

        assertEquals(RuntimeRiskLevel.COMPROMISED, escalated.level)
        assertTrue(escalated.enforcement.disableBiometricQuickUnlock)
        assertTrue(escalated.enforcement.disableAutofill)
        assertTrue(escalated.enforcement.requireRiskNotice)
    }

    @Test
    fun `缓存快照自带 ptrace 信号时同样判最高风险`() {
        val report = RuntimeIntegrityPolicy.evaluate(IntegritySignals(beingTraced = true))

        assertEquals(RuntimeRiskLevel.COMPROMISED, report.level)
    }

    @Test
    fun `TracerPid 判定只认正数`() {
        assertFalse("0 = 未被 trace", RuntimeIntegrityPolicy.isTraced(0))
        assertTrue("正数 = 正被该 pid trace", RuntimeIntegrityPolicy.isTraced(4242))
        assertFalse(
            "读不到 → 不判为被 trace：ISSUE-P3-83 是提高成本项，不得因读不到就换掉整机可用性",
            RuntimeIntegrityPolicy.isTraced(null)
        )
        assertFalse("非标准负值不得误判（判据是 > 0 而非 != 0）", RuntimeIntegrityPolicy.isTraced(-1))
    }
}
