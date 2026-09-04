package com.keepasskey.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 符合 2026 Material 3 Expressive 风格的 Bento 结构卡片
 * 具备柔和的 Surface 填充与 1dp 半透明微边框
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(16.dp),
        content = content
    )
}
