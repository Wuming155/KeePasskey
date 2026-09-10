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
}
