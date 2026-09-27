package com.keepasskey.app.passkey

import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仿冒 origin 不得拿到通行密钥候选（`ISSUE-P3-339` 的**代码层半环**）。
 *
 * ## 为什么先做这一层
 *
 * 浏览器半环要「两个不同可注册域 + 公开可信证书」才跑得起来（本机实测：Chrome 不认注入
 * `/system/etc/security/cacerts` 的自建 CA，而用 `thisisunsafe` 点掉安全提示后
 * Chrome 明确拒绝 WebAuthn——`NotAllowedError: WebAuthn is not supported on sites with
 * TLS certificate errors`，见该条目「开工前置读数」）。但**真正的越界面在本仓代码里**，
 * 不依赖浏览器也能证伪：候选筛选函数吃进 `(origin, 条目 rpId, 条目 URL)` 三元组，
 * 输出「出不出候选」——把仿冒形态做成表驱动用例即可判定。
 *
 * ## 域名口径（不得回退到 `.test`）
 *
 * 全部用 `.xyz`（随仓 PSL 在册）。选 `.test` 会让 `PublicSuffixList` 按「未知 TLD 不可注册」
 * fail-closed，于是**正向用例也拿不到候选**，负向读数就此失去意义。
 *
 * ## 断言方向
 *
 * 每条负向都配一条**同源正向对照**（同一 origin、rpId 合法 ⇒ 必须出候选），
 * 否则「什么都不出」也能让全表通过——那是重言断言的另一种形态。
 */
class CredentialProviderLookalikeMatchTest {

    private val service = KeePasskeyCredentialProviderService()

    private fun passkey(id: String, rpId: String, url: String = "") = UiVaultEntry(
        id = id,
        title = "pk-$id",
        username = "user-$id",
        url = url,
        isPasskey = true,
        passkeyRpId = rpId,
        category = EntryCategory.PASSKEY
    )

    private fun password(id: String, url: String) = UiVaultEntry(
        id = id, title = "pw-$id", username = "user-$id", url = url, category = EntryCategory.LOGIN
    )

    private fun offered(origin: String, vararg entries: UiVaultEntry): List<String> =
        service.findMatchingEntries(
            entries.toList(),
            origin = origin,
            packageName = "com.android.chrome",
            packageDimensionAuthorized = false          // 本表只问域维度，包维度另册
        ).map { it.id }

    @Test
    fun `一 真域与同可注册域的子域该出候选`() {
        val entries = arrayOf(
            passkey("rp-exact", "rp.testlab.xyz"),
            passkey("rp-sub", "rp.testlab.xyz")
        )
        assertEquals(listOf("rp-exact"), offered("https://rp.testlab.xyz", *entries).take(1))
        // 子域与真域同属一个可注册域 ⇒ 规范允许放行
        assertTrue(
            "sub.rp.testlab.xyz 应能唤醒 rpId=rp.testlab.xyz 的凭据",
            offered("https://sub.rp.testlab.xyz/login", *entries).contains("rp-exact")
        )
    }

    @Test
    fun `二 四类仿冒形态一律不出候选`() {
        val entries = arrayOf(passkey("victim", "rp.testlab.xyz"))
        for (origin in listOf(
            "https://rp.testlab.xyz.phish.testlab.xyz",     // 把真域堆在别域后缀里
            "https://phish.testlab.xyz",                    // 另一个可注册域
            "https://xn--r-5tb.testlab.xyz",                // 同形异码（西里尔 р）
            "https://rp.testlab.xyz.evil.xyz",
            "https://rp-testlab.xyz",
            "https://rptestlab.xyz",
        )
        ) {
            val hit = offered(origin, *entries)
            assertFalse("仿冒 origin $origin 拿到了凭据（越界）：$hit", hit.contains("victim"))
        }
        // 同源正向对照：证明上面那串不是「什么都不出」的假绿
        assertTrue(
            "正向对照失效：真域自己也拿不到候选，则上表全部断言无意义",
            offered("https://rp.testlab.xyz", *entries).contains("victim")
        )
    }

    @Test
    fun `三 IP_origin 不参与域匹配`() {
        val entries = arrayOf(passkey("ip-entry", "10.0.2.2"), passkey("dns-entry", "rp.testlab.xyz"))
        // 凭据本身以 IP 为 rpId 的条目是异常形态：不得被任何 DNS 名的 origin 捞出
        assertFalse(
            "DNS origin 捞出了 IP rpId 的条目",
            offered("https://rp.testlab.xyz", *entries).contains("ip-entry")
        )
        assertTrue(offered("https://rp.testlab.xyz", *entries).contains("dns-entry"))
    }

    /**
     * 兜底越界面：`findMatchingEntries` 的 `urlMatch` 分支若不区分「这条是不是 passkey」，
     * 一个 **rpId 属别域**、只是 URL 写了本站的 passkey 条目就会在本站被列出 ⇒
     * 跨域泄露凭据存在性（用户在假站看到真站账号名，或反之）。
     */
    @Test
    fun `四 passkey 条目不得经条目 URL 兜底被别的域捞出`() {
        val crossLinked = passkey("cross", "phish.testlab.xyz", url = "https://rp.testlab.xyz")
        val legitPassword = password("pw", "https://rp.testlab.xyz")

        val hit = offered("https://rp.testlab.xyz", crossLinked, legitPassword)
        assertFalse(
            "URL 兜底把 rpId 属 phish.testlab.xyz 的 passkey 条目捞进了本站候选" +
                "（签名侧另有 rpId 复核，故这不等于断言被交出，但**凭据存在性已跨域泄露**）",
            hit.contains("cross")
        )
        assertTrue("普通口令条目仍须按 URL 匹配（收紧不得过头）", hit.contains("pw"))
    }

    @Test
    fun `五 尾点与大小写不改判`() {
        val entries = arrayOf(passkey("case", "rp.testlab.xyz"))
        // 大小写不敏感：同一域必须同样出候选
        assertTrue(offered("https://RP.TestLab.XYZ", *entries).contains("case"))
        // 尾点（根点）形态：Chrome 归一后仍是同域 ⇒ 出候选；若判不成同域则说明归一漏了尾点
        assertTrue(
            "rp.testlab.xyz. 应与 rp.testlab.xyz 同域",
            offered("https://rp.testlab.xyz.", *entries).contains("case")
        )
    }
}
