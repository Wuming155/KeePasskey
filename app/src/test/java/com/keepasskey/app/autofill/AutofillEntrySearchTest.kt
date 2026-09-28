package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ===== ISSUE-P3-372 AC⑤：域名感知三档降级（加性 OR） =====

    @Test
    fun `子域查询命中条目父域`() {
        // Kp2a 五级降级的核心形态：查 accounts.example.com 能找到 URL 为 https://example.com 的条目
        val entry = entry("Example", "u", "https://example.com")
        assertEquals(1, AutofillEntrySearch.filter(listOf(entry), "accounts.example.com").size)
    }

    @Test
    fun `条目子域命中父域查询`() {
        val entry = entry("Bank", "u", "https://login.bank.example.com")
        assertEquals(1, AutofillEntrySearch.filter(listOf(entry), "bank.example.com").size)
    }

    @Test
    fun `去 www 归一后等值命中`() {
        val wwwEntry = entry("Www", "u", "https://www.example.com")
        val bareQuery = entry("Bare", "u", "https://example.com")

        assertEquals(1, AutofillEntrySearch.filter(listOf(wwwEntry), "example.com").size)
        assertEquals(1, AutofillEntrySearch.filter(listOf(bareQuery), "www.example.com").size)
    }

    @Test
    fun `域名档保持严格点号边界`() {
        val github = entry("GitHub", "u", "https://github.com")
        // 相似域名不得经域名档命中（子串档对 title/userName/url 同样不命中）
        assertTrue(AutofillEntrySearch.filter(listOf(github), "evilgithub.com").isEmpty())
        assertTrue(AutofillEntrySearch.filter(listOf(github), "github.com.evil.com").isEmpty())
    }

    @Test
    fun `单词查询不进域名档`() {
        assertFalse(AutofillEntrySearch.hostLadderMatches("https://example.com", "login"))
        assertFalse(AutofillEntrySearch.hostLadderMatches("https://example.com", "exa mple"))
    }

    @Test
    fun `android 绑定条目不参与域名降级`() {
        assertFalse(
            "包名不是域名，域名档对其恒不适用",
            AutofillEntrySearch.hostLadderMatches("android://com.example.app", "example.app")
        )
        // 但既有子串匹配行为不变（url 原文包含查询串仍命中）
        val bound = entry("App", "u", "android://com.example.app")
        assertEquals(1, AutofillEntrySearch.filter(listOf(bound), "com.example.app").size)
    }

    @Test
    fun `域名降级为加性命中不移除子串行为`() {
        val entry = entry("GitHub", "octocat", "https://github.com")
        assertEquals(1, AutofillEntrySearch.filter(listOf(entry), "octocat").size)
        assertEquals(1, AutofillEntrySearch.filter(listOf(entry), "gith").size)
    }
}
