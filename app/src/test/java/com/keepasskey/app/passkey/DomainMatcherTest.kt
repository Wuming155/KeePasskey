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
        // localhost 不参与后缀扩展（本地 RP 的精确相等放行见下一个用例）
        assertFalse(DomainMatcher.isDomainMatch("localhost", "app.localhost"))
    }

    /**
     * ENG-20261028-40：IP 形态**只允许精确相等**。整改前 `168.1.1` 会经 PSL 默认规则被判为
     * 可注册域，从而命中 `192.168.1.1` 的后缀，令不同地址被视为同一主机。
     */
    @Test
    fun `域名匹配_IP 形态仅精确相等`() {
        assertTrue(DomainMatcher.isDomainMatch("192.168.1.1", "192.168.1.1"))
        assertTrue(DomainMatcher.isDomainMatch("http://192.168.1.1:8080/admin", "192.168.1.1"))
        assertFalse(DomainMatcher.isDomainMatch("168.1.1", "192.168.1.1"))
        assertFalse(DomainMatcher.isDomainMatch("192.168.1.1", "1.192.168.1.1"))
        assertFalse(DomainMatcher.isDomainMatch("192.168.1.1", "192.168.1.2"))
        // 域名与 IP 两个方向都不得互相命中
        assertFalse(DomainMatcher.isDomainMatch("1.1", "192.168.1.1"))
        assertFalse(DomainMatcher.isDomainMatch("example.com", "192.168.1.1"))
        // IPv6 字面量不是可授权主机（保持既有 fail-closed 口径）
        assertFalse(DomainMatcher.isDomainMatch("::1", "::1"))
        assertFalse(DomainMatcher.isDomainMatch("[::1]", "http://[::1]/"))
    }

    /**
     * ENG-20261028-40：`localhost` 是单标签本地名，被 PSL 下限拒绝曾导致本地 RP 无法建凭据 /
     * 匹配。放行范围刻意只到**逐字相等**：`*.localhost` 仍按普通域名走 PSL 默认规则，
     * 避免一个回环服务借 `localhost` 凭据覆盖其它本地名。
     */
    @Test
    fun `域名匹配_localhost 本地场景仅精确相等`() {
        assertTrue(DomainMatcher.isDomainMatch("localhost", "localhost"))
        assertTrue(DomainMatcher.isDomainMatch("http://localhost:5173/login", "localhost"))
        assertTrue(DomainMatcher.isDomainMatch("LOCALHOST.", "https://localhost/"))
        assertFalse(DomainMatcher.isDomainMatch("localhost", "app.localhost"))
        assertFalse(DomainMatcher.isDomainMatch("app.localhost", "localhost"))
        // 多标签本地名本来就按普通域名判定（PSL 默认规则）
        assertTrue(DomainMatcher.isDomainMatch("app.localhost", "http://app.localhost:5173/"))
        assertTrue(DomainMatcher.isDomainMatch("app.localhost", "www.app.localhost"))
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

    // ===== IP 字面量面（ISSUE-P3-234：DAL 出口 SSRF 覆盖层的可达性判据） =====

    /**
     * `ISSUE-P3-234`：`extractDomain` **不做 IP 字面量识别**（原样返回去方括号 / 端口后的主机），
     * 而 PSL 两个入口按 ENG-20261028-40 在查表前把 IP 形态判为非域名。
     *
     * - IPv4 / 点分数字形态 ⇒ 不是可注册域（`isRegistrableDomain` false、`registrableDomain` null），
     *   匹配层另行按「仅精确相等」放行（见 `域名匹配_IP 形态仅精确相等`）；
     * - IPv6 去方括号 / 端口后是**单标签**（或空串）⇒ 两入口同样拒绝。
     *
     * 该「IP 形态不参与域后缀」的结论是「`Dns` 层守卫（`SsrfGuardDns`）不构成 IP 字面量面
     * 完整覆盖」的判据之一：OkHttp 对 IP 字面量主机名不经 `Dns` 接口，故仅连接期层
     * （`SsrfGuardSocketFactory`）可达。
     */
    @Test
    fun `IP 字面量_非域名形态两入口一致拒绝`() {
        // 归一化不做 IP 识别（方括号 IPv6 与端口剥离后原样返回）
        assertEquals("127.0.0.1", DomainMatcher.extractDomain("https://127.0.0.1/path?q=1"))
        assertEquals("169.254.169.254", DomainMatcher.extractDomain("169.254.169.254"))
        assertEquals("::1", DomainMatcher.extractDomain("https://[::1]/x"))

        assertFalse(PublicSuffixList.isRegistrableDomain("127.0.0.1"))
        assertFalse(PublicSuffixList.isRegistrableDomain("169.254.169.254"))
        assertFalse(PublicSuffixList.isRegistrableDomain("168.1.1"))
        assertFalse(PublicSuffixList.isRegistrableDomain("::1"))
        assertEquals(null, PublicSuffixList.registrableDomain("127.0.0.1"))
        assertEquals(null, PublicSuffixList.registrableDomain("168.1.1"))
        assertEquals(null, PublicSuffixList.registrableDomain("::1"))
    }

    /**
     * `ISSUE-P3-234`：IPv4 字面量**可作为 `rp.id` 通过创建校验** ⇒ DAL 随后会请求
     * `https://<ip>/.well-known/assetlinks.json`。这是「SSRF 连接期层必须保留」的可执行依据
     * ——不得据「`rp.id` 大概不会是 IP」推断该面不可达而拆除 `SsrfGuardSocketFactory`。
     *
     * 该放行是**创建层的本地授权下限**，不代表 IP 是可注册域；`localhost` 同样只解除下限，
     * 归属仍由 DAL 对非 https 来源裁决。
     */
    @Test
    fun `IP 字面量与 localhost 可作 rp_id 而 IPv6 不可`() {
        val appOrigin = "android:apk-key-hash:AbCdEf123456"
        // 普通应用来源：可注册域 / localhost / IPv4 形态解除下限
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("127.0.0.1", appOrigin))
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("169.254.169.254", appOrigin))
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("localhost", appOrigin))
        // 浏览器来源：与 origin 主机名相等亦放行（IP / localhost 走精确相等档）
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("127.0.0.1", "https://127.0.0.1"))
        assertTrue(DomainMatcher.isRpIdTrustedForCreation("localhost", "https://localhost:8443"))
        // 浏览器来源：IP 仍不得做后缀扩展（`168.1.1` 不是 `192.168.1.1` 的父域）
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("168.1.1", "https://192.168.1.1"))
        // 对照：IPv6 字面量为单标签 ⇒ 两条分支一律拒绝
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("::1", appOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("[::1]", appOrigin))
        assertFalse(DomainMatcher.isRpIdTrustedForCreation("::1", "https://[::1]"))
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
