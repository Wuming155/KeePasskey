package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.SearchMatchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-352 AC②：域名感知匹配档（加性 OR 层）单元测试。
 *
 * 判据结构遵循 AC④：**每条负向都配同源正向对照**（同一 fixture 换查询串），
 * 防止「测试本身永假」的环境假绿；凭据供给侧 `DomainMatcher` 零改动由
 * `CredentialProviderLookalikeMatchTest` 等既有用例另行锁定。
 */
class VaultSearchDomainTierTest {

    private fun entry(
        url: String = "",
        passkeyRpId: String? = null,
        title: String = "站点"
    ) = UiVaultEntry(
        id = "e1",
        title = title,
        username = "user",
        url = url
    ).let { if (passkeyRpId == null) it else it.copy(passkeyRpId = passkeyRpId) }

    // ===== 正向：子串档做不到的域名维命中 =====

    @Test
    fun `子域查询命中父域条目URL（纯子串做不到）`() {
        val target = entry(url = "https://example.com/login")
        // 同源正向：域名档命中
        assertTrue(domainTierMatches("login.example.com", target))
        assertTrue(matchesSearchQuery(target, "login.example.com"))
        // 同源对照：该查询不含于 URL 子串 ⇒ 命中只能来自域名档
        assertFalse(target.url.contains("login.example.com"))
    }

    @Test
    fun `父域查询命中子域条目URL`() {
        val target = entry(url = "https://accounts.example.com/auth")
        assertTrue(domainTierMatches("example.com", target))
        assertTrue(matchesSearchQuery(target, "example.com"))
    }

    @Test
    fun `www前缀两侧互命中`() {
        val target = entry(url = "https://www.example.com/")
        assertTrue(domainTierMatches("example.com", target))
        assertTrue(domainTierMatches("www.example.com", entry(url = "https://example.com/")))
    }

    @Test
    fun `查询为带协议URL时剥协议后参与域名判定`() {
        val target = entry(url = "https://example.com/")
        assertTrue(domainTierMatches("https://login.example.com/x", target))
        // 同源负向：兄弟子域（login.* vs accounts.*）不属父子关系，不互命中
        assertFalse(domainTierMatches("https://login.example.com/x", entry(url = "https://accounts.example.com/")))
    }

    @Test
    fun `passkeyRpId参与域名判定`() {
        val target = entry(url = "", passkeyRpId = "github.com")
        // 同源正向：子域 rpId 查询命中（URL 为空 ⇒ 只可能来自域名档）
        assertTrue(domainTierMatches("login.github.com", target))
        assertTrue(matchesSearchQuery(target, "login.github.com"))
        // 同源负向：异域不命中
        assertFalse(domainTierMatches("evil.github.com.evil.example", target))
    }

    // ===== 负向：点号边界 / 可注册下限 / scheme 守卫（均配同源正向） =====

    @Test
    fun `不同顶级域不互命中`() {
        val target = entry(url = "https://example.org")
        assertFalse(domainTierMatches("example.com", target))
        assertFalse(matchesSearchQuery(target, "example.com"))
        // 同源正向
        assertTrue(domainTierMatches("example.org", target))
    }

    @Test
    fun `粘连后缀不命中（点号边界）`() {
        val target = entry(url = "https://example.com")
        assertFalse(domainTierMatches("evilexample.com", target))
        assertFalse(matchesSearchQuery(target, "evilexample.com"))
        // 同源正向
        assertTrue(matchesSearchQuery(target, "example.com"))
    }

    @Test
    fun `单标签查询不进域名档（不会扫射全库）`() {
        val target = entry(url = "https://example.org")
        assertFalse(domainTierMatches("org", target))
        assertFalse(domainTierMatches("com", target))
        // 同源正向：多标签才进域名档
        assertTrue(domainTierMatches("example.org", target))
    }

    @Test
    fun `含空白的普通语句不进域名档`() {
        val target = entry(url = "https://example.com")
        assertFalse(domainTierMatches("example com", target))
        assertFalse(domainTierMatches("登录 example.com", target))
        // 同源正向
        assertTrue(domainTierMatches("example.com", target))
    }

    @Test
    fun `android绑定条目不走host维度`() {
        val target = entry(url = "android://com.foo.app/")
        assertFalse(domainTierMatches("foo.app", target))
        // 同源正向：https 条目同查询可命中
        assertTrue(domainTierMatches("foo.app", entry(url = "https://foo.app/")))
    }

    @Test
    fun `android形态查询不进域名档`() {
        val target = entry(url = "https://example.com")
        assertFalse(domainTierMatches("android://com.example.app", target))
        // 同源正向
        assertTrue(domainTierMatches("example.com", target))
    }

    // ===== 与既有匹配档的组合 =====

    @Test
    fun `分词档每个词各自可走域名档`() {
        val target = entry(url = "https://example.com/login", title = "工作账号")
        assertTrue(matchesSearchQuery(target, "工作 login.example.com", SearchMatchMode.ALL_TERMS))
        assertFalse(matchesSearchQuery(target, "工作 login.example.org", SearchMatchMode.ALL_TERMS))
    }

    @Test
    fun `域名档不改变子串档既有命中`() {
        // 既有用例口径回归：标题 / URL 子串、大小写不敏感仍成立
        val target = entry(url = "https://example.com", title = "GitHub")
        assertTrue(matchesSearchQuery(target, "github"))
        assertTrue(matchesSearchQuery(target, "EXAMPLE"))
        assertFalse(matchesSearchQuery(target, "不存在的关键字"))
    }
}
