package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DomainMatcher 严格域名匹配与 RP ID 可信校验单元测试。
 * 覆盖：WebAuthn 域名匹配点号边界、公共后缀下限拒绝（F5）、
 * 创建分支 rp.id↔origin 可注册后缀绑定（Wave 12）。
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

    // ===== isPackageMatch（F1 回归锁） =====

    @Test
    fun `包名匹配_剥离 android scheme 后精确相等`() {
        assertTrue(DomainMatcher.isPackageMatch("android://com.example.app", "com.example.app"))
        assertTrue(DomainMatcher.isPackageMatch("com.example.app", "com.example.app"))
        // 后缀包名不做任何父子信任匹配（CWE-284）
        assertFalse(DomainMatcher.isPackageMatch("com.example.app", "evil.com.example.app"))
        assertFalse(DomainMatcher.isPackageMatch("com.example.app.pro", "com.example.app"))
    }

    // ===== extractAndroidBoundPackage（F4 回归锁） =====

    @Test
    fun `android 绑定提取_仅接受 android scheme`() {
        assertEquals("com.example.app", DomainMatcher.extractAndroidBoundPackage("android://com.example.app"))
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage("https://github.com"))
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage("webauthn://com.example.app"))
    }
}
