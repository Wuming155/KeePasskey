package com.keepasskey.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ISSUE-P2-282` AC①／AC②：标签分隔符**单一词汇表**与官方 `NormalizeTag(s)` 等价实现回归。
 *
 * 期望值按官方 `StrUtil.cs`（KeePass-2.61.1）语义手写：切分集合 `{ ',', ';' }`（`g_vTagSep`）、
 * `NormalizeTag`＝trim + 分隔符替换为 `.`、`NormalizeTags`＝去空 + 去重 + 自然排序、
 * 存储形态裸 `;`（`TagsToString(bForDisplay = false)`）。
 */
class KdbxTagsTest {

    @Test
    fun `切分集合与写侧分隔符即官方 g_vTagSep 与存储形态`() {
        assertEquals(listOf(',', ';'), KdbxTags.SEPARATORS.toList())
        assertEquals(";", KdbxTags.WRITE_SEPARATOR)
    }

    @Test
    fun `normalizeTag：trim 并把残留分隔符替换为点（官方口径，不是剔除整项）`() {
        assertEquals("a.b.c", KdbxTags.normalizeTag("  a,b;c  "))
        assertEquals("工作", KdbxTags.normalizeTag(" 工作 "))
        // 纯分隔符输入经替换后为「. .」（分隔符换点、中间空格保留）而非空串
        // （官方同型口径：替换在先、去空看替换后结果）
        assertEquals(". .", KdbxTags.normalizeTag(" , ; "))
        assertEquals("", KdbxTags.normalizeTag("   "))
    }

    @Test
    fun `normalizeTags：去空去重并按自然序排列（数字段按数值比较）`() {
        assertEquals(
            listOf("a2", "a10", "b"),
            KdbxTags.normalizeTags(listOf("b", "a10", "a2", "b", "a10", ""))
        )
    }

    @Test
    fun `parse：逗号与分号同为分隔符（逗号库不再读成单个标签）`() {
        assertEquals(listOf("a", "b", "c"), KdbxTags.parse("a,b;c"))
        assertEquals(listOf("a", "b"), KdbxTags.parse("a, b"))
        assertEquals(listOf("a", "b"), KdbxTags.parse("a;,b,;"))
        assertEquals(emptyList<String>(), KdbxTags.parse(null))
        assertEquals(emptyList<String>(), KdbxTags.parse(""))
    }

    @Test
    fun `serialize：归一化后以裸分号连接（官方存储形态）`() {
        assertEquals("a;b", KdbxTags.serialize(listOf("b", "a")))
        assertEquals("a.b", KdbxTags.serialize(listOf("a,b")))
        assertEquals("", KdbxTags.serialize(emptyList()))
    }

    @Test
    fun `parse 与 serialize 往返自洽`() {
        val tags = listOf("b", "a10", "a2", "b")
        assertEquals(KdbxTags.normalizeTags(tags), KdbxTags.parse(KdbxTags.serialize(tags)))
    }
}
