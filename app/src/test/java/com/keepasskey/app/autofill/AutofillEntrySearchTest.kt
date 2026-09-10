package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillEntrySearch] 单元测试（ISSUE-P3-40）。
 */
class AutofillEntrySearchTest {

    private fun entry(title: String, userName: String, url: String): KdbxEntry = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to com.keepasskey.core.security.ProtectedString(
                title,
                isProtected = false
            ),
            KdbxConstants.Fields.USER_NAME to com.keepasskey.core.security.ProtectedString(
                userName,
                isProtected = false
            ),
            KdbxConstants.Fields.URL to com.keepasskey.core.security.ProtectedString(
                url,
                isProtected = false
            )
        )
    )

    @Test
    fun `空查询返回全部并受上限约束`() {
        val entries = (1..60).map { entry("t$it", "u$it", "https://e$it.com") }

        assertEquals(AutofillEntrySearch.DEFAULT_LIMIT, AutofillEntrySearch.filter(entries, "").size)
        assertEquals(3, AutofillEntrySearch.filter(entries, "", limit = 3).size)
    }

    @Test
    fun `按标题用户名网址匹配且大小写不敏感`() {
        val entries = listOf(
            entry("GitHub", "octocat", "https://github.com"),
            entry("GitLab", "labuser", "https://gitlab.com"),
            entry("Bank", "user", "https://bank.example.com")
        )

        assertEquals(1, AutofillEntrySearch.filter(entries, "GITHUB").size)
        assertEquals(1, AutofillEntrySearch.filter(entries, "octocat").size)
        assertEquals(1, AutofillEntrySearch.filter(entries, "bank.example").size)
        assertTrue(AutofillEntrySearch.filter(entries, "nonexistent").isEmpty())
    }

    @Test
    fun `查询首尾空白被忽略`() {
        val entries = listOf(entry("GitHub", "octocat", "https://github.com"))
        assertEquals(1, AutofillEntrySearch.filter(entries, "  github  ").size)
    }

    @Test
    fun `空列表返回空`() {
        assertTrue(AutofillEntrySearch.filter(emptyList(), "x").isEmpty())
    }
}
