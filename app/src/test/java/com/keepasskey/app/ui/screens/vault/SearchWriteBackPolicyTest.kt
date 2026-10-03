package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-442` 搜索词自愈回灌的判定内核用例。
 *
 * 四组判据：域名形态（可写回的必要条件）、敏感红线（`ISSUE-P2-105` 同源，口令形态不预填不回显）、
 * `{REF}` 保护（URL 含引用即拒绝）、以及 `shouldOffer` 的合成矩阵。
 */
class SearchWriteBackPolicyTest {

    // ===== 域名形态 =====

    @Test
    fun `裸域名与完整URL均视为可写回词`() {
        assertTrue(SearchWriteBackPolicy.isUrlTerm("example.com"))
        assertTrue(SearchWriteBackPolicy.isUrlTerm("login.example.com"))
        assertTrue(SearchWriteBackPolicy.isUrlTerm("https://example.com/login?next=1"))
        assertTrue(SearchWriteBackPolicy.isUrlTerm("example.com:8443"))
        assertTrue(SearchWriteBackPolicy.isUrlTerm("  example.com  "))
    }

    @Test
    fun `无点短词与含空白串不是可写回词`() {
        assertFalse(SearchWriteBackPolicy.isUrlTerm(""))
        assertFalse(SearchWriteBackPolicy.isUrlTerm("小明"))
        assertFalse(SearchWriteBackPolicy.isUrlTerm("github"))
        assertFalse(SearchWriteBackPolicy.isUrlTerm("two words.com"))
        assertFalse(SearchWriteBackPolicy.isUrlTerm("com"))
    }

    @Test
    fun `超长串不是可写回词`() {
        val tooLong = "a".repeat(SearchWriteBackPolicy.MAX_TERM_LENGTH) + ".com"
        assertFalse(SearchWriteBackPolicy.isUrlTerm(tooLong))
    }

    // ===== 敏感红线（ISSUE-P2-105 同源）=====

    @Test
    fun `含多类字符的长口令串判定为口令形态`() {
        assertTrue(SearchWriteBackPolicy.looksLikeSecretQuery("Tr0ub4dor&3xample"))
        assertTrue(SearchWriteBackPolicy.looksLikeSecretQuery("P@ssw0rdP@ssw0rd"))
    }

    @Test
    fun `域名形态与短串不判定为口令形态`() {
        assertFalse(SearchWriteBackPolicy.looksLikeSecretQuery("example.com"))
        assertFalse(SearchWriteBackPolicy.looksLikeSecretQuery("小明"))
        // 短于阈值：即便含字母 + 数字也不按口令处理（无泄露价值，误拦会吃掉正常检索词）
        assertFalse(SearchWriteBackPolicy.looksLikeSecretQuery("abc12345"))
    }

    // ===== {REF} 保护 =====

    @Test
    fun `URL 含字段引用时识别为不可覆盖`() {
        assertTrue(SearchWriteBackPolicy.hasReferenceUrl("{REF:P@T:github}"))
        assertTrue(SearchWriteBackPolicy.hasReferenceUrl("https://x.com/{ref:U@T:a}"))
        assertFalse(SearchWriteBackPolicy.hasReferenceUrl("https://example.com"))
    }

    // ===== 域覆盖判定 =====

    @Test
    fun `条目URL已覆盖搜索词域时无需自愈`() {
        assertTrue(SearchWriteBackPolicy.urlCoversTerm("https://example.com", "login.example.com"))
        assertTrue(SearchWriteBackPolicy.urlCoversTerm("https://login.example.com", "example.com"))
    }

    @Test
    fun `不同域与空URL不构成覆盖`() {
        assertFalse(SearchWriteBackPolicy.urlCoversTerm("https://other.com", "example.com"))
        assertFalse(SearchWriteBackPolicy.urlCoversTerm("", "example.com"))
        assertFalse(SearchWriteBackPolicy.urlCoversTerm("android://com.example.app", "example.com"))
    }

    // ===== 合成矩阵 =====

    @Test
    fun `搜索态点开不匹配条目时应当询问`() {
        assertTrue(
            SearchWriteBackPolicy.shouldOffer(
                query = "example.com",
                entryUrl = "https://other.com",
                entryBlocked = false,
                alreadyAsked = false,
                sessionSuppressed = false
            )
        )
        // 条目尚未配置 URL 时同样值得问（写回即修正）
        assertTrue(
            SearchWriteBackPolicy.shouldOffer(
                query = "example.com",
                entryUrl = "",
                entryBlocked = false,
                alreadyAsked = false,
                sessionSuppressed = false
            )
        )
    }

    @Test
    fun `已询问过或已勾选不再询问时不再询问`() {
        assertFalse(
            SearchWriteBackPolicy.shouldOffer(
                query = "example.com",
                entryUrl = "https://other.com",
                entryBlocked = false,
                alreadyAsked = true,
                sessionSuppressed = false
            )
        )
        assertFalse(
            SearchWriteBackPolicy.shouldOffer(
                query = "example.com",
                entryUrl = "https://other.com",
                entryBlocked = false,
                alreadyAsked = false,
                sessionSuppressed = true
            )
        )
    }

    @Test
    fun `只读回收站或含引用URL时一票否决`() {
        assertFalse(
            SearchWriteBackPolicy.shouldOffer(
                query = "example.com",
                entryUrl = "https://other.com",
                entryBlocked = true,
                alreadyAsked = false,
                sessionSuppressed = false
            )
        )
    }

    @Test
    fun `口令形态与非域名形态搜索词一律不询问`() {
        assertFalse(
            SearchWriteBackPolicy.shouldOffer(
                query = "Tr0ub4dor&3xample",
                entryUrl = "https://other.com",
                entryBlocked = false,
                alreadyAsked = false,
                sessionSuppressed = false
            )
        )
        assertFalse(
            SearchWriteBackPolicy.shouldOffer(
                query = "小明",
                entryUrl = "https://other.com",
                entryBlocked = false,
                alreadyAsked = false,
                sessionSuppressed = false
            )
        )
    }

    @Test
    fun `域已匹配时不询问`() {
        assertFalse(
            SearchWriteBackPolicy.shouldOffer(
                query = "example.com",
                entryUrl = "https://example.com",
                entryBlocked = false,
                alreadyAsked = false,
                sessionSuppressed = false
            )
        )
    }
}
