package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutofillWebDomainPolicy webDomain 归属判定单元测试（ISSUE-P2-07 / ZT-12 / ISSUE-P1-11）。
 *
 * 核心安全断言：**非浏览器应用不得以 webDomain 参与凭据匹配**——未通过 DAL 归属校验时必须
 * REJECTED（fail-closed）。ISSUE-P1-11 追加断言：**浏览器分支必须同时校验签名证书指纹**——
 * 仅占用受信浏览器包名但签名不匹配的调用方一律 REJECTED，杜绝侧载冒名应用冒领站点凭据。
 */
class AutofillWebDomainPolicyTest {

    /** Chrome 已取证指纹（大写无冒号，与 BrowserSigningFingerprints 一致） */
    private val chromeFingerprint =
        "32A2FC74D731105859E5A85DF16D95F102D85B22099B8064C6D6BABBB6652849F"

    /** Firefox 正式版已取证指纹（Mozilla 官方 sources） */
    private val firefoxFingerprint =
        "5004779088E7F988D5BC5CC5F8798FEBF4F8CD084A1B2A46EFD4C8EE4AEAF211"

    @Test
    fun `受信浏览器包名与指纹均匹配时放行 webDomain`() {
        assertEquals(
            WebDomainAttribution.BROWSER_DELEGATED,
            AutofillWebDomainPolicy.attribute(
                "com.android.chrome", "https://github.com/login", chromeFingerprint, false
            )
        )
    }

    @Test
    fun `占用受信浏览器包名但签名指纹不匹配时拒绝`() {
        // ISSUE-P1-11 攻击链：侧载 APK 占用 com.android.chrome 包名但签名不同
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute(
                "com.android.chrome",
                "https://github.com/login",
                "DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF",
                false
            )
        )
    }

    @Test
    fun `占用受信浏览器包名但无法取得指纹时拒绝`() {
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "https://github.com", null, false)
        )
    }

    @Test
    fun `未取证浏览器包名即使携带任意指纹也拒绝`() {
        // 原「仅包名」列表中的浏览器未取得权威指纹来源 → 不再直接信任
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.brave.browser", "https://github.com", chromeFingerprint, false)
        )
    }

    @Test
    fun `非浏览器应用未经 DAL 校验时拒绝 webDomain`() {
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.example.evil", "https://github.com", null, false)
        )
    }

    @Test
    fun `非浏览器应用经 DAL 归属校验后可放行 webDomain`() {
        assertEquals(
            WebDomainAttribution.DAL_VERIFIED,
            AutofillWebDomainPolicy.attribute(
                "com.example.bank", "https://bank.example.com", "AA".repeat(32), true
            )
        )
    }

    @Test
    fun `浏览器包名缺失或 webDomain 非法时一律拒绝`() {
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", null, chromeFingerprint, false)
        )
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "   ", chromeFingerprint, false)
        )
        // 单标签 / 含空白 / 连续点号均非法
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "single", chromeFingerprint, false)
        )
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "not a domain", chromeFingerprint, false)
        )
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute("com.android.chrome", "example..com", chromeFingerprint, false)
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
    fun `受信浏览器判定对包名大小写空白与指纹大小写不敏感`() {
        assertTrue(AutofillWebDomainPolicy.isTrustedBrowser("  COM.Android.CHROME  ", chromeFingerprint))
        assertTrue(AutofillWebDomainPolicy.isTrustedBrowser("org.mozilla.firefox", firefoxFingerprint))
        // 指纹小写输入同样命中（内部统一大写比对）
        assertTrue(AutofillWebDomainPolicy.isTrustedBrowser("com.android.chrome", chromeFingerprint.lowercase()))
        assertFalse(AutofillWebDomainPolicy.isTrustedBrowser("com.example.evil", chromeFingerprint))
        assertFalse(AutofillWebDomainPolicy.isTrustedBrowser("", chromeFingerprint))
        assertFalse(AutofillWebDomainPolicy.isTrustedBrowser("com.android.chrome", null))
    }
}
