package com.keepasskey.app.ui.screens.vault

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.model.ChildVaultEntryGroup
import com.keepasskey.app.ui.model.ChildVaultEntryRow
import com.keepasskey.app.ui.model.EntryIcon

/**
 * 子库（ISSUE-P3-30）在库列表中的**只读分区**。
 *
 * ## 结构性只读
 *
 * 本文件的组件**不接收任何回调形参**——没有 `onClick` / `onLongClick` / `onCopyPassword` /
 * `onDelete` 之类的挂点。这不是「暂时没接」，而是首版只读边界的实现方式：
 * 入口不存在，就不存在被误接或绕过判定的可能（`ChildVaultEntryRow` 与 `UiVaultEntry`
 * 是两个类型，后者才被写路径消费；判定依据见 [ChildVaultEntryRow] KDoc）。
 *
 * 每行的尾随锁形图标带无障碍描述，向读屏用户如实声明「只读、无编辑或复制入口」——
 * 视觉上的无按钮与语义上的可感知声明同时成立。
 */

/** 分区标题（挂载别名 + 只读声明 + 条目数），分隔出「哪些条目来自子库」 */
@Composable
internal fun ChildDatabaseSectionHeader(
    group: ChildVaultEntryGroup,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(CHILD_SECTION_LOCK_ICON_DP.dp)
        )
        Spacer(modifier = Modifier.width(CHILD_SECTION_LOCK_GAP_DP.dp))
        Text(
            text = stringResource(R.string.vault_child_db_section_title, group.mountAlias),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.vault_child_db_section_count, group.entries.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单条子库只读条目行。
 *
 * 展示：标题 / 「挂载别名 + 子库分组路径」/ 用户名与 URL（受 [showUsername] / [showUrl] 控制）。
 * **不展示密码**：核心层投影本就不携带密码明文（只有 `hasPassword` 布尔事实），
 * 且本行无复制入口，故不存在「子库密码被复制到剪贴板」的路径。
 */
@Composable
internal fun ChildVaultEntryRowView(
    row: ChildVaultEntryRow,
    showUsername: Boolean,
    showUrl: Boolean,
    densitySpec: ListDensitySpec,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = densitySpec.rowHorizontalPaddingDp.dp,
                vertical = densitySpec.rowVerticalPaddingDp.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(densitySpec.iconContainerSizeDp.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                EntryIconContent(
                    icon = EntryIcon.Default(CHILD_ENTRY_ICON_NAME),
                    tint = MaterialTheme.colorScheme.tertiary,
                    placeholderIcon = Icons.Default.Key,
                    contentSize = densitySpec.iconContentSizeDp.dp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = densitySpec.titleFontSizeSp.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))
                GroupPathLine(
                    groupPath = row.displayPath,
                    fontSizeSp = densitySpec.secondaryFontSizeSp
                )

                if (hasSecondaryLine(row, showUsername, showUrl)) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (showUsername && row.username.isNotBlank()) {
                            Text(
                                text = row.username,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = densitySpec.secondaryFontSizeSp.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (showUsername && row.username.isNotBlank() && showUrl && row.url.isNotBlank()) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (showUrl && row.url.isNotBlank()) {
                            Text(
                                text = row.url.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = densitySpec.secondaryFontSizeSp.sp
                                ),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 无按钮：仅以锁形图标 + 无障碍描述声明只读（无编辑 / 无复制入口）
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.cd_child_db_entry_read_only),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(CHILD_ENTRY_LOCK_ICON_DP.dp)
            )
        }
    }
}

/**
 * 搜索态下的如实提示：子库条目不参与搜索与自动填充（ISSUE-P3-30 裁决 2）。
 *
 * 仅在「用户正在搜索」且「确有已挂载子库」时出现——用户搜不到子库条目时，
 * 界面要说明原因，而不是静默漏掉。
 */
@Composable
internal fun ChildDatabaseSearchExclusionHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.vault_child_db_search_excluded),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

/** 是否存在可展示的次级信息行（避免空行占位） */
private fun hasSecondaryLine(
    row: ChildVaultEntryRow,
    showUsername: Boolean,
    showUrl: Boolean
): Boolean = (showUsername && row.username.isNotBlank()) || (showUrl && row.url.isNotBlank())

/** 子库条目使用的标准图标 id（子库条目暂无自定义图标投影，统一回退钥匙图标） */
private const val CHILD_ENTRY_ICON_NAME = "key"

/** 分区标题锁形图标边长（dp） */
private const val CHILD_SECTION_LOCK_ICON_DP = 14

/** 分区标题锁形图标与文案间距（dp） */
private const val CHILD_SECTION_LOCK_GAP_DP = 6

/** 只读条目行尾随锁形图标边长（dp） */
private const val CHILD_ENTRY_LOCK_ICON_DP = 16
