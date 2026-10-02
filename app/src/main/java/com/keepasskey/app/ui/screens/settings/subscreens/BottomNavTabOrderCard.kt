package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.BottomNavTabNames
import com.keepasskey.app.ui.components.BottomNavItem
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 底栏 Tab「显隐 + 排序」一体化卡片（ISSUE-P3-443）。
 *
 * 消费 [SettingsUiState.bottomNavOrder]（有序可见 Tab 名单），回写经设置 ViewModel 落
 * SettingsRepository。行序、兜底与「不可隐藏集合」的口径见 [BottomNavItem.displayOrderFor] /
 * [BottomNavItem.resolveVisibleItems] 与本卡片 KDoc。
 */

/**
 * ISSUE-P3-443：底栏 Tab「显隐 + 排序」一体化卡片。
 *
 * 行序来自 [BottomNavItem.displayOrderFor]（可见项按配置顺序在前、隐藏项殿后）；
 * 上移 / 下移在完整序列内换位后回写「可见名单」；显隐开关仅「验证码 / 生成器」可关
 * （密码库为隐藏回落目标、设置为返回设置页的常驻入口，二者固定显示——与旧开关口径一致，
 * 「至少保留一个可见 Tab」不变量由此天然成立）。
 */
@Composable
internal fun BottomNavTabOrderCard(
    orderNames: List<String>,
    onOrderChange: (List<String>) -> Unit
) {
    val displayOrder = remember(orderNames) { BottomNavItem.displayOrderFor(orderNames) }
    val visibleNames = remember(orderNames) { BottomNavItem.resolveVisibleItems(orderNames).map { it.name }.toSet() }

    /** 换位 / 开关后回写：完整序列中的可见子集（保持展示顺序） */
    fun persistVisibleFrom(newDisplay: List<BottomNavItem>) {
        onOrderChange(newDisplay.filter { it.name in visibleNames }.map { it.name })
    }

    Column {
        Text(
            text = stringResource(R.string.theme_nav_tabs_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        displayOrder.forEachIndexed { index, item ->
            BottomNavTabOrderRow(
                item = item,
                visible = item.name in visibleNames,
                canMoveUp = index > 0,
                canMoveDown = index < displayOrder.lastIndex,
                onMoveUp = {
                    persistVisibleFrom(displayOrder.toMutableList().apply {
                        add(index - 1, removeAt(index))
                    })
                },
                onMoveDown = {
                    persistVisibleFrom(displayOrder.toMutableList().apply {
                        add(index + 1, removeAt(index))
                    })
                },
                onToggle = { checked ->
                    val visibleItems = BottomNavItem.resolveVisibleItems(orderNames)
                    onOrderChange(
                        if (checked) {
                            visibleItems.map { it.name } + item.name
                        } else {
                            visibleItems.filter { it != item }.map { it.name }
                        }
                    )
                }
            )
        }
    }
}

/**
 * 排序卡片的单行：图标 + 标签 +（可选「固定显示」说明）+ 上移 / 下移 + 显隐开关。
 * 「至少一个可见 Tab」不变量由不可隐藏集合保证（见 [BottomNavTabOrderCard] KDoc）。
 */
@Composable
private fun BottomNavTabOrderRow(
    item: BottomNavItem,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.selectedIcon,
            contentDescription = null,
            tint = if (visible) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!item.canHide) {
                Text(
                    text = stringResource(R.string.theme_nav_tab_pinned_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.cd_move_tab_up)
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_move_tab_down)
            )
        }
        Switch(
            checked = visible,
            // 密码库 / 设置固定显示（口径见 [BottomNavTabOrderCard] KDoc）
            enabled = item.canHide,
            onCheckedChange = onToggle
        )
    }
}

// ISSUE-P3-443 预览：默认全显态 + 「验证码已隐藏」反向态（显隐开关的关态必须有预览，ISSUE-P3-340）
@Preview(name = "底栏 Tab 排序 - 全部显示 - 浅色", showBackground = true)
@Preview(name = "底栏 Tab 排序 - 全部显示 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun BottomNavTabOrderCardAllVisiblePreview() {
    KeePasskeyTheme {
        BottomNavTabOrderCard(
            orderNames = BottomNavTabNames.DEFAULT_ORDER,
            onOrderChange = {}
        )
    }
}

@Preview(name = "底栏 Tab 排序 - 验证码已隐藏 - 浅色", showBackground = true)
@Preview(name = "底栏 Tab 排序 - 验证码已隐藏 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun BottomNavTabOrderCardAuthenticatorHiddenPreview() {
    KeePasskeyTheme {
        BottomNavTabOrderCard(
            orderNames = listOf(
                BottomNavTabNames.VAULT,
                BottomNavTabNames.GENERATOR,
                BottomNavTabNames.SETTINGS
            ),
            onOrderChange = {}
        )
    }
}
