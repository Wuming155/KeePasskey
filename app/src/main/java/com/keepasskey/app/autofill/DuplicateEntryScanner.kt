package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry

/**
 * ISSUE-P3-382：库内重复条目只读检测。
 *
 * 裁决（批次留痕）：先做只读重复报告；合并动作须用户逐条确认、走既有编辑/删除管线，
 * 不与同步 KdbxMerger 混用。默认判据「同 URL + 同账号」。
 *
 * 负例口径：URL 为空且账号为空的条目不参与；不同 UUID 但 URL/账号均不同不算重复。
 */
object DuplicateEntryScanner {

    enum class DuplicateRule {
        SAME_URL_AND_USERNAME,
        SAME_TITLE_AND_URL
    }

    data class DuplicateGroup(
        val key: String,
        val rule: DuplicateRule,
        val entries: List<KdbxEntry>
    )

    fun scan(
        entries: List<KdbxEntry>,
        rules: Set<DuplicateRule> = setOf(DuplicateRule.SAME_URL_AND_USERNAME)
    ): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        val seen = mutableSetOf<String>()
        for (rule in rules) {
            val buckets = LinkedHashMap<String, MutableList<KdbxEntry>>()
            for (entry in entries) {
                val key = keyOf(entry, rule) ?: continue
                buckets.getOrPut(key) { mutableListOf() }.add(entry)
            }
            for ((key, group) in buckets) {
                val unique = group.distinctBy { it.id }
                if (unique.size < 2) continue
                val bucketKey = rule.name + ":" + key
                if (!seen.add(bucketKey)) continue
                groups += DuplicateGroup(key = key, rule = rule, entries = unique)
            }
        }
        return groups
    }

    internal fun keyOf(entry: KdbxEntry, rule: DuplicateRule): String? {
        return when (rule) {
            DuplicateRule.SAME_URL_AND_USERNAME -> {
                val url = normalizeUrl(entry.url)
                val user = entry.fields[KdbxConstants.Fields.USER_NAME]?.readString()?.trim().orEmpty()
                if (url.isEmpty() && user.isEmpty()) null else url + "|" + user
            }

            DuplicateRule.SAME_TITLE_AND_URL -> {
                val title = entry.title.trim()
                val url = normalizeUrl(entry.url)
                if (title.isEmpty() || url.isEmpty()) null else title + "|" + url
            }
        }
    }

    fun normalizeUrl(raw: String): String {
        var v = raw.trim().lowercase()
        if (v.isEmpty()) return ""
        v = v.removePrefix("https://").removePrefix("http://")
        v = v.removePrefix("www.")
        v = v.trimEnd('/')
        return v
    }
}
