package com.keepasskey.app.security

import com.keepasskey.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整性禁用归因单测（**ISSUE-P2-227** 验收 AC①④）。
 *
 * 本批**刻意不改判据**（风险分级与 fail-closed 后果完全沿用整改前），改的是「为什么被禁」的可见性，
 * 故用例的重心不在等级复述（那部分由 `RuntimeIntegrityPolicyTest` 覆盖），而在三条**归因不变式**：
 * 1. **凡禁用生物解锁必有原因可点名**——`disableBiometricQuickUnlock == true` 时清单不得为空，
 *    否则用户看到的仍是笼统提示（本缺陷的原始形态）；
 * 2. **判定与呈现同源**——清单严格由命中信号导出，未命中的信号一律不得出现（不得「顺带指控」）；
 * 3. **顺序即危害度**——动态攻击特征排在静态可疑特征之前，解锁提示取首项才意味着「优先说最要紧的」。
 */
class IntegrityBlockReasonTest {

    @Test
    fun `干净信号不产出任何原因`() {
        assertTrue(IntegrityBlockReason.from(IntegritySignals.NONE, undetermined = false).isEmpty())
    }

    @Test
    fun `未判定态优先且只产出未判定一项`() {
        // 快照陈旧 / 首次扫描未完成时信号往往全为默认 false，若不点名则仍是笼统提示
        val reasons = IntegrityBlockReason.from(IntegritySignals.NONE, undetermined = true)
        assertEquals(listOf(IntegrityBlockReason.SCAN_UNDETERMINED), reasons)
    }

    @Test
    fun `每个信号各自映射到唯一原因`() {
        val cases = mapOf(
            IntegritySignals(debuggerAttached = true) to IntegrityBlockReason.DEBUGGER_ATTACHED,
            IntegritySignals(beingTraced = true) to IntegrityBlockReason.BEING_TRACED,
            IntegritySignals(hookFrameworkDetected = true) to IntegrityBlockReason.HOOK_FRAMEWORK,
            IntegritySignals(rootArtifactsDetected = true) to IntegrityBlockReason.ROOT_ARTIFACTS,
            IntegritySignals(magiskDetected = true) to IntegrityBlockReason.MAGISK,
            IntegritySignals(appDebuggable = true) to IntegrityBlockReason.DEBUGGABLE_BUILD
        )
        cases.forEach { (signals, expected) ->
            assertEquals(
                "信号 $signals 应且只应点名 $expected",
                listOf(expected),
                IntegrityBlockReason.from(signals, undetermined = false)
            )
        }
    }

    @Test
    fun `无障碍信号不得被当作降级原因`() {
        // 该信号「只提示、不降级」（ISSUE-P2-44），若被列进原因清单即构成对可及性配置的误指控
        val reasons = IntegrityBlockReason.from(
            IntegritySignals(thirdPartyAccessibilityEnabled = true),
            undetermined = false
        )
        assertTrue("无障碍命中不得产出降级原因", reasons.isEmpty())
    }

    @Test
    fun `清单顺序即危害度降序且解锁提示取到攻击特征`() {
        val allHits = IntegritySignals(
            appDebuggable = true,
            magiskDetected = true,
            debuggerAttached = true
        )
        val reasons = IntegrityBlockReason.from(allHits, undetermined = false)
        assertEquals(
            "调试器附加（明确攻击特征）须排在静态可疑特征之前",
            listOf(
                IntegrityBlockReason.DEBUGGER_ATTACHED,
                IntegrityBlockReason.MAGISK,
                IntegrityBlockReason.DEBUGGABLE_BUILD
            ),
            reasons
        )
    }

    @Test
    fun `凡禁用生物解锁的裁决都携带可点名原因`() {
        // 本缺陷的原始形态：禁了却给不出原因。新增降级路径时若忘了登记原因，此例即红
        val signalSets = listOf(
            IntegritySignals(appDebuggable = true),
            IntegritySignals(rootArtifactsDetected = true),
            IntegritySignals(debuggerAttached = true),
            IntegritySignals(beingTraced = true),
            IntegritySignals(hookFrameworkDetected = true),
            IntegritySignals(magiskDetected = true, appDebuggable = true)
        )
        signalSets.forEach { signals ->
            val enforcement = RuntimeIntegrityPolicy.evaluate(signals).enforcement
            if (enforcement.disableBiometricQuickUnlock) {
                assertTrue(
                    "禁用生物解锁却无原因可点名：$signals",
                    enforcement.biometricBlockReasons.isNotEmpty()
                )
            }
        }
        assertTrue(
            "未判定态同样须给出原因",
            IntegrityEnforcement.UNDETERMINED.biometricBlockReasons.isNotEmpty()
        )
    }

    @Test
    fun `放行态不携带任何原因`() {
        val enforcement = RuntimeIntegrityPolicy.evaluate(IntegritySignals.NONE).enforcement
        assertTrue(enforcement.biometricBlockReasons.isEmpty())
        assertTrue(IntegrityEnforcement.ALLOWED.biometricBlockReasons.isEmpty())
    }

    @Test
    fun `实时信号升级后的裁决同样点名新命中的攻击特征`() {
        // escalateForLiveSignals 走 evaluate 重算：冷启动后才出现的调试器 / ptrace 必须进入原因清单
        val base = RuntimeIntegrityPolicy.evaluate(IntegritySignals(appDebuggable = true))
        val escalated = RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = base,
            debuggerAttached = true,
            hookFrameworkDetected = false,
            beingTraced = true
        )
        assertEquals(
            listOf(
                IntegrityBlockReason.DEBUGGER_ATTACHED,
                IntegrityBlockReason.BEING_TRACED,
                IntegrityBlockReason.DEBUGGABLE_BUILD
            ),
            escalated.enforcement.biometricBlockReasons
        )
    }

    @Test
    fun `每个原因都映射到已资源化的文案`() {
        IntegrityBlockReason.entries.forEach { reason ->
            assertNotNull(reason.name, reason.messageRes)
            assertTrue(
                "${reason.name} 的 messageRes 须为已声明的字符串资源（不得为 0）",
                reason.messageRes != 0
            )
            assertEquals(
                "${reason.name} 的专属文案资源不可与其它原因复用（否则点名等于没点）",
                1,
                IntegrityBlockReason.entries.count { it.messageRes == reason.messageRes }
            )
        }
        // 通用回落文案独立存在（清单为空时使用），不得与任一专属文案同 id
        assertTrue(
            IntegrityBlockReason.entries.none { it.messageRes == R.string.sec_biometric_integrity_blocked }
        )
    }
}
