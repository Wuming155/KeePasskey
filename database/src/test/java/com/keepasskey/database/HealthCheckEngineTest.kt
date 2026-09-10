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
    fun `常见弱口令按大小写不敏感匹配（经强度引擎，不物化 String）`() {
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

    /**
     * ISSUE-P3-36 回归锁：以下口令**不在**接线前的 15 条常见口令表内、长度也 >= 8，
     * 旧实现（`长度 < 8 || 命中 15 条表`）会完全漏判；接入强度引擎后必须被识别。
     */
    @Test
    fun `模式化弱口令被强度引擎识别（旧实现漏判面）`() {
        val cases = listOf("qwertyuiop", "abcabcabc", "20260101")
        val entries = cases.mapIndexed { index, pw ->
            KdbxEntry(
                id = KdbxUuid(ByteArray(16) { (index + 20).toByte() }),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("Pattern $index", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString(pw, isProtected = true)
                )
            )
        }

        val issues = HealthCheckEngine.analyzeEntries(entries)

        for ((index, pw) in cases.withIndex()) {
            assertTrue(
                "模式化弱口令 '$pw' 应被识别为 WEAK",
                issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Pattern $index" }
            )
        }
        // 描述文案应如实带上强度评分（不夸大、不省略）
        assertTrue(
            "WEAK 文案应包含强度评分",
            issues.filter { it.riskLevel == PasswordRiskLevel.WEAK }.all { it.description.contains("/4") }
        )
    }

    /** 强口令不得被误判为 WEAK（防止强度引擎接线引入假阳性）。 */
    @Test
    fun `强口令不被误判为弱口令`() {
        val strong = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 40 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Strong Site", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("tR7#kL9@mQ2!xZ4&vB6*", isProtected = true)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(strong))

        assertFalse(
            "长随机口令不应被判为 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK }
        )
    }
}
