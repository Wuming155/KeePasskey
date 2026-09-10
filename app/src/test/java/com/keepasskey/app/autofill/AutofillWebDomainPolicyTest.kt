package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutofillWebDomainPolicy webDomain 归属判定单元测试（ISSUE-P2-07 / ZT-12）。
 *
 * 核心安全断言：**非浏览器应用不得以 webDomain 参与凭据匹配**——
 * 未通过 DAL 归属校验时必须 REJECTED（fail-closed），杜绝伪造 webDomain 冒领站点凭据。
 */
class AutofillWebDomainPolicyTest {

    @Test
    fun `受信任浏览器委派调用放行 webDomain`() {
        assertEquals(
            WebDomainAttribution.BROWSER_DELEGATED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "https://github.com/login", false)
        )
    }

    @Test
    fun `非浏览器应用未经 DAL 校验时拒绝 webDomain`() {
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.example.evil", "https://github.com", false)
        )
    }

    @Test
    fun `非浏览器应用经 DAL 归属校验后可放行 webDomain`() {
        assertEquals(
            WebDomainAttribution.DAL_VERIFIED,
            AutofillWebDomainPolicy.attribute("com.example.bank", "https://bank.example.com", true)
        )
    }

    @Test
    fun `浏览器包名缺失或 webDomain 非法时一律拒绝`() {
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", null, false)
        )
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "   ", false)
        )
        // 单标签 / 含空白 / 连续点号均非法
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "single", false)
        )
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "not a domain", false)
        )
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "example..com", false)
        )
    }

    @Test
    fun `域名归一化剥离协议路径端口并小写`() {
        assertEquals("example.com", AutofillWebDomainPolicy.normalizeDomain("https://Example.com/login?x=1"))
        assertEquals("github.com", AutofillWebDomainPolicy.normalizeDomain("  GitHub.com:8443  "))
        assertEquals("sub.example.com", AutofillWebDomainPolicy.normalizeDomain("https://sub.example.com/x"))
        assertNull(AutofillWebDomainPolicy.normalizeDomain(null))
        assertNull(AutofillWebDomainPolicy.normalizeDomain(""))
    }

    @Test
    fun `浏览器白名单匹配大小写与空白不敏感`() {
        assertTrue(AutofillWebDomainPolicy.isTrustedBrowser("  COM.Android.CHROME  "))
        assertTrue(AutofillWebDomainPolicy.isTrustedBrowser("org.mozilla.firefox"))
        assertFalse(AutofillWebDomainPolicy.isTrustedBrowser("com.example.evil"))
        assertFalse(AutofillWebDomainPolicy.isTrustedBrowser(""))
    }
}
