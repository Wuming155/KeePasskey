package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.FiniteAnimationSpec

/**
 * VaultListScreen 的列表分区段落（§280 规模门禁同批从 [VaultListScreen] 逐字迁出，
 * 结构性拆分：`LazyListScope` 扩展形态，渲染语义零变化）。
 */

/**
 * 6. ISSUE-P3-30：已解锁子库的只读分区。
 *
 * 与根库条目**并列**渲染（各自独立的 key 命名空间），不混入根库 items(entries)；
 * 子库行组件不接收任何写回调，故列表分区内不存在编辑 / 删除 / 复制入口。
 */
internal fun LazyListScope.childDatabaseSection(
    uiState: VaultListUiState,
    densitySpec: ListDensitySpec,
    // ISSUE-P3-444 AC②：动效降级态传 null（`animateItem` 的 spec 参数可空 ⇒ 直切）
    itemFadeSpec: FiniteAnimationSpec<Float>?,
    itemPlacementSpec: FiniteAnimationSpec<IntOffset>?
) {
    if (uiState.childEntrySectionVisible) {
        uiState.childEntryGroups.forEach { childGroup ->
            item(key = "child_header_${childGroup.mountId}") {
                ChildDatabaseSectionHeader(group = childGroup)
            }
            items(childGroup.entries, key = { "child_${it.rowKey}" }) { childEntry ->
                ChildVaultEntryRowView(
                    row = childEntry,
                    showUsername = uiState.showUsernameInList,
                    showUrl = uiState.showUrlInList,
                    densitySpec = densitySpec,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeSpec,
                        placementSpec = itemPlacementSpec,
                        fadeOutSpec = itemFadeSpec
                    )
                )
            }
        }
    }
}
