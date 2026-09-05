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
        assertTrue(DomainMatcher.isPackageMatch("example.app", "com.example.app"))
        assertTrue(DomainMatcher.isPackageMatch("com.example.app", "example.app"))

        assertFalse(DomainMatcher.isPackageMatch("com.evilapp", "com.app"))
        assertFalse(DomainMatcher.isPackageMatch("com.example.app", "com.other.app"))
        assertFalse(DomainMatcher.isPackageMatch("", "com.example.app"))
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
