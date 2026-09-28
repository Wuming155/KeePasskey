package com.keepasskey.app.ui.screens.vault

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * ISSUE-P3-360 AC④c：搜索结果命中高亮。
 *
 * 行组件把**已生效的过滤词**（`uiState.searchQuery`，防抖后——非输入回显 `searchQueryDisplay`）
 * 在标题文本中的首次命中片段以加粗 + 主色着色标出；无命中 / 空词返回原文本。
 *
 * 拆分：[buildSearchHighlight] 为**纯函数**（颜色显式入参，宿主 JVM 可测），
 * [searchHighlight] 为取主题色的薄组合包装。
 */

/** 纯构造版：返回带命中 SpanStyle 的 [AnnotatedString]（大小写不敏感的首次命中）。 */
internal fun buildSearchHighlight(
    text: String,
    query: String,
    accent: Color,
    accentBackground: Color
): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    val matchIndex = text.indexOf(trimmed, ignoreCase = true)
    if (matchIndex < 0) return AnnotatedString(text)
    val matchEnd = matchIndex + trimmed.length
    return buildAnnotatedString {
        append(text.substring(0, matchIndex))
        withStyle(
            SpanStyle(
                color = accent,
                background = accentBackground,
                fontWeight = FontWeight.Bold
            )
        ) {
            append(text.substring(matchIndex, matchEnd))
        }
        append(text.substring(matchEnd))
    }
}

/** 组合包装：取当前主题的 primary / primaryContainer 作为命中配色。 */
@Composable
internal fun searchHighlight(text: String, query: String): AnnotatedString =
    buildSearchHighlight(
        text = text,
        query = query,
        accent = MaterialTheme.colorScheme.primary,
        accentBackground = MaterialTheme.colorScheme.primaryContainer
    )
