package com.keepasskey.app.passkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.app.autofill.AutofillWebDomainPolicy
import com.keepasskey.app.autofill.WebDomainAttribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 域名匹配内核的**设备侧（instrumented）**回归（ISSUE-P2-27 解析层 / P3-66）。
 *
 * ## 为什么必须有这一层
 *
 * [DomainMatcher] 的域名匹配依赖三处**平台运行时相关**的能力，宿主 JVM 全绿不代表设备可用：
 * - `java.net.IDN.toASCII`（ICU 实现，JVM 与 Android 的 ICU 版本可不同）；
 * - [PublicSuffixList] 的 Mozilla PSL 全量数据（大批量字符串索引在 ART 上的行为）；
 * - `Regex` / 字符串归一化（本仓已两度发生 ICU 正则差异导致的运行时缺陷：ISSUE-P1-12 / ISSUE-P0-04）。
 *
 * 故把「域归一化 + 公共后缀下限 + IDN 跨形式匹配」放回真实 Android 运行时执行，
 * 作为该类缺陷的设备侧看门用例。
 */
@RunWith(AndroidJUnit4::class)
class DomainMatcherAndroidRuntimeTest {

    @Test
    fun `提取主机名剥离 scheme 路径端口与认证信息`() {
        assertEquals(
            "example.com",
            DomainMatcher.extractDomain("https://user:pass@Example.COM:8443/path?q=1#frag")
        )
        assertEquals("sub.example.co.uk", DomainMatcher.extractDomain("http://sub.example.co.uk:8080/"))
        assertEquals("example.com", DomainMatcher.extractDomain("example.com"))
    }

    @Test
    fun `严格点号边界匹配：拒绝前缀伪装域名`() {
        assertTrue(DomainMatcher.isDomainMatch("github.com", "https://github.com/"))
        assertTrue(DomainMatcher.isDomainMatch("github.com", "https://login.github.com/"))
        assertFalse("evilgithub.com 不得冒充 github.com", DomainMatcher.isDomainMatch("github.com", "https://evilgithub.com/"))
    }

    @Test
    fun `公共后缀下限：拒绝以公共后缀充当凭据域名`() {
        assertFalse("单标签 TLD 一律拒绝", DomainMatcher.isDomainMatch("com", "https://example.com/"))
        assertFalse(
            "多标签公共后缀不得充当凭据域名",
            DomainMatcher.isDomainMatch("com.cn", "https://example.com.cn/")
        )
    }

    @Test
    fun `私有段后缀在设备侧仍按可注册域处理`() {
        assertTrue(
            "github.io 属私有段后缀，user.github.io 应可与其匹配",
            DomainMatcher.isDomainMatch("user.github.io", "https://user.github.io/")
        )
    }

    @Test
    fun `IDN 主机名经 punycode 归一后可跨形式匹配`() {
        // 设备侧 java.net.IDN 必须把 unicode 与 punycode 两种写法归一为同一主机
        assertTrue(
            DomainMatcher.isDomainMatch("münchen.de", "https://xn--mnchen-3ya.de/")
        )
        assertTrue(
            DomainMatcher.isDomainMatch("xn--mnchen-3ya.de", "https://münchen.de/")
        )
    }

    @Test
    fun `webDomain 归一化在小写化后仍满足主机形态校验`() {
        assertEquals(
            "www.example.com",
            AutofillWebDomainPolicy.normalizeDomain("HTTPS://WWW.Example.COM/login")
        )
        assertEquals(null, AutofillWebDomainPolicy.normalizeDomain("localhost"))
        assertEquals(null, AutofillWebDomainPolicy.normalizeDomain("not a domain"))
        assertEquals(null, AutofillWebDomainPolicy.normalizeDomain(null))
    }

    @Test
    fun `webDomain 归属判定在设备侧保持 fail-closed`() {
        // 未取证指纹的浏览器包名 + 无 DAL 证明 → 拒绝
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute(
                callingPackage = "com.example.clone",
                rawWebDomain = "https://example.com",
                certSha256Hex = null,
                dalVerified = false
            )
        )
        // 非法 webDomain 即使 DAL 通过也拒绝
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute(
                callingPackage = "com.example.app",
                rawWebDomain = "not a domain",
                certSha256Hex = "00",
                dalVerified = true
            )
        )
        // 合法域 + DAL 证明 → 放行
        assertEquals(
            WebDomainAttribution.DAL_VERIFIED,
            AutofillWebDomainPolicy.attribute(
                callingPackage = "com.example.app",
                rawWebDomain = "https://example.com",
                certSha256Hex = "00",
                dalVerified = true
            )
        )
    }
}
