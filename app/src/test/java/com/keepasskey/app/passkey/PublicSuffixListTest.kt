package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.IDN

/**
 * `PublicSuffixList` 语义与装载策略的单元测试。
 *
 * 覆盖三块：
 * 1. **判定语义**：公共后缀 / 通配 / 例外 / 私有段 / 单标签 / IP 字面量等边界；
 * 2. **按末标签懒加载的等价性**（`ISSUE-P1-238` 整改引入）：以本文件内**独立实现**的
 *    [ReferencePsl]（逐行 `trim` + `lowercase` + 全量集合）为对照，对 PSL 全量规则穷举比对——
 *    懒加载只是「少装规则」，任何漏装都会在这里现形；
 * 3. **IDN 末标签路径**：源文件里该类规则以 Unicode 书写，生产实现无法用文本定位，
 *    走的是另一条构建路径（全量扫描 + 按归一化末标签筛选），故单独覆盖。
 *
 * **用例顺序无关性**：生产实现**不缓存任何全局状态**（每个末标签各有一份规则桶），
 * 因此本用例的断言不依赖执行顺序，也不存在「先跑谁会让后跑的后端路径被替换」的问题。
 */
class PublicSuffixListTest {

    private val sourceText: String = checkNotNull(
        PublicSuffixListTest::class.java.getResourceAsStream(PSL_RESOURCE_PATH)
    ) { "PSL 资源不在测试类路径上（src/main/resources 未参与单测运行时）" }
        .use { it.readBytes() }
        .toString(Charsets.UTF_8)

    private val reference = ReferencePsl(sourceText)

    // ===== 1. 判定语义 =====

    @Test
    fun `判定_可注册域与公共后缀`() {
        assertTrue(PublicSuffixList.isRegistrableDomain("example.com"))
        assertTrue(PublicSuffixList.isRegistrableDomain("login.github.com"))
        // 公共后缀本身不是可注册域（F5 整改的判据）
        assertFalse(PublicSuffixList.isRegistrableDomain("com"))
        assertFalse(PublicSuffixList.isRegistrableDomain("co.uk"))
        assertTrue(PublicSuffixList.isRegistrableDomain("bbc.co.uk"))
        assertTrue(PublicSuffixList.isRegistrableDomain("example.com.cn"))
    }

    @Test
    fun `判定_私有段`() {
        // 私有段与 ICANN 段同等生效：github.io 自身不可注册，其子域可
        assertFalse(PublicSuffixList.isRegistrableDomain("github.io"))
        assertTrue(PublicSuffixList.isRegistrableDomain("foo.github.io"))
    }

    @Test
    fun `判定_通配与例外规则`() {
        // 现行 PSL：`*.ck` 为通配规则、`!www.ck` 为例外规则
        // 通配 ⇒ 单标签下的名字本身即公共后缀（不可注册）
        assertFalse(PublicSuffixList.isRegistrableDomain("foo.ck"))
        assertTrue(PublicSuffixList.isRegistrableDomain("bar.foo.ck"))
        assertFalse(PublicSuffixList.isRegistrableDomain("other.ck"))
        // 例外 ⇒ `www.ck` 不是公共后缀（公共后缀是 `ck`），故它是可注册域
        assertTrue(PublicSuffixList.isRegistrableDomain("www.ck"))
    }

    @Test
    fun `判定_单标签_空值_IP 字面量`() {
        assertFalse(PublicSuffixList.isRegistrableDomain(""))
        assertFalse(PublicSuffixList.isRegistrableDomain("localhost"))
        assertFalse(PublicSuffixList.isRegistrableDomain("::1"))
        assertFalse(PublicSuffixList.isRegistrableDomain("a..com"))
        // 尾部根点归一后按普通主机处理
        assertTrue(PublicSuffixList.isRegistrableDomain("example.com."))
        // IP 字面量的「末标签」不会是 PSL 规则 ⇒ 多标签一律放行（与既有行为一致）
        assertTrue(PublicSuffixList.isRegistrableDomain("127.0.0.1"))
        assertTrue(PublicSuffixList.isRegistrableDomain("169.254.169.254"))
    }

    @Test
    fun `判定_大小写与空白归一`() {
        assertTrue(PublicSuffixList.isRegistrableDomain("EXAMPLE.COM"))
        assertTrue(PublicSuffixList.isRegistrableDomain("  example.com  "))
    }

    // ===== 2. 按末标签懒加载的等价性（全量穷举） =====

    /**
     * 对 PSL 中**末标签为 ASCII** 的每一条规则 [R]，逐一比对
     * `isRegistrableDomain` 与 [ReferencePsl] 在 `R`、`example.R`、`a.b.R` 三个 host 上的结论。
     *
     * 覆盖约 1 万个不同的末标签与全部规则形态（精确 / 通配 / 例外 / 私有段 / 含非 ASCII 前缀
     * 的规则，如 `公司.cn`）。punycode 末标签另由 [IDN 末标签_与引用实现一致] 覆盖。
     */
    @Test
    fun `按末标签懒加载_与引用实现逐规则一致`() {
        val rules = sourceText.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map { it.removePrefix("!").removePrefix("*.") }
            .filter { it.isNotEmpty() && it.substringAfterLast('.').all { ch -> ch.code < 128 } }
            .distinct()

        var checked = 0
        for (rule in rules) {
            for (host in listOf(rule, "example.$rule", "a.b.$rule")) {
                assertEquals(
                    "host=$host 与引用实现结论不一致",
                    reference.isRegistrableDomain(host),
                    PublicSuffixList.isRegistrableDomain(host)
                )
                checked++
            }
        }
        assertTrue(
            "穷举覆盖的 host 组合过少（实际 $checked），PSL 资源可能未正确装入",
            checked > 20_000
        )
    }

    /** 多标签组合的主机名（规则拼接之外的常见形态），同样与引用实现比对 */
    @Test
    fun `按末标签懒加载_常见主机名组合一致`() {
        val hosts = listOf(
            "www.example.com", "a.b.c.example.com", "s3.amazonaws.com",
            "news.bbc.co.uk", "www.bbc.co.uk", "shop.example.co.jp",
            "example.s3.dualstack.ap-northeast-1.amazonaws.com",
            "user.github.io", "a.user.github.io", "foo.bar.ck", "ck",
            "com", "uk", "co.jp", "amazonaws.com", "ap-northeast-1.amazonaws.com"
        )
        for (host in hosts) {
            assertEquals(
                "host=$host 与引用实现结论不一致",
                reference.isRegistrableDomain(host),
                PublicSuffixList.isRegistrableDomain(host)
            )
        }
    }

    // ===== 3. IDN 末标签路径 =====

    /**
     * 源文件里末标签非 ASCII 的规则只能经**全量扫描 + 按归一化末标签筛选**装入，
     * 故单独覆盖：Unicode 与 punycode 两种书写都必须与引用实现一致。
     */
    @Test
    fun `IDN 末标签_与引用实现一致`() {
        val hosts = listOf(
            // рф（xn--p1ai）
            "рф", "xn--p1ai", "example.рф", "example.xn--p1ai",
            // 中国（xn--fiqs8s）
            "中国", "xn--fiqs8s", "example.中国", "example.xn--fiqs8s",
            // 公司.cn（非 ASCII 前缀 + ASCII 末标签，走的是 ASCII 桶路径）
            "公司.cn", "xn--55qx5d.cn", "example.公司.cn", "example.xn--55qx5d.cn",
            "网络.cn", "xn--dqru22d.cn"
        )
        for (host in hosts) {
            assertEquals(
                "host=$host 与引用实现结论不一致",
                reference.isRegistrableDomain(host),
                PublicSuffixList.isRegistrableDomain(host)
            )
        }
        // 公开口径的定点断言（防止「两侧同时错」的假绿）
        assertFalse(PublicSuffixList.isRegistrableDomain("xn--p1ai"))
        assertTrue(PublicSuffixList.isRegistrableDomain("example.xn--p1ai"))
        assertFalse(PublicSuffixList.isRegistrableDomain("xn--55qx5d.cn"))
        assertTrue(PublicSuffixList.isRegistrableDomain("example.xn--55qx5d.cn"))
    }

    private companion object {
        const val PSL_RESOURCE_PATH = "/publicsuffix/public_suffix_list.dat"
    }
}

/**
 * PSL 官方算法的**独立引用实现**（逐行 `trim` + `lowercase` + 全量集合 + 两次遍历求最长后缀）。
 *
 * 刻意**不复用生产代码**：它要能独立地指出生产实现的漏装 / 误判。与生产实现的差异只允许存在于
 * 「装载策略」（全量 vs 按末标签懒加载），判定口径必须逐字一致。
 */
private class ReferencePsl(text: String) {

    private val exact = HashSet<String>()
    private val wildcard = HashSet<String>()
    private val exception = HashSet<String>()

    init {
        for (raw in text.split('\n')) {
            val rule = raw.trim().lowercase()
            when {
                rule.isEmpty() || rule.startsWith("//") -> {}
                rule.startsWith("!") -> exception.add(punycode(rule.substring(1)))
                rule.startsWith("*.") -> wildcard.add(punycode(rule.substring(2)))
                else -> exact.add(punycode(rule))
            }
        }
    }

    private fun punycode(rule: String): String =
        if (rule.any { it.code > 127 }) try {
            IDN.toASCII(rule)
        } catch (e: Exception) {
            // 与生产实现同口径：现行 PSL 存在 Java IDN 判为「未分配码点」的规则
            // （如巴厘文标签），归一失败保留原样（该规则此后无法与 punycode 候选命中，
            // 与整改前行为一致）
            rule
        } else rule

    fun isRegistrableDomain(host: String): Boolean {
        var s = host.trim().lowercase()
        if (s.endsWith('.')) s = s.dropLast(1)
        if (s.isEmpty()) return false
        if (s.any { it.code > 127 }) {
            // 与生产实现同口径：归一失败（如未分配码点）按不可注册处理
            s = try {
                IDN.toASCII(s)
            } catch (e: Exception) {
                return false
            }
        }
        val labels = s.split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() }) return false

        var best: String? = null
        var bestLabelCount = 0
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in exception) {
                val suffix = candidate.substringAfter('.')
                return labels.size > suffix.split('.').size
            }
            val parent = labels.subList(i + 1, labels.size).joinToString(".")
            if ((candidate in exact || parent in wildcard) && labels.size - i > bestLabelCount) {
                best = candidate
                bestLabelCount = labels.size - i
            }
        }
        val suffix = best ?: labels.last()
        return labels.size > suffix.split('.').size
    }
}
