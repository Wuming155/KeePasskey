package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxEntry

/**
 * 自动填充「手动选择器」的检索策略（ISSUE-P3-40，纯函数，JVM 可测）。
 *
 * 只在**非敏感元数据**（标题 / 用户名 / 网址）上做大小写不敏感的子串匹配，
 * 绝不触碰密码明文——列表渲染与搜索热路径不解密任何受保护字段（零秘密热路径）。
 */
object AutofillEntrySearch {

    /** 展示上限（避免超大库一次性投影过多条目） */
    const val DEFAULT_LIMIT = 50

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
                        entry.url.lowercase().contains(q)
            }
        }
        return matched.take(limit.coerceAtLeast(1))
    }
}
