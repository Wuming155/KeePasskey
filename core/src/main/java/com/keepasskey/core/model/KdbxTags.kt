package com.keepasskey.core.model

/**
 * KDBX 标签（Tags）的分隔符词汇表与归一化（`ISSUE-P2-282` AC①／AC②）。
 *
 * **单一常量表**：读侧（XML 解析）、写侧（XML 序列化）、编辑页输入解析一律走本对象，
 * 禁止再各写一份分隔符集合（整改前读侧只认 `;`、写侧用 `"; "`、编辑页只认 `,` / `，` / 空格，
 * 三套口径互不相同 ⇒ 逗号库读成 1 个标签、回写后对方又读成多个，双向漂移）。
 *
 * 口径逐字对齐官方 `StrUtil.cs`（KeePass-2.61.1）：
 * - `g_vTagSep = { ',', ';' }`（`:1530`）——读 / 输入切分集合；
 * - `NormalizeTag`（`:1531-1541`）——trim 后把分隔符**替换为 `.`**（不是剔除整项）；
 * - `NormalizeTags`（`:1543-1578`）——归一化后去空项、去重、自然排序；
 * - `TagsToString`（`:1589-1623`）——存储形态以**裸 `;`** 连接（`, ` 仅为显示形态）。
 */
object KdbxTags {

    /** 标签切分集合（读 / 输入共用，对齐官方 `g_vTagSep`）。 */
    val SEPARATORS: CharArray = charArrayOf(',', ';')

    /** 写侧分隔符（官方存储形态为裸 `;`，非 `"; "`）。 */
    const val WRITE_SEPARATOR: String = ";"

    /** 官方 `NormalizeTag`：trim，并把标签内残留的分隔符替换为 `.`（不是剔除整项）。 */
    fun normalizeTag(tag: String): String {
        var normalized = tag.trim()
        for (separator in SEPARATORS) {
            normalized = normalized.replace(separator, '.')
        }
        return normalized
    }

    /**
     * 官方 `NormalizeTags`：逐项 [normalizeTag]、去空项、去重、自然排序。
     * 排序实现为数字段感知的自然序（`CompareNaturally` 同语义：连续数字按数值比较）。
     */
    fun normalizeTags(tags: List<String>): List<String> =
        tags.map(::normalizeTag)
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(::compareNaturally)

    /** 读 / 输入解析：按 [SEPARATORS] 切分后归一化（官方 `StringToTags` 同语义）。 */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return normalizeTags(raw.split(*SEPARATORS))
    }

    /** 写侧序列化：归一化后以裸 `;` 连接（官方 `TagsToString(bForDisplay = false)` 同语义）。 */
    fun serialize(tags: List<String>): String =
        normalizeTags(tags).joinToString(WRITE_SEPARATOR)

    /** 数字段感知的自然序比较（官方 `StrUtil.CompareNaturally` 同语义，区分大小写）。 */
    private fun compareNaturally(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var numA = 0L
                var numB = 0L
                while (i < a.length && a[i].isDigit()) {
                    numA = numA * 10 + (a[i] - '0')
                    i++
                }
                while (j < b.length && b[j].isDigit()) {
                    numB = numB * 10 + (b[j] - '0')
                    j++
                }
                if (numA != numB) return numA.compareTo(numB)
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        // 前缀相同者短串在前；i/j 已消耗的长度即公共前缀长度
        return (a.length - i).compareTo(b.length - j)
    }
}
