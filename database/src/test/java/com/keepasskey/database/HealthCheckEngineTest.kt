package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HealthCheckEngine 密码健康度与安全审计单元测试
 */
class HealthCheckEngineTest {

    @Test
    fun `测试弱密码与重复密码识别`() {
        val weakEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 1 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site 1", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("123456", isProtected = true)
            )
        )

        val reusedEntry1 = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 2 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site 2", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("StrongPass#2026!", isProtected = true)
            )
        )

        val reusedEntry2 = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 3 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site 3", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("StrongPass#2026!", isProtected = true)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(weakEntry, reusedEntry1, reusedEntry2))
        assertTrue(issues.any { it.riskLevel == PasswordRiskLevel.WEAK })
        assertTrue(issues.any { it.riskLevel == PasswordRiskLevel.REUSED })
    }
}
