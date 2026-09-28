package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.core.model.KdbxEntry

/**
 * 自动填充「手动选择器」的检索策略（ISSUE-P3-40，纯函数，JVM 可测）。
 *
 * 只在**非敏感元数据**（标题 / 用户名 / 网址）上做大小写不敏感的子串匹配，
 * 绝不触碰密码明文——列表渲染与搜索热路径不解密任何受保护字段（零秘密热路径）。
 *
 * ISSUE-P3-372 AC⑤：查询串形如域名时，在子串命中之外**叠加**一条域名感知降级
 * （加性 OR，对齐 Kp2a `SearchForHost` 五级降级的收敛子集）：
 * 1. 主机名等值（`github.com` ↔ `https://github.com`）；
 * 2. 子域后缀双向（查询为条目子域 或 条目为查询子域，严格点号标签边界）；
 * 3. 去 `www.` 归一后等值。
 * 只增不减：既有子串命中行为一字不改；`android://` 条目不参与本降级
 * （包名不是域名，避免 `com.x.y` 形态误入域名档）。
 */
object AutofillEntrySearch {

    /** 展示上限（避免超大库一次性投影过多条目） */
    const val DEFAULT_LIMIT = 50

    /** `android://` 绑定条目的 scheme 前缀（域名降级对其不适用） */
    private const val ANDROID_SCHEME_PREFIX = "android://"

    /** `www.` 主机前缀（去归一用） */
    private const val WWW_PREFIX = "www."

    fun filter(
        entries: List<KdbxEntry>,
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): List<KdbxEntry> {
        if (entries.isEmpty()) return emptyList()
        val q = query.trim().lowercase()
        val matched = if (q.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.title.lowercase().contains(q) ||
                        entry.userName.lowercase().contains(q) ||
                        entry.url.lowercase().contains(q) ||
                        hostLadderMatches(entry.url, q)
            }
        }
        return matched.take(limit.coerceAtLeast(1))
    }

    /**
     * ISSUE-P3-372 AC⑤：域名感知三档降级（纯函数）。
     *
     * @param entryUrl 条目 url 字段原值
     * @param query 已 trim + 小写的查询串
     */
    internal fun hostLadderMatches(entryUrl: String, query: String): Boolean {
        if (query.contains(' ')) return false
        val queryHost = DomainMatcher.extractDomain(query)
        // 查询须形如域名（含点号的多标签主机），单词查询不进域名档
        if (!queryHost.contains('.')) return false
        if (entryUrl.startsWith(ANDROID_SCHEME_PREFIX, ignoreCase = true)) return false
        val entryHost = DomainMatcher.extractDomain(entryUrl)
        if (entryHost.isEmpty() || !entryHost.contains('.')) return false

        if (entryHost == queryHost) return true
        // 子域后缀双向：任一方是另一方的下级主机（严格点号标签边界）
        if (entryHost.endsWith(".$queryHost")) return true
        if (queryHost.endsWith(".$entryHost")) return true
        // 去 www 归一后等值（任一侧带 www）
        val entryStripped = entryHost.removePrefix(WWW_PREFIX)
        val queryStripped = queryHost.removePrefix(WWW_PREFIX)
        if (entryStripped != entryHost && entryStripped == queryHost) return true
        if (queryStripped != queryHost && queryStripped == entryHost) return true
        if (entryStripped != entryHost && queryStripped != queryHost &&
            entryStripped == queryStripped
        ) {
            return true
        }
        return false
    }
}
