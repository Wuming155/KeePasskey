package com.keepasskey.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 位于主界面显眼位置的快捷主题切换胶囊组件
 * 点击可在 [浅色 -> 深色 -> 跟随系统] 三态平滑循环切换，状态清晰可见。
 */
@Composable
fun ThemeToggleCapsule(
    currentTheme: AppThemeMode,
    onThemeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CapsuleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CapsuleShape
            )
            .clickable { onThemeToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(
            targetState = currentTheme,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ThemeIconAnimation"
        ) { theme ->
            // 主题名称按当前语言动态解析，替代枚举内置的硬编码文案
            val themeLabel = when (theme) {
                AppThemeMode.LIGHT -> stringResource(R.string.capsule_theme_light)
                AppThemeMode.DARK -> stringResource(R.string.capsule_theme_dark)
                AppThemeMode.SYSTEM -> stringResource(R.string.capsule_theme_system)
            }
            val icon = when (theme) {
                AppThemeMode.LIGHT -> Icons.Default.LightMode
                AppThemeMode.DARK -> Icons.Default.DarkMode
                AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
            }
            val tint = when (theme) {
                AppThemeMode.LIGHT -> MaterialTheme.colorScheme.primary
                AppThemeMode.DARK -> MaterialTheme.colorScheme.primary
                AppThemeMode.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(
                imageVector = icon,
                contentDescription = themeLabel,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = when (currentTheme) {
                AppThemeMode.LIGHT -> stringResource(R.string.capsule_theme_light)
                AppThemeMode.DARK -> stringResource(R.string.capsule_theme_dark)
                AppThemeMode.SYSTEM -> stringResource(R.string.capsule_theme_system)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
