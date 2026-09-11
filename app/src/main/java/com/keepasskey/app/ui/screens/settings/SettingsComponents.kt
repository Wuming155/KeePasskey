package com.keepasskey.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 现代分组卡片容器
 */
@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        content = content
    )
}

/**
 * 组内行间细致分隔线
 */
@Composable
internal fun SettingsItemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 68.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = 0.6.dp
    )
}

/**
 * 现代偏好设置项目行（圆角 Squircle 色彩胶囊图标 + 标题/副标题 + 状态胶囊 + 前进指示）
 */
@Composable
internal fun ModernSettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingBadge: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 柔和色调 Squircle 图标胶囊
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }

        if (trailingBadge != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = trailingBadge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * 现代风格分类标题
 */
@Composable
internal fun ModernSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}
