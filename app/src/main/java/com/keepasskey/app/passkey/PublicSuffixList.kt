package com.keepasskey.app.passkey

import java.net.IDN

/**
 * 轻量级 Public Suffix List（PSL）匹配器，替代原 47 条硬编码清单（批次 G / P1 安全整改）。
 *
 * 数据来源：`src/main/resources/publicsuffix/public_suffix_list.dat`（Mozilla 官方文件，
 * MPL-2.0 许可，许可声明保留于文件头）。线程安全。
 *
 * 算法对齐 publicsuffix.org 官方规范：
 * 1. 例外规则（`!` 前缀）优先命中，公共后缀 = 规则去掉最左标签（如 `!www.ck` → `ck`）；
 * 2. 否则取最长匹配规则为公共后缀；通配规则（`*.` 前缀）消耗一个任意标签（如 `*.ck` → `foo.ck`）；
 * 3. 无规则命中时套用默认规则 `*`（即最后一个标签本身为公共后缀）。
 *
 * 可注册域判定：`isRegistrableDomain(host)` = host 存在至少一个位于公共后缀之上的标签。
 * 涵盖私有段（PRIVATE DOMAINS，如 `github.io`），`github.io` 自身不可注册而 `foo.github.io` 可。
 *
 * IDN 处理：查询期将非 ASCII host 经 [IDN.toASCII] 归一为 punycode；**包含非 ASCII 标签的规则**
 * 在装入其所属「末标签桶」时同样归一（如 `公司.cn` → `xn--55qx5d.cn`）。
 * 归一失败按不可注册处理（fail-closed，宁可拒绝不放行）。
 *
 * ### 装载策略：按「末标签桶」**懒加载**（ISSUE-P1-238，2026-09-21 真机实测后整改）
 *
 * 本对象是凭据提供者 `rp.id` 归属校验的**同步依赖**，而该调用处在系统约 **3.0 s** 的应答
 * 预算内。原实现每次冷启动都全量解析 PSL（16,475 行 / 1.0 万条规则），真机实测 **1.0 s**
 * （Redmi 4X，debug 构建解释执行），是超时的主要应用侧来源。
 *
 * 关键观察：`findPublicSuffix` 中**所有**候选串（`labels[i..]` 与 `labels[i+1..]`）都以 host 的
 * 最后一个标签结尾 ⇒ 规则命中只可能发生在「末标签相同」的规则之间。故只需目标末标签的规则桶，
 * 无需全量解析；且**任何规则的超集**都不会改变判定结果（末标签不同者无法与候选串相等）。
 *
 * 桶的构建方式：在源文本上用 `String.indexOf` 定位「以 `.<tld>` 结尾的行」与「整行即 `<tld>` 的行」
 * （两者都是框架方法，运行于 boot image 的已编译代码，而非解释执行的自写循环——这一点是实测
 * 结论：自写逐字节循环反而更慢，详见批次正文的实测表）。单个桶通常只有几条规则，
 * 最重的 `jp` / `com` 桶约 1.1 ~ 2.0 千条，仍远低于全量解析。
 *
 * 例外：末标签自身为 punycode（`xn--…`）时，源文件里的对应规则以 Unicode 形式书写，
 * 无法用文本定位 ⇒ 退化为**一次性全量解析**并作为「全集」缓存（对任意末标签都是合法超集）。
 * 该分支只在 IDN 站点上出现。
 *
 * ### 依赖的 PSL 源文件前提（由 `PublicSuffixListResourceTest` 机检锁定）
 *
 * 1. 行分隔符为纯 `LF`（`CR` 仅在行末做防御性裁剪）；
 * 2. 规则行**已是小写** ⇒ 省掉每行一次 `lowercase()`；
 * 3. 规则行首尾无空白 ⇒ 省掉每行 `trim()`。
 *
 * 三条被破坏时该用例即红，**不会静默改变判定口径**（人工更新 `.dat` 时须同步复核）。
 */
object PublicSuffixList {

    /**
     * 单个「末标签桶」的规则集合。
     *
     * 三个集合的成员**都已去掉 `!` / `*.` 前缀**（与原实现的 `exactRules` / `wildcardRules` /
     * `exceptionRules` 同形），因此可被 [findPublicSuffix] 直接消费。
     */
    private class TldRules(
        val exact: Set<String>,
        val wildcard: Set<String>,
        val exception: Set<String>
    )

    /** 单行解析结果：类别（`RULE_KIND_*` 之一）+ 已归一化的规则值（`!` / `*.` 前缀已剥离） */
    private class ParsedRule(val kind: Int, val value: String)

    private val lock = Any()

    /** PSL 源文件全文（首载后保留，供按需构建其它末标签桶；约 334 K 字符） */
    @Volatile
    private var sourceText: String? = null

    /** 源文件缺失 / 读取失败（fail-closed 标记：此后一切 host 判为不可注册） */
    @Volatile
    private var sourceUnavailable = false

    /**
     * 已构建的末标签桶。
     *
     * 用 `ConcurrentHashMap`：读路径（[rulesFor] 的无锁快查）可能与其它线程的写入并发——
     * 桶对象总是**先完整构建再发布**，故读侧只会看到「无」或「完整」两种状态；
     * 普通 `HashMap` 的并发读写则无此保证。
     */
    private val tldRules = java.util.concurrent.ConcurrentHashMap<String, TldRules>()

    /**
     * 判断 host 是否为可注册域名（存在公共后缀之上的至少一个标签）。
     * host 需已归一化为主机名形式（小写、无 scheme、无端口）；尾部根点（"."）在此归一处理。
     *
     * 读取失败（资源缺失）时返回 `false`（fail-closed）。
     */
    fun isRegistrableDomain(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        val labels = normalized.split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() }) return false
        val rules = rulesFor(labels[labels.size - 1]) ?: return false
        val publicSuffix = findPublicSuffix(rules, labels) ?: labels[labels.size - 1] // 默认规则 "*"
        return labels.size > publicSuffix.split('.').size
    }

    /**
     * 取某末标签对应的规则桶（必要时构建；按末标签缓存）。
     *
     * 返回 `null` 仅表示**资源不可用**（fail-closed），不表示「无规则」——无规则时返回空桶，
     * 由 [findPublicSuffix] 套用默认规则 `*`。
     *
     * 两条构建路径（**都只产出该末标签的桶**，不缓存任何全局状态）：
     * - ASCII 末标签：用文本定位规则行（[buildTldRules]），只解析命中的那几行；
     * - punycode 末标签（`xn--…`）：源文件里对应规则以 Unicode 书写，无法文本定位 ⇒ 全量扫描
     *   （[buildTldRulesByNormalizedLabel]），仅在该类站点上付一次代价并缓存到桶。
     */
    private fun rulesFor(tld: String): TldRules? {
        if (sourceUnavailable) return null
        tldRules[tld]?.let { return it }
        synchronized(lock) {
            if (sourceUnavailable) return null
            tldRules[tld]?.let { return it }
            val text = loadSourceText() ?: return null
            val rules = if (tld.startsWith(PUNYCODE_LABEL_PREFIX)) {
                buildTldRulesByNormalizedLabel(text, tld)
            } else {
                buildTldRules(text, tld)
            }
            tldRules[tld] = rules
            return rules
        }
    }

    /** 载入源文件全文；失败置 [sourceUnavailable] 并返回 null（仅在 [lock] 内调用） */
    private fun loadSourceText(): String? {
        sourceText?.let { return it }
        return try {
            val text = PublicSuffixList::class.java
                .getResourceAsStream(PSL_RESOURCE_PATH)
                ?.use { it.readBytes() }
                ?.let { String(it, Charsets.UTF_8) }
                ?: throw IllegalStateException("PSL 资源缺失")
            sourceText = text
            text
        } catch (e: Exception) {
            sourceUnavailable = true
            null
        }
    }

    /**
     * 构建单个末标签桶：只解析「末标签 == [tld]」的规则行。
     *
     * 用**单次** `indexOf("$tld\n")` 扫描定位全部候选位置，再按命中位置的前一个字符分类——
     * 这样只需扫一遍源文本（原先用两个针各扫一遍）：
     * - 前一字符为 `.` ⇒ 形如 `….tld` 的行：多标签规则（`co.uk`）、通配规则（`*.ck`）、
     *   例外规则（`!www.ck`）；
     * - 前一字符为 `LF` ⇒ 整行即 `tld` 的单标签规则（`com`）；
     * - 其余（如 `notcom` 命中 `com\n`）⇒ 不是规则行，跳过。
     *
     * 注释行可能被命中（部分注释以 `.tld` 结尾），由 [addRuleLine] 自行跳过，不影响结果。
     */
    private fun buildTldRules(text: String, tld: String): TldRules {
        val exact = HashSet<String>()
        val wildcard = HashSet<String>()
        val exception = HashSet<String>()
        val needle = "$tld\n"
        var from = 0
        while (true) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) break
            if (hit == 0) {
                // 防御：文件首行即单标签规则（现行资源以注释行起首，正常不会走到）
                if (text.startsWith(needle)) addRuleLine(text, 0, tld.length, exact, wildcard, exception)
                from = 1
                continue
            }
            val previous = text[hit - 1]
            if (previous == DOT_CHAR) {
                val lineStart = text.lastIndexOf(LINE_FEED, hit) + 1
                addRuleLine(text, lineStart, hit + tld.length, exact, wildcard, exception)
            } else if (previous == LINE_FEED) {
                addRuleLine(text, hit, hit + tld.length, exact, wildcard, exception)
            }
            from = hit + 1
        }
        return TldRules(exact, wildcard, exception)
    }

    /**
     * 构建 punycode 末标签的桶：源文件里这类规则以 Unicode 书写（如 `公司.cn`），
     * 无法用文本定位，只能**全量逐行**解析后按「归一化后的末标签」筛选。
     *
     * 代价即一次全量解析（真机实测约 0.7 s），但仅在该类站点上发生，且按末标签缓存到
     * [tldRules]；普通 ASCII 站点永不触发（见 [rulesFor]）。
     */
    private fun buildTldRulesByNormalizedLabel(text: String, tld: String): TldRules {
        val exact = HashSet<String>()
        val wildcard = HashSet<String>()
        val exception = HashSet<String>()
        val size = text.length
        var lineStart = 0
        while (lineStart <= size) {
            val lineFeed = text.indexOf(LINE_FEED, lineStart)
            val lineEnd = if (lineFeed < 0) size else lineFeed
            val parsed = parseRuleLine(text, lineStart, lineEnd)
            if (parsed != null && parsed.value.substringAfterLast(DOT_CHAR) == tld) {
                putParsed(parsed, exact, wildcard, exception)
            }
            if (lineFeed < 0) break
            lineStart = lineFeed + 1
        }
        return TldRules(exact, wildcard, exception)
    }

    /** 按官方算法求公共后缀；无规则命中返回 null（调用方套用默认规则 "*"） */
    private fun findPublicSuffix(rules: TldRules, labels: List<String>): String? {
        // 例外规则优先：命中时公共后缀 = 规则去掉最左标签
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in rules.exception) {
                return candidate.substringAfter('.')
            }
        }
        // 最长匹配（同为 host 后缀，标签数多即更长）
        var best: String? = null
        var bestLabelCount = 0
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            val parent = labels.subList(i + 1, labels.size).joinToString(".")
            val matched = candidate in rules.exact || parent in rules.wildcard
            if (matched && labels.size - i > bestLabelCount) {
                best = candidate
                bestLabelCount = labels.size - i
            }
        }
        return best
    }

    /**
     * 解析单行（`[from, to)` 为不含换行符的行区间）。
     *
     * 语义与「`trim` → `lowercase` → `startsWith` 分流」逐字一致（前提：资源行已小写、
     * 首尾无空白，见类 KDoc），返回值已去掉 `!` / `*.` 前缀并按需归一为 punycode；
     * 空白行、`//` 注释行、只剩前缀的残缺行一律返回 null（调用方跳过）。
     */
    private fun parseRuleLine(text: String, from: Int, to: Int): ParsedRule? {
        if (from >= to) return null
        // 防御性 CR 裁剪（现行资源为纯 LF；见类 KDoc 前提 1）
        val end = if (text[to - 1] == CARRIAGE_RETURN) to - 1 else to
        if (from >= end) return null
        if (text.startsWith(COMMENT_PREFIX, from)) return null

        var start = from
        var kind = RULE_KIND_EXACT
        when (text[start]) {
            BANG -> {
                kind = RULE_KIND_EXCEPTION
                start++
            }
            ASTERISK -> if (text.startsWith(WILDCARD_PREFIX, start)) {
                kind = RULE_KIND_WILDCARD
                start += WILDCARD_PREFIX.length
            }
        }
        if (start >= end) return null

        val rule = text.substring(start, end)
        val value = if (NON_ASCII_PATTERN.matcher(rule).find()) toPunycode(rule) else rule
        return ParsedRule(kind, value)
    }

    /** 解析单行并写入对应集合（文本定位路径专用；末标签必然等于目标 tld） */
    private fun addRuleLine(
        text: String,
        from: Int,
        to: Int,
        exact: MutableSet<String>,
        wildcard: MutableSet<String>,
        exception: MutableSet<String>
    ) {
        val parsed = parseRuleLine(text, from, to) ?: return
        putParsed(parsed, exact, wildcard, exception)
    }

    /** 按规则类别写入对应集合 */
    private fun putParsed(
        parsed: ParsedRule,
        exact: MutableSet<String>,
        wildcard: MutableSet<String>,
        exception: MutableSet<String>
    ) {
        when (parsed.kind) {
            RULE_KIND_EXCEPTION -> exception.add(parsed.value)
            RULE_KIND_WILDCARD -> wildcard.add(parsed.value)
            else -> exact.add(parsed.value)
        }
    }

    /**
     * 非 ASCII 规则归一为 punycode；失败保留原样（仅影响极个别规则，不阻断装载）。
     *
     * 调用方已用 [NON_ASCII_PATTERN] 判定「确含非 ASCII」，故此处不再做一次
     * `none { it.code > 127 }`（那是逐字符的解释执行遍历，正是本类要消除的开销）。
     */
    private fun toPunycode(rule: String): String = try {
        IDN.toASCII(rule)
    } catch (e: Exception) {
        rule
    }

    /** host 归一：小写、去尾部根点、非 ASCII 转 punycode；非法输入返回 null（fail-closed） */
    private fun normalizeHost(host: String): String? {
        var s = host.trim().lowercase()
        if (s.endsWith('.')) s = s.dropLast(1)
        if (s.isEmpty()) return null
        if (s.any { it.code > 127 }) {
            s = try {
                IDN.toASCII(s)
            } catch (e: Exception) {
                return null
            }
        }
        return s
    }

    /**
     * 规则行分类（[addRuleLine] 的内部标记；不对外暴露，避免用裸 `Int` 传语义）。
     *
     * 之所以用 `Int` 常量而非 `enum`：本函数在 provider 3 s 应答预算内运行，`enum` 的
     * `values()` / 拆箱在解释执行下反而是额外开销，而分类值**仅在文件内局部使用**。
     */
    private const val RULE_KIND_EXACT = 0
    private const val RULE_KIND_WILDCARD = 1
    private const val RULE_KIND_EXCEPTION = 2

    private const val PSL_RESOURCE_PATH = "/publicsuffix/public_suffix_list.dat"

    /** punycode 标签前缀（RFC 3492）：末标签为它时无法用文本定位规则，退化为全量解析 */
    private const val PUNYCODE_LABEL_PREFIX = "xn--"

    /** 行分隔符与注释前缀（现行资源为纯 LF + `//` 注释，见类 KDoc 前提） */
    private const val LINE_FEED = '\n'
    private const val CARRIAGE_RETURN = '\r'
    private const val COMMENT_PREFIX = "//"
    private const val DOT_CHAR = '.'

    /** 通配规则前缀（`*.`），与 [addRuleLine] 的去前缀逻辑同锚 */
    private const val WILDCARD_PREFIX = "*."
    private const val BANG = '!'
    private const val ASTERISK = '*'

    /**
     * 非 ASCII 判定（框架正则，boot image 编译态执行）。
     *
     * 不用 `rule.none { it.code > 127 }`：那是 Kotlin 内联的**逐字符解释执行**循环，
     * 在全量解析路径上会放大成十余万次解释执行（实测约 0.1 s 量级）。
     */
    private val NON_ASCII_PATTERN = java.util.regex.Pattern.compile("[^\\x00-\\x7F]")
}
