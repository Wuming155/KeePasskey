package com.keepasskey.app.ui.screens.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * 详情页区块标题（统一 titleMedium + SemiBold 样式）
 */
@Composable
internal fun SectionTitle(@StringRes textRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

/**
 * 头部 Hero 区域
 *
 * ISSUE-P3-02：[icon] 为状态层投影后的图标（自定义位图 / 缺图占位 / 标准图标），
 * [urlText] 为 URL 字段引用展开后的展示文案；本组件只做纯绘制。
 *
 * ISSUE-P3-17：[groupPath] 非空时在 URL 下方展示条目所属分组完整路径
 * （仅 `showGroupInEntry` 开启时由状态层下发，UI 不做路径计算）。
 */
@Composable
internal fun EntryHeaderSection(
    entry: UiVaultEntry,
    icon: BitmapEntryIcon,
    urlText: String,
    groupPath: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            EntryIconContent(
                icon = icon,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                placeholderIcon = getVaultIcon(entry.iconName),
                contentSize = 32.dp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry.isPasskey) {
                    Spacer(modifier = Modifier.width(8.dp))
                    PasskeyBadge()
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = urlText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            // ISSUE-P3-17：showGroupInEntry 开启时的所属分组路径
            if (groupPath != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = groupPath,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 快捷操作磁贴组
 */
@Composable
internal fun QuickActionRow(
    entry: UiVaultEntry,
    onShowMessage: (UiMessage) -> Unit,
    onCopyUsername: (String, String) -> Unit,
    onCopyPassword: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionTile(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            label = stringResource(R.string.detail_btn_open_url),
            modifier = Modifier.weight(1f),
            onClick = { onShowMessage(UiMessage(R.string.detail_opening_browser)) }
        )
        QuickActionTile(
            icon = Icons.Default.ContentCopy,
            label = stringResource(R.string.detail_btn_copy_user),
            modifier = Modifier.weight(1f),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopyUsername(entry.title, entry.username)
            }
        )
        QuickActionTile(
            icon = Icons.Default.Key,
            label = stringResource(R.string.detail_btn_copy_pwd),
            modifier = Modifier.weight(1f),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopyPassword(entry.title)
            }
        )
    }
}

/**
 * 快捷操作磁贴小组件
 */
@Composable
internal fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
