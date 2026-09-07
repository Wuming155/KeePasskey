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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 符合 Material 3 色调层级（Surface Roles）的 Bento 结构卡片。
 *
 * M3 规范：容器层级应由 surfaceContainer* 色阶表达（tonal 优先），
 * 边框仅用于强对比强调（如选中态），默认不再描边——
 * 全量 1dp outlineVariant 描边会在列表中形成视觉杂音（审美硬伤整改）。
 * 需要描边的调用方（如批量选中卡片）显式传入 [borderColor]。
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(width = borderWidth, color = borderColor, shape = shape)
                } else {
                    Modifier
                }
            )
            .padding(16.dp),
        content = content
    )
}
