package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DomainMatcher 严格域名匹配与 RP ID 可信校验单元测试。
 * 覆盖：WebAuthn 域名匹配点号边界、公共后缀下限拒绝（F5）、
 * 创建分支 rp.id↔origin 可注册后缀绑定（Wave 12）、
 * 以及填充链路包名匹配的 `android://` 硬约束（P2-40）。
 */
class DomainMatcherTest {

    // ===== isDomainMatch（断言/填充侧） =====

    @Test
    fun `域名匹配_完全相等与子域命中`() {
        assertTrue(DomainMatcher.isDomainMatch("github.com", "github.com"))
        assertTrue(DomainMatcher.isDomainMatch("github.com", "login.github.com"))
        // 大小写与协议归一化
        assertTrue(DomainMatcher.isDomainMatch("HTTPS://GitHub.com", "https://login.github.com/path"))
    }

    @Test
    fun `域名匹配_拒绝后缀伪装与交叉域`() {
        // 严禁模糊 contains：evilgithub.com 不得命中 github.com
        assertFalse(DomainMatcher.isDomainMatch("github.com", "evilgithub.com"))
        assertFalse(DomainMatcher.isDomainMatch("github.com", "github.com.evil.io"))
        assertFalse(DomainMatcher.isDomainMatch("google.com", "github.com"))
    }

    @Test
    fun `域名匹配_公共后缀与单标签凭据一律拒绝`() {
        // F5：公共后缀/单标签不得作为凭据侧 RP ID（防整条 TLD 冒充）
        assertFalse(DomainMatcher.isDomainMatch("co.uk", "news.bbc.co.uk"))
        assertFalse(DomainMatcher.isDomainMatch("com", "example.com"))
        assertFalse(DomainMatcher.isDomainMatch("localhost", "localhost"))
    }

    @Test
    fun `域名匹配_多级公共后缀下的可注册域正常命中`() {
        // 凭据侧为公共后缀之下的可注册域：正常命中
        assertTrue(DomainMatcher.isDomainMatch("bbc.co.uk", "news.bbc.co.uk"))
        assertTrue(DomainMatcher.isDomainMatch("example.com.cn", "www.example.com.cn"))
    }

    // ===== isRpIdTrustedForCreation（Wave 12 创建分支） =====

    @Test
    fun `创建校验_浏览器来源_rp_id 须为 origin 可注册后缀`() {
        // rp.id 为 origin 主机或其父域（origin 是 rp.id 的子域）才可信
        val browserOrigin = "https://github.com"
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("github.com", browserOrigin))
        val subdomainOrigin = "https://login.github.com"
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("github.com", subdomainOrigin))
        // 跨域与后缀伪装一律拒绝
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("evil-github.com", browserOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("github.com.evil.io", browserOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("google.com", browserOrigin))
        // rp.id 为 origin 的子域不可信（rp.id 必须是 origin 的后缀，方向不可反转）
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("login.github.com", browserOrigin))
    }

    @Test
    fun `创建校验_浏览器来源_公共后缀与单标签拒绝`() {
        val browserOrigin = "https://news.bbc.co.uk"
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("co.uk", browserOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("uk", browserOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("", browserOrigin))
    }

    @Test
    fun `创建校验_普通应用来源_仅要求可注册域并拒绝公共后缀`() {
        // android:apk-key-hash origin 无 web 域可绑定：归属由 RP 服务端 DAL 裁决
        val appOrigin = "android:apk-key-hash:AbCdEf123456"
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("app.example.com", appOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("co.uk", appOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("com", appOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("", appOrigin))
    }

    @Test
    fun `创建校验_空来源按普通应用规则降级`() {
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("app.example.com", ""))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("co.uk", ""))
    }

    // ===== 完整 PSL 接入（批次 G / P1） =====

    @Test
    fun `PSL_原硬编码清单漏判项一律拒绝`() {
        // 47 条旧清单漏判：整条公共后缀不得作为凭据侧 RP ID
        assertFalse(DomainMatcher.isDomainMatch("edu.cn", "mail.tsinghua.edu.cn"))
        assertFalse(DomainMatcher.isDomainMatch("gov.au", "service.nsw.gov.au"))
        assertFalse(DomainMatcher.isDomainMatch("co.id", "shop.tokopedia.co.id"))
        assertFalse(DomainMatcher.isDomainMatch("ac.jp", "www.u-tokyo.ac.jp"))
    }

    @Test
    fun `PSL_公共后缀之下的可注册域正常命中`() {
        assertTrue(DomainMatcher.isDomainMatch("example.edu.cn", "www.example.edu.cn"))
        assertTrue(DomainMatcher.isDomainMatch("example.gov.au", "portal.example.gov.au"))
    }

    @Test
    fun `PSL_通配规则与例外规则`() {
        // *.ck：foo.ck 为公共后缀（不可注册），其子域可注册
        assertFalse(DomainMatcher.isDomainMatch("foo.ck", "www.foo.ck"))
        assertTrue(DomainMatcher.isDomainMatch("bar.foo.ck", "www.bar.foo.ck"))
        // !www.ck 例外：www.ck 本身可注册
        assertTrue(DomainMatcher.isDomainMatch("www.ck", "www.ck"))
        assertFalse(DomainMatcher.isDomainMatch("ck", "www.ck"))
    }

    @Test
    fun `PSL_私有段_github_io 自身拒绝而子域放行`() {
        assertFalse(DomainMatcher.isDomainMatch("github.io", "myproject.github.io"))
        assertFalse(DomainMatcher.isDomainMatch("gitlab.io", "pages.gitlab.io"))
        assertTrue(DomainMatcher.isDomainMatch("myproject.github.io", "myproject.github.io"))
    }

    @Test
    fun `PSL_IDN 与 punycode 等价判定`() {
        // 中国域名：unicode 与 punycode 等价
        assertFalse(DomainMatcher.isDomainMatch("中国", "www.example.中国"))
        assertFalse(DomainMatcher.isDomainMatch("xn--fiqs8s", "www.example.中国"))
        assertTrue(DomainMatcher.isDomainMatch("example.中国", "www.example.中国"))
        assertTrue(DomainMatcher.isDomainMatch("example.xn--fiqs8s", "www.example.中国"))
    }

    @Test
    fun `PSL_尾部根点与非法输入 fail 处理`() {
        // 尾部根点归一
        assertTrue(PublicSuffixList.isRegistrableDomain("example.com."))
        // 空串 / 单标签 / TLD 一律不可注册
        assertFalse(PublicSuffixList.isRegistrableDomain(""))
        assertFalse(PublicSuffixList.isRegistrableDomain("localhost"))
        assertFalse(PublicSuffixList.isRegistrableDomain("com"))
        // 内部空标签拒绝
        assertFalse(PublicSuffixList.isRegistrableDomain("a..com"))
    }

    // ===== isPackageMatch（F1 回归锁，宽松变体：仅供保存侧去重） =====

    @Test
    fun `包名匹配_剥离 android scheme 后精确相等`() {
        assertTrue(DomainMatcher.isPackageMatch("android://com.example.app", "com.example.app"))
        assertTrue(DomainMatcher.isPackageMatch("com.example.app", "com.example.app"))
        // 后缀包名不做任何父子信任匹配（CWE-284）
        assertFalse(DomainMatcher.isPackageMatch("com.example.app", "evil.com.example.app"))
        assertFalse(DomainMatcher.isPackageMatch("com.example.app.pro", "com.example.app"))
    }

    // ===== isAndroidPackageMatch（P2-40：填充链路 android:// 硬约束） =====

    @Test
    fun `填充包名匹配_Web绑定条目不得被同形包名命中`() {
        // 缺陷复现锚点：整改前 isPackageMatch("https://github.com", "github.com") == true，
        // 任意开发者注册 github.com 形态包名即可冒领该站 Web 绑定凭据
        assertFalse(DomainMatcher.isAndroidPackageMatch("https://github.com", "github.com"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("https://github.com/login", "github.com"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("http://github.com", "github.com"))
        // 裸包名同样不再放行（只认显式 android:// 绑定）
        assertFalse(DomainMatcher.isAndroidPackageMatch("github.com", "github.com"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("com.example.app", "com.example.app"))
        // 其他 scheme 一律不构成包名绑定
        assertFalse(DomainMatcher.isAndroidPackageMatch("webauthn://com.example.app", "com.example.app"))
    }

    @Test
    fun `填充包名匹配_android 绑定条目正常命中`() {
        assertTrue(DomainMatcher.isAndroidPackageMatch("android://com.example.app", "com.example.app"))
        assertTrue(DomainMatcher.isAndroidPackageMatch("android://com.example.app/login", "com.example.app"))
        // 大小写与首尾空白归一
        assertTrue(DomainMatcher.isAndroidPackageMatch("ANDROID://Com.Example.App", " com.example.app "))
        // 包名之间无父子信任：前缀 / 后缀一律拒绝（CWE-284）
        assertFalse(DomainMatcher.isAndroidPackageMatch("android://com.example.app", "evil.com.example.app"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("android://com.example.app.pro", "com.example.app"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("android://evil.com.example.app", "com.example.app"))
    }

    @Test
    fun `填充包名匹配_空白输入 fail-closed`() {
        assertFalse(DomainMatcher.isAndroidPackageMatch("android://", "com.example.app"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("", "com.example.app"))
        assertFalse(DomainMatcher.isAndroidPackageMatch("android://com.example.app", ""))
        assertFalse(DomainMatcher.isAndroidPackageMatch("android://com.example.app", "   "))
    }

    // ===== extractAndroidBoundPackage（F4 回归锁） =====

    @Test
    fun `android 绑定提取_仅接受 android scheme`() {
        assertEquals("com.example.app", DomainMatcher.extractAndroidBoundPackage("android://com.example.app"))
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage("https://github.com"))
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage("webauthn://com.example.app"))
    }
}
