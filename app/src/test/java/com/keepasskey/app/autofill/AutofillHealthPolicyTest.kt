package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.CredentialProviderRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillHealthPolicy] 单元测试（ISSUE-P3-41；`ISSUE-P2-239` 增补 CM 通道登记两态）。
 *
 * `ISSUE-P2-239` 的判定本身（存储形态 → 三态）由
 * [com.keepasskey.app.passkey.CredentialProviderRegistrationTest] 穷举；本类只锁定
 * **报告映射与两条边界**：①「未知」不得并入正常；② CM 通道异常不得污染传统链路可用性判定。
 */
class AutofillHealthPolicyTest {

    @Test
    fun `全部正常时无异常项`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertTrue(report.isFullyOperational)
        assertTrue(report.isLegacyAutofillOperational)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `未声明服务时给出对应异常`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = false,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertEquals(listOf(AutofillHealthIssue.SERVICE_NOT_DECLARED), report.issues)
        assertFalse(report.isLegacyAutofillOperational)
    }

    @Test
    fun `系统未启用与应用内关闭分别列出`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = false,
            systemEnabled = false,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertEquals(
            listOf(AutofillHealthIssue.APP_DISABLED, AutofillHealthIssue.SYSTEM_NOT_ENABLED),
            report.issues
        )
        assertFalse(report.isLegacyAutofillOperational)
    }

    @Test
    fun `凭据管理器不可用不影响传统自动填充可用性`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = false,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertFalse(report.isFullyOperational)
        assertTrue(report.isLegacyAutofillOperational)
        assertEquals(listOf(AutofillHealthIssue.CREDENTIAL_MANAGER_UNAVAILABLE), report.issues)
    }

    /**
     * ISSUE-P3-113：字段屏蔽签名密钥不可用必须成为**显式异常项**
     * （该状态下填充被 fail-closed 放弃，用户此前只能看到「不出候选」）。
     *
     * 同时锁定边界：它**不**改变「传统链路可用性」判定——传统通道本身仍是通的，
     * 只是被保守策略拦下，二者不可混为一谈（否则 UI 会给出错误的修复指引）。
     */
    @Test
    fun `字段屏蔽密钥不可用时单独列出且不影响传统链路可用性`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            fieldBlockSignatureUnavailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertEquals(
            listOf(AutofillHealthIssue.FIELD_BLOCK_SIGNATURE_UNAVAILABLE),
            report.issues
        )
        assertFalse("存在异常项即不得报「完全可用」", report.isFullyOperational)
        assertTrue(report.isLegacyAutofillOperational)
    }

    /** 默认参数必须保持既有语义：不传该项时报告与 ISSUE-P3-113 之前完全一致 */
    @Test
    fun `未传字段屏蔽项时按可用处理`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertFalse(report.fieldBlockSignatureUnavailable)
        assertTrue(report.issues.isEmpty())
    }

    // ========== ISSUE-P2-239：CM 通道登记两态 ==========

    /**
     * 「系统未登记本应用」必须成为显式异常项（AC①）：该状态下系统**根本不会**向本应用发起
     * 创建请求，用户只看到「点保存没反应」——这正是本项要给出归因与修复路径的静默失效。
     *
     * 边界（同「字段屏蔽」先例）：它只影响 CM 通道，传统 `AutofillService` 通道照常工作，
     * 故**不得**计入「传统链路可用性」——否则界面会给出错误的修复指引。
     */
    @Test
    fun `系统未登记本应用时单独列出且不影响传统链路可用性`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.NOT_REGISTERED
        )

        assertEquals(
            listOf(AutofillHealthIssue.CREDENTIAL_PROVIDER_NOT_REGISTERED),
            report.issues
        )
        assertFalse("存在异常项即不得报「完全可用」", report.isFullyOperational)
        assertTrue(
            "CM 通道未登记不得污染传统自动填充链路判定（两者独立）",
            report.isLegacyAutofillOperational
        )
    }

    /**
     * AC② 的核心断言：**读取失败一律呈现「未知」而非「正常」**。
     *
     * 反例形态（本项要防的）：若把 `UNKNOWN` 并入「正常」，用户在「点保存没反应」时
     * 会看到一张全绿的卡 ⇒ 比不检测更坏（谎报安全）。
     */
    @Test
    fun `登记状态未知时如实列出而非当作正常`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.UNKNOWN
        )

        assertEquals(
            listOf(AutofillHealthIssue.CREDENTIAL_PROVIDER_STATE_UNKNOWN),
            report.issues
        )
        assertFalse("「未知」不得被当作「正常」，卡面必须提示需处理", report.isFullyOperational)
        assertTrue(report.isLegacyAutofillOperational)
    }

    /** 已登记时不得出现 CM 通道任何异常项（正向反校：两态各自只在对应取值下出现） */
    @Test
    fun `已登记时不出现 CM 通道异常项`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true,
            credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
        )

        assertFalse(
            "已登记却仍报 CM 项 ⇒ 判定与报告映射脱节",
            report.issues.any {
                it == AutofillHealthIssue.CREDENTIAL_PROVIDER_NOT_REGISTERED ||
                    it == AutofillHealthIssue.CREDENTIAL_PROVIDER_STATE_UNKNOWN
            }
        )
    }
}
