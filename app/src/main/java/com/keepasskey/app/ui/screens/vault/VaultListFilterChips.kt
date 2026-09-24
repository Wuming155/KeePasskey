package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 列表筛选芯片行（ISSUE-P3-297 处置③：标签 / 收藏接线）。
 *
 * 「收藏」档 + 全库标签候选两段芯片；点击已选芯片再次切换即取消该档。
 * 可见性判据：库内确有收藏条目或存在标签候选时才渲染，避免空筛选项占位。
 * 筛选档与排序同口径为会话态（不持久化），过滤逻辑在状态层
 * [selectSortedEntries] 完成，本组件只做选择与回传。
 */
@Composable
internal fun VaultFilterChipRow(
    availableTags: List<String>,
    hasFavoriteEntries: Boolean,
    favoriteOnly: Boolean,
    selectedTag: String?,
    onFavoriteFilterChange: (Boolean) -> Unit,
    onTagFilterChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hasFavoriteEntries) {
            FilterChip(
                selected = favoriteOnly,
                onClick = { onFavoriteFilterChange(!favoriteOnly) },
                label = {
                    Text(
                        text = stringResource(R.string.vault_filter_favorites),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            )
        }
        availableTags.forEach { tag ->
            FilterChip(
                selected = tag == selectedTag,
                onClick = { onTagFilterChange(if (tag == selectedTag) null else tag) },
                label = { Text(text = tag, style = MaterialTheme.typography.labelLarge) }
            )
        }
    }
}
