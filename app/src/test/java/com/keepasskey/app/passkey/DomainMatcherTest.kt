package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainMatcher] 纯函数单元测试，严格覆盖 P2-14 域名安全匹配与解析要求。
 */
class DomainMatcherTest {

    @Test
    fun extractDomain_stripsProtocolsPortsPathsAndQueries() {
        assertEquals("github.com", DomainMatcher.extractDomain("https://github.com"))
        assertEquals("github.com", DomainMatcher.extractDomain("http://github.com/login?tab=1#top"))
        assertEquals("login.github.com", DomainMatcher.extractDomain("https://login.github.com:8443/auth"))
        assertEquals("example.org", DomainMatcher.extractDomain("example.org"))
        assertEquals("example.org", DomainMatcher.extractDomain("HTTPS://EXAMPLE.ORG/"))
        assertEquals("localhost", DomainMatcher.extractDomain("http://user:pass@localhost:8080/test"))
        assertEquals("127.0.0.1", DomainMatcher.extractDomain("http://127.0.0.1:3000"))
        assertEquals("::1", DomainMatcher.extractDomain("http://[::1]:8080/"))
        assertEquals("", DomainMatcher.extractDomain("   "))
    }

    @Test
    fun isDomainMatch_preventsSubdomainSpoofingAndEvilDomain() {
        // P2-14 核心安全测试：evilgithub.com 绝不能命中 github.com
        assertFalse(DomainMatcher.isDomainMatch("github.com", "evilgithub.com"))
        assertFalse(DomainMatcher.isDomainMatch("evilgithub.com", "github.com"))
        assertFalse(DomainMatcher.isDomainMatch("https://github.com", "https://evilgithub.com"))

        // 精确匹配
        assertTrue(DomainMatcher.isDomainMatch("github.com", "github.com"))
        assertTrue(DomainMatcher.isDomainMatch("https://github.com", "http://github.com/"))

        // 子域名合法匹配（严格标签边界，login.github.com 命中 github.com）
        assertTrue(DomainMatcher.isDomainMatch("github.com", "login.github.com"))
        assertTrue(DomainMatcher.isDomainMatch("github.com", "https://auth.api.github.com/v1"))

        // 反向不匹配（条目绑定了特定子域，不能用于根域或同级其他子域）
        assertFalse(DomainMatcher.isDomainMatch("login.github.com", "github.com"))
        assertFalse(DomainMatcher.isDomainMatch("login.github.com", "api.github.com"))

        // 空串防守
        assertFalse(DomainMatcher.isDomainMatch("", "github.com"))
        assertFalse(DomainMatcher.isDomainMatch("github.com", ""))
    }

    @Test
    fun isPackageMatch_validatesPackageNames() {
        assertTrue(DomainMatcher.isPackageMatch("com.example.app", "com.example.app"))
        assertTrue(DomainMatcher.isPackageMatch("COM.EXAMPLE.APP", "com.example.app"))

        // F1 整改：包名间不存在父子信任关系，任何后缀包含匹配均已废除（精确相等才算命中）
        assertFalse(DomainMatcher.isPackageMatch("example.app", "com.example.app"))
        assertFalse(DomainMatcher.isPackageMatch("com.example.app", "example.app"))

        assertFalse(DomainMatcher.isPackageMatch("com.evilapp", "com.app"))
        assertFalse(DomainMatcher.isPackageMatch("com.example.app", "com.other.app"))
        assertFalse(DomainMatcher.isPackageMatch("", "com.example.app"))
    }

    @Test
    fun isPackageMatch_rejectsSuffixFamilyPackagesBothDirections() {
        // F1 回归测试（High 越权）：两个方向的包名后缀家族均不得互相命中——
        // 覆盖审计报告示例及其修正方向（报告原示例 com.victim.app.evil 实际不匹配，
        // 可利用方向是 evil.com.victim.app 一类以受害者包名为后缀的包）
        assertFalse(DomainMatcher.isPackageMatch("com.victim.app", "com.victim.app.evil"))
        assertFalse(DomainMatcher.isPackageMatch("com.victim.app", "evil.com.victim.app"))
        assertFalse(DomainMatcher.isPackageMatch("com.victim.app", "victim.app"))
        assertFalse(DomainMatcher.isPackageMatch("victim.app", "com.victim.app"))

        // android:// 绑定条目同样按精确匹配裁决
        assertFalse(DomainMatcher.isPackageMatch("android://com.victim.app", "evil.com.victim.app"))
        assertFalse(DomainMatcher.isPackageMatch("android://com.victim.app", "com.victim.app.evil"))
    }

    @Test
    fun isDomainMatch_rejectsPublicSuffixRpIds() {
        // F5 回归测试：单标签/多标签公共后缀不得作为 RP ID / 条目域参与匹配（收敛钓鱼面）
        assertFalse(DomainMatcher.isDomainMatch("io", "example.io"))
        assertFalse(DomainMatcher.isDomainMatch("io", "io"))
        assertFalse(DomainMatcher.isDomainMatch("com.cn", "example.com.cn"))
        assertFalse(DomainMatcher.isDomainMatch("co.uk", "example.co.uk"))

        // 正常可注册域名不受影响
        assertTrue(DomainMatcher.isDomainMatch("example.io", "example.io"))
        assertTrue(DomainMatcher.isDomainMatch("example.io", "login.example.io"))
        assertTrue(DomainMatcher.isDomainMatch("example.co.uk", "www.example.co.uk"))
    }

    @Test
    fun extractAndroidBoundPackage_extractsOnlyAndroidSchemeBindings() {
        // F4 支撑函数：仅 android scheme 绑定可提取包名
        assertEquals("com.example.app", DomainMatcher.extractAndroidBoundPackage("android://com.example.app"))
        assertEquals("com.example.app", DomainMatcher.extractAndroidBoundPackage("android://com.example.app/path"))
        assertEquals("com.example.app", DomainMatcher.extractAndroidBoundPackage("ANDROID://COM.EXAMPLE.APP"))

        // Web 绑定（浏览器创建的 https 条目）不可被当作包名提取，杜绝普通应用冒领
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage("https://github.com"))
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage("github.com"))
        assertEquals(null, DomainMatcher.extractAndroidBoundPackage(""))
    }

    @Test
    fun isPackageMatch_stripsAndroidSchemeFromEntryUrl() {
        // L1 整改：入库记录为 android://<包名> 的凭据因子必须可被调用包名正确匹配
        assertTrue(DomainMatcher.isPackageMatch("android://com.example.app", "com.example.app"))
        assertTrue(DomainMatcher.isPackageMatch("android://com.example.app/path", "com.example.app"))

        // scheme 剥离后仍须保持严格点号边界
        assertFalse(DomainMatcher.isPackageMatch("android://com.evilapp", "com.app"))
        assertFalse(DomainMatcher.isPackageMatch("android://com.example.app", "com.other.app"))

        // 纯包名输入不受影响
        assertTrue(DomainMatcher.isPackageMatch("com.example.app", "com.example.app"))
    }
}
