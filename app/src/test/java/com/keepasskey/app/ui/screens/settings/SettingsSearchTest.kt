package com.keepasskey.app.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-444` AC③「设置项搜索」的纯匹配 / 高亮内核用例。
 */
class SettingsSearchTest {

    private val highlight = SpanStyle(color = Color.Red)

    @Test
    fun `空查询恒命中且不产生高亮`() {
        assertTrue(SettingsSearch.matchesQuery("", listOf("任意标题")))
        assertTrue(SettingsSearch.matchesQuery("   ", listOf("任意标题")))
        val result = SettingsSearch.highlighted("两步验证 (TOTP)", "", highlight)
        assertEquals("两步验证 (TOTP)", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `大小写不敏感子串匹配`() {
        assertTrue(SettingsSearch.matchesQuery("totp", listOf("两步验证 (TOTP)", "副标题")))
        assertTrue(SettingsSearch.matchesQuery("TOTP", listOf("两步验证 (TOTP)")))
        assertTrue(SettingsSearch.matchesQuery("界面", listOf("界面偏好")))
    }

    @Test
    fun `任一候选文本命中即命中`() {
        assertTrue(SettingsSearch.matchesQuery("黑名单", listOf("自动填充", "系统自动填充服务、黑名单")))
        assertFalse(SettingsSearch.matchesQuery("不存在的词", listOf("自动填充", "系统自动填充服务")))
    }

    @Test
    fun `命中段被包上高亮样式且只包首个命中`() {
        val result = SettingsSearch.highlighted("两步验证 (TOTP) 与 TOTP 映射", "TOTP", highlight)
        assertEquals("两步验证 (TOTP) 与 TOTP 映射", result.text)
        assertEquals(1, result.spanStyles.size)
        val span = result.spanStyles.first()
        assertEquals(highlight, span.item)
        assertEquals("首个命中段的文本", "TOTP", result.text.substring(span.start, span.end))
    }

    @Test
    fun `未命中时原样返回无样式文本`() {
        val result = SettingsSearch.highlighted("自动填充", "totp", highlight)
        assertEquals("自动填充", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}
