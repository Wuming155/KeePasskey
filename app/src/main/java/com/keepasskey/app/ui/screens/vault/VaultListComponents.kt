package com.keepasskey.app.ui.screens.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.theme.CapsuleShape

/** 下拉指示组件随手势下移的最大距离 */
private val INDICATOR_TRAVEL_Y = 36.dp

/**
 * 下拉刷新指示组件：随下拉进度淡入下移，在指示区展示上次同步时间；同步进行中切换为进度环
 */
@Composable
internal fun LastSyncPullIndicator(
    distanceFraction: Float,
    isSyncing: Boolean,
    lastSyncTimeText: String,
    modifier: Modifier = Modifier
) {
    if (distanceFraction <= 0f && !isSyncing) return
    val progress = distanceFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // 下拉过程中保持手势跟手，仅在指示区范围内生效
                alpha = if (isSyncing) 1f else progress
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = CapsuleShape,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            ),
            shadowElevation = if (isSyncing) 3.dp else 0.dp,
            modifier = Modifier
                .padding(top = 10.dp)
                .graphicsLayer {
                    translationY = INDICATOR_TRAVEL_Y.toPx() * progress
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.vault_sync_verifying),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = stringResource(R.string.vault_last_sync_time, lastSyncTimeText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 新建入口悬浮按钮：滚动时按设置隐藏；只读会话不展示（H4-只读整改）
 */
@Composable
internal fun VaultListFab(
    visible: Boolean,
    isReadOnly: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        // H4-只读整改：只读会话隐藏新建入口
        if (!isReadOnly) {
            ExtendedFloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CapsuleShape,
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_create)) },
                text = { Text(stringResource(R.string.btn_create), fontWeight = FontWeight.SemiBold) }
            )
        }
    }
}

/**
 * H2 整改：存在待解决冲突会话时展示「去解决冲突」横幅——
 * 此前冲突解决页是死路由，用户收到冲突提示后无任何入口
 */
@Composable
internal fun PendingConflictBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.sync_feedback_conflict),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.vault_conflict_action),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 面包屑路径导航：根目录 + 各级文件夹，当前层级高亮
 */
@Composable
internal fun VaultBreadcrumbBar(
    breadcrumbs: List<VaultGroup>,
    currentGroupId: String?,
    onNavigateUp: () -> Unit,
    onNavigateToBreadcrumb: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_navigate_up),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            LazyRow(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Text(
                        text = stringResource(R.string.vault_root_dir),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onNavigateToBreadcrumb(null) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                items(breadcrumbs) { grp ->
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    val isCurrent = grp.id == currentGroupId
                    Text(
                        text = grp.name,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onNavigateToBreadcrumb(grp.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 回收站模式警示横幅
 */
@Composable
internal fun RecycleBinBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.vault_recycle_bin_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/**
 * 当前非默认排序状态轻量提示，点击右侧文案恢复默认排序
 */
@Composable
internal fun SortStatusHint(
    sortOption: VaultSortOption,
    onResetSort: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.vault_current_sort, stringResource(sortOption.labelRes)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.vault_reset_sort),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onResetSort() }
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}

/**
 * 空状态：搜索无结果与空目录分别展示不同图标与文案
 */
@Composable
internal fun VaultEmptyState(
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isSearching) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isSearching) stringResource(R.string.vault_empty_title)
                else stringResource(R.string.vault_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
