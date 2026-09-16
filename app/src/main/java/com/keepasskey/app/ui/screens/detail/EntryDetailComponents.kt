package com.keepasskey.app.ui.screens.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * 详情页区块标题。
 *
 * 与设置二级页分节标题对齐（titleSmall + Bold + primary + 4dp 起始缩进），
 * 保证全应用内容页 / 设置页分节层级一致（Material 3 分区标签）。
 */
@Composable
internal fun SectionTitle(@StringRes textRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp)
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
 * 快捷操作行（ISSUE-P3-132 ④）
 *
 * 原实现是三张竖向磁贴卡片（每张约 76dp 高，合计占去首屏近 1/3 竖向空间），外部 UI 评审指出
 * 它把核心凭据内容推到折叠线以下、加重滚动疲劳。现改为**单行可横向滚动的 MD3 `AssistChip` 组**：
 * 同一组动作只占约 32dp（chip 容器高）+ 间距，三个动作、图标与触感反馈全部保留。
 *
 * 选横向滚动而非等分挤压的原因：文案在窄屏（360dp）下三等分会触发截断
 * （英文 “Copy username” 就放不进约 104dp），而滚动行的 chip 按内容自适应宽度。
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
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = { onShowMessage(UiMessage(R.string.detail_opening_browser)) },
            label = { Text(stringResource(R.string.detail_btn_open_url)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(QUICK_ACTION_ICON_SIZE)
                )
            }
        )
        AssistChip(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopyUsername(entry.title, entry.username)
            },
            label = { Text(stringResource(R.string.detail_btn_copy_user)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(QUICK_ACTION_ICON_SIZE)
                )
            }
        )
        AssistChip(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCopyPassword(entry.title)
            },
            label = { Text(stringResource(R.string.detail_btn_copy_pwd)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(QUICK_ACTION_ICON_SIZE)
                )
            }
        )
    }
}

/** 快捷操作 chip 的图标尺寸（M3 chip 内图标标准档） */
private val QUICK_ACTION_ICON_SIZE = 18.dp

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "详情页头部与快捷操作 - 浅色", showBackground = true)
@Preview(name = "详情页头部与快捷操作 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun EntryHeaderSectionPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntryHeaderSection(
                entry = com.keepasskey.app.ui.preview.PreviewEntryPasskey,
                icon = com.keepasskey.app.ui.preview.PreviewDefaultIcon,
                urlText = "https://example.com",
                groupPath = "网站登录 / 预览分组路径"
            )
            EntryHeaderSection(
                entry = com.keepasskey.app.ui.preview.PreviewEntryLogin,
                icon = com.keepasskey.app.ui.preview.PreviewMissingIcon,
                urlText = "https://example.com"
            )
            QuickActionRow(
                entry = com.keepasskey.app.ui.preview.PreviewEntryLogin,
                onShowMessage = { _ -> },
                onCopyUsername = { _, _ -> },
                onCopyPassword = { _ -> }
            )
            SectionTitle(textRes = com.keepasskey.app.R.string.detail_basic_section)
        }
    }
}
