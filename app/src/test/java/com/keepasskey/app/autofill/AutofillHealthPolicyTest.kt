package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillHealthPolicy] 单元测试（ISSUE-P3-41）。
 */
class AutofillHealthPolicyTest {

    @Test
    fun `全部正常时无异常项`() {
        val report = AutofillHealthPolicy.evaluate(
            serviceDeclared = true,
            appEnabled = true,
            systemEnabled = true,
            credentialManagerAvailable = true
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
            credentialManagerAvailable = true
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
            credentialManagerAvailable = true
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
            credentialManagerAvailable = false
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
            fieldBlockSignatureUnavailable = true
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
            credentialManagerAvailable = true
        )

        assertFalse(report.fieldBlockSignatureUnavailable)
        assertTrue(report.issues.isEmpty())
    }
}
