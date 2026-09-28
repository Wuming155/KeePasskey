package com.keepasskey.app.ui.screens.vault

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-360 AC④c：搜索结果命中高亮的纯函数契约。
 *
 * 高亮词取**已生效的过滤词**（防抖后 `uiState.searchQuery`）；本用例锁定构造语义：
 * 命中片段带加粗 + 主色 SpanStyle，未命中 / 空词返回原文本，大小写不敏感。
 */
class VaultSearchHighlightTest {

    private val accent = Color(0xFF0055CC)
    private val accentBackground = Color(0xFFDDE7FF)

    @Test
    fun `命中片段被切分为带样式的独立 span`() {
        val result = buildSearchHighlight("GitHub Enterprise", "git", accent, accentBackground)

        assertEquals("全文本必须完整保留", "GitHub Enterprise", result.text)
        val spans = result.spanStyles
        assertEquals("必须恰好一个命中 span", 1, spans.size)
        val span = spans.first()
        assertEquals("命中起点", 0, span.start)
        assertEquals("命中终点（3 字符）", 3, span.end)
        assertEquals(accent, span.item.color)
        assertEquals(accentBackground, span.item.background)
        assertEquals("命中必须加粗", androidx.compose.ui.text.font.FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun `大小写不敏感命中中段`() {
        val result = buildSearchHighlight("mail.Example.com", "EXAMPLE", accent, accentBackground)
        val span = result.spanStyles.single()
        assertEquals(5, span.start)
        assertEquals(12, span.end)
    }

    @Test
    fun `空词或未命中返回无样式原文`() {
        val blank = buildSearchHighlight("GitHub", "   ", accent, accentBackground)
        assertEquals("GitHub", blank.text)
        assertTrue("空词不得产生 span", blank.spanStyles.isEmpty())

        val miss = buildSearchHighlight("GitHub", "gitlab", accent, accentBackground)
        assertEquals("GitHub", miss.text)
        assertTrue("未命中不得产生 span", miss.spanStyles.isEmpty())
    }
}
