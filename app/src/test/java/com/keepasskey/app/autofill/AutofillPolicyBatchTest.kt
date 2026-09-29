package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-P2-383 混域 / P3-393 过期 / P2-380 模板桥接 / P3-382 去重（JVM）。
 */
class AutofillPolicyBatchTest {

    private fun node(
        id: String,
        hints: List<String> = emptyList(),
        webDomain: String? = null,
        htmlName: String? = null
    ) = ScanNode(id = id, autofillHints = hints, webDomain = webDomain, htmlName = htmlName)

    @Test
    fun `混域整结构拒绝`() {
        val result = AutofillFieldScanner.scan(
            listOf(
                node("1", listOf("username"), webDomain = "a.example.com"),
                node("2", listOf("password"), webDomain = "b.example.com")
            )
        )
        assertNull(result.webDomain)
        assertNull(result.usernameId)
        assertNull(result.passwordId)
    }

    @Test
    fun `纯同域不受影响`() {
        val result = AutofillFieldScanner.scan(
            listOf(
                node("1", listOf("username"), webDomain = "a.example.com"),
                node("2", listOf("password"), webDomain = "a.example.com")
            )
        )
        assertEquals("a.example.com", result.webDomain)
        assertEquals("1", result.usernameId)
        assertEquals("2", result.passwordId)
    }

    @Test
    fun `单一域与空白域不构成混域`() {
        val result = AutofillFieldScanner.scan(
            listOf(
                node("1", listOf("username"), webDomain = "a.example.com"),
                node("2", listOf("password"), webDomain = "  "),
                node("3", listOf("username"), webDomain = null)
            )
        )
        assertEquals("a.example.com", result.webDomain)
        assertEquals("1", result.usernameId)
    }

    private fun entryWithExpiry(expires: Boolean, expiry: Instant): KdbxEntry = KdbxEntry(
        id = KdbxUuid.random(),
        parentGroupId = null,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("E", false),
            KdbxConstants.Fields.PASSWORD to ProtectedString("p", true),
            KdbxConstants.Fields.URL to ProtectedString("https://a.example.com", false),
            KdbxConstants.Fields.USER_NAME to ProtectedString("u", false)
        ),
        times = KdbxTimes(expires = expires, expiryTime = expiry, lastModificationTime = Instant.parse("2026-01-01T00:00:00Z"))
    )

    @Test
    fun `过期条目不进 ranker`() {
        val now = Instant.now()
        val expired = entryWithExpiry(true, now.minusSeconds(60))
        val fresh = entryWithExpiry(true, now.plusSeconds(3600))
        val never = entryWithExpiry(false, now)

        // rank 内部用 Instant.now()，因此用 isEntryExpired 直接断言排除语义
        assertTrue(AutofillCandidateRanker.isEntryExpired(expired, now))
        assertFalse(AutofillCandidateRanker.isEntryExpired(fresh, now))
        assertFalse(AutofillCandidateRanker.isEntryExpired(never, now))

        val ranked = AutofillCandidateRanker.rank(
            entries = listOf(expired, fresh, never),
            callingPackage = "com.example.app",
            webDomain = "a.example.com",
            packageDimensionAuthorized = false
        )
        assertTrue(ranked.none { it.entry === expired })
        assertTrue(ranked.any { it.entry === fresh })
        assertTrue(ranked.any { it.entry === never })
    }

    @Test
    fun `结构化识别接受模板中文键`() {
        val entry = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = null,
            fields = emptyMap(),
            customFields = listOf(
                KdbxCustomField("卡号", ProtectedString("4111111111111111", true)),
                KdbxCustomField("CVV", ProtectedString("123", true)),
                KdbxCustomField("有效期", ProtectedString("12/30", false))
            ),
            times = KdbxTimes()
        )
        val roles = setOf(
            StructuredFieldRole.CREDIT_CARD_NUMBER,
            StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
            StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH,
            StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR
        )
        assertTrue(StructuredFieldPolicy.hasAllFields(entry, roles))
        val values = StructuredFieldPolicy.valuesFor(entry, roles)
        assertEquals("4111111111111111", values[StructuredFieldRole.CREDIT_CARD_NUMBER])
        assertEquals("123", values[StructuredFieldRole.CREDIT_CARD_SECURITY_CODE])
        assertEquals("12", values[StructuredFieldRole.CREDIT_CARD_EXPIRATION_MONTH])
        assertEquals("30", values[StructuredFieldRole.CREDIT_CARD_EXPIRATION_YEAR])
    }

    @Test
    fun `hint 键优先于中文模板键`() {
        val entry = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = null,
            fields = emptyMap(),
            customFields = listOf(
                KdbxCustomField("creditCardNumber", ProtectedString("4222", false)),
                KdbxCustomField("卡号", ProtectedString("4333", false))
            ),
            times = KdbxTimes()
        )
        val values = StructuredFieldPolicy.valuesFor(
            entry,
            setOf(StructuredFieldRole.CREDIT_CARD_NUMBER)
        )
        assertEquals("4222", values[StructuredFieldRole.CREDIT_CARD_NUMBER])
    }

    @Test
    fun `重复扫描同 URL 同账号`() {
        fun e(url: String, user: String, id: KdbxUuid = KdbxUuid.random()) = KdbxEntry(
            id = id,
            parentGroupId = null,
            fields = mapOf(
                KdbxConstants.Fields.USER_NAME to ProtectedString(user, false),
                KdbxConstants.Fields.URL to ProtectedString(url, false)
            ),
            times = KdbxTimes()
        )
        val groups = DuplicateEntryScanner.scan(
            listOf(
                e("https://www.example.com/", "alice"),
                e("http://example.com", "alice"),
                e("https://other.com", "bob")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].entries.size)
        assertEquals(DuplicateEntryScanner.DuplicateRule.SAME_URL_AND_USERNAME, groups[0].rule)
    }

    @Test
    fun `重复扫描负例不同 URL`() {
        fun e(url: String, user: String) = KdbxEntry(
            id = KdbxUuid.random(),
            parentGroupId = null,
            fields = mapOf(
                KdbxConstants.Fields.USER_NAME to ProtectedString(user, false),
                KdbxConstants.Fields.URL to ProtectedString(url, false)
            ),
            times = KdbxTimes()
        )
        val groups = DuplicateEntryScanner.scan(
            listOf(
                e("https://a.com", "alice"),
                e("https://b.com", "alice")
            )
        )
        assertTrue(groups.isEmpty())
    }
}
