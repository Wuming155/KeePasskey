package com.keepasskey.app.passkey

import java.net.IDN

/**
 * 轻量级 Public Suffix List（PSL）匹配器，替代原 47 条硬编码清单（批次 G / P1 安全整改）。
 *
 * 数据来源：`src/main/resources/publicsuffix/public_suffix_list.dat`（Mozilla 官方文件，
 * MPL-2.0 许可，许可声明保留于文件头）。惰性单例加载，线程安全。
 *
 * 算法对齐 publicsuffix.org 官方规范：
 * 1. 例外规则（`!` 前缀）优先命中，公共后缀 = 规则去掉最左标签（如 `!www.ck` → `ck`）；
 * 2. 否则取最长匹配规则为公共后缀；通配规则（`*.` 前缀）消耗一个任意标签（如 `*.ck` → `foo.ck`）；
 * 3. 无规则命中时套用默认规则 `*`（即最后一个标签本身为公共后缀）。
 *
 * 可注册域判定：`isRegistrableDomain(host)` = host 存在至少一个位于公共后缀之上的标签。
 * 涵盖私有段（PRIVATE DOMAINS，如 `github.io`），`github.io` 自身不可注册而 `foo.github.io` 可。
 *
 * IDN 处理：加载期将 Unicode 规则经 `java.net.IDN.toASCII` 归一为 punycode；查询期对非 ASCII
 * host 同样归一（minSdk 36 直接可用）。归一失败按不可注册处理（fail-closed，宁可拒绝不放行）。
 *
 * 数据加载失败同样 fail-closed：所有 host 一律判为不可注册（安全优先于可用性，
 * 出现即表明安装包损坏）。匹配器为纯 Kotlin / 零第三方依赖，保持纯 JVM 可测。
 */
object PublicSuffixList {

    private val lock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailed = false

    /** 精确规则（如 "edu.cn"、"github.io"） */
    @Volatile
    private var exactRules: Set<String> = emptySet()

    /** 通配规则 `*.suffix` 存储 suffix 部分（如 "*.ck" → "ck"） */
    @Volatile
    private var wildcardRules: Set<String> = emptySet()

    /** 例外规则 `!rule` 存储 rule 部分（如 "!www.ck" → "www.ck"） */
    @Volatile
    private var exceptionRules: Set<String> = emptySet()

    /**
     * 判断 host 是否为可注册域名（存在公共后缀之上的至少一个标签）。
     * host 需已归一化为主机名形式（小写、无 scheme、无端口）；尾部根点（"."）在此归一处理。
     */
    fun isRegistrableDomain(host: String): Boolean {
        if (!ensureLoaded()) return false
        val normalized = normalizeHost(host) ?: return false
        val labels = normalized.split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() }) return false
        val publicSuffix = findPublicSuffix(labels) ?: labels.last() // 默认规则 "*"
        return labels.size > publicSuffix.split('.').size
    }

    /** 按官方算法求公共后缀；无规则命中返回 null（调用方套用默认规则 "*"） */
    private fun findPublicSuffix(labels: List<String>): String? {
        // 例外规则优先：命中时公共后缀 = 规则去掉最左标签
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in exceptionRules) {
                return candidate.substringAfter('.')
            }
        }
        // 最长匹配（同为 host 后缀，标签数多即更长）
        var best: String? = null
        var bestLabelCount = 0
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            val parent = labels.subList(i + 1, labels.size).joinToString(".")
            val matched = candidate in exactRules || parent in wildcardRules
            if (matched && labels.size - i > bestLabelCount) {
                best = candidate
                bestLabelCount = labels.size - i
            }
        }
        return best
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(lock) {
            if (loaded) return true
            val exact = HashSet<String>()
            val wildcard = HashSet<String>()
            val exception = HashSet<String>()
            try {
                val stream = PublicSuffixList::class.java
                    .getResourceAsStream("/publicsuffix/public_suffix_list.dat")
                    ?: throw IllegalStateException("PSL 资源缺失")
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        val rule = raw.trim().lowercase()
                        when {
                            rule.isEmpty() || rule.startsWith("//") -> {}
                            rule.startsWith("!") -> exception.add(toPunycode(rule.substring(1)))
                            rule.startsWith("*.") -> wildcard.add(toPunycode(rule.substring(2)))
                            else -> exact.add(toPunycode(rule))
                        }
                    }
                }
                exactRules = exact
                wildcardRules = wildcard
                exceptionRules = exception
                loaded = true
                return true
            } catch (e: Exception) {
                loadFailed = true
                return false
            }
        }
    }

    /** 非 ASCII 规则归一为 punycode；失败保留原样（仅影响极个别规则，不阻断加载） */
    private fun toPunycode(rule: String): String {
        if (rule.none { it.code > 127 }) return rule
        return try {
            IDN.toASCII(rule)
        } catch (e: Exception) {
            rule
        }
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
}
