package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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

    @Test
    fun `测试已声明过期时间的过期条目被标记为 EXPIRED`() {
        val expiredEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 4 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Expired Site", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("StrongPass#2026!", isProtected = true)
            ),
            times = KdbxTimes(
                expires = true,
                expiryTime = Instant.now().minusSeconds(86_400)
            )
        )

        val freshEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 5 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Fresh Site", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("AnotherStrong#2026!", isProtected = true)
            ),
            times = KdbxTimes(expires = true, expiryTime = Instant.now().plusSeconds(86_400))
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(expiredEntry, freshEntry))
        assertTrue(issues.any { it.riskLevel == PasswordRiskLevel.EXPIRED && it.title == "Expired Site" })
        assertFalse(issues.any { it.riskLevel == PasswordRiskLevel.EXPIRED && it.title == "Fresh Site" })
    }

    @Test
    fun `常见弱口令按大小写不敏感字符数组匹配（不再物化 String）`() {
        val upperWeak = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 6 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Upper Weak", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("PASSWORD", isProtected = true)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(upperWeak))

        assertTrue(
            "大小写不敏感匹配必须识别 'PASSWORD' 为常见弱口令",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Upper Weak" }
        )
    }
}
