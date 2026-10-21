package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ListDensity

/**
 * ISSUE-P3-348：分组图标 tile 语义分色——回收站用 error 容器系，其余按 iconName
 * 稳定哈希轮换 primary / secondary / tertiary 三组容器色（排序/重排不换色；
 * 禁止按列表下标取色，颜色只取现有语义令牌）。
 */
@Composable
private fun groupTileColors(group: VaultGroup): Pair<Color, Color> =
    if (group.isRecycleBin) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        when (Math.floorMod(group.iconName.hashCode(), 3)) {
            0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        }
    }

/**
 * 文件夹行组件（ISSUE-P3-29：自 `VaultEntryRows.kt` 拆出，同包同可见性，纯结构性拆分）。
 *
 * ISSUE-P3-22：分组图标与条目侧共用同一投影结果（[EntryIconPresenter] 同一缓存）。
 * - 命中图标池 → 绘制自定义位图；
 * - 池中缺失（引用残留）→ 缺图占位，**不**回退标准图标谎报状态；
 * - 未绑定（[icon] 为 null 或 [EntryIcon.Default]）→ 既有标准矢量图标语义。
 *
 * ISSUE-P3-17：[densitySpec] 驱动行高 / 内边距 / 字号，数值只在 [ListDensityPresenter] 定义。
 *
 * ISSUE-P3-644：只读会话下三个写菜单项**不可执行**，故菜单项的 `enabled` 取 `!readOnly`，
 * 并在菜单底部说明原因（[R.string.readonly_save_rejected]）——整改前菜单照常可点、控制器在
 * `launch` 前静默 `return`，用户只看到「点了没反应」。控制器的只读硬守卫仍保留（第二道防线）。
 */
@Composable
fun KeePassGroupRow(
    group: VaultGroup,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onChangeIcon: () -> Unit,
    onDelete: () -> Unit,
    // ISSUE-P3-644：只读会话（VaultListUiState.isReadOnly）禁用重命名 / 换图标 / 删除
    readOnly: Boolean = false,
    icon: BitmapEntryIcon? = null,
    densitySpec: ListDensitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL),
    highlightQuery: String = "", // ISSUE-P3-360 AC④c：搜索命中高亮词
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        // 与条目卡统一为「无描边 + surfaceContainerLow」填充卡，避免文件夹/条目混搭两种卡片语义
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = densitySpec.rowHorizontalPaddingDp.dp,
                vertical = densitySpec.rowVerticalPaddingDp.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ISSUE-P3-348：分组图标 tile 语义分色（配色规则见 groupTileColors）
            val (tileContainer, tileTint) = groupTileColors(group)
            Box(
                modifier = Modifier
                    .size(densitySpec.iconContainerSizeDp.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileContainer),
                contentAlignment = Alignment.Center
            ) {
                EntryIconContent(
                    icon = icon ?: EntryIcon.Default(group.iconName),
                    tint = tileTint,
                    placeholderIcon = if (group.isRecycleBin) Icons.Default.Delete else getVaultIcon(group.iconName),
                    contentSize = densitySpec.iconContentSizeDp.dp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = searchHighlight(group.name, highlightQuery),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = densitySpec.titleFontSizeSp.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (!group.isRecycleBin) {
                GroupRowMenu(
                    readOnly = readOnly,
                    showMenu = showMenu,
                    onOpenMenu = { showMenu = true },
                    onDismissMenu = { showMenu = false },
                    onRename = onRename,
                    onChangeIcon = onChangeIcon,
                    onDelete = onDelete
                )
            }
        }
    }
}

/**
 * 分组行的三点溢出菜单（ISSUE-P3-644 自 [KeePassGroupRow] 拆出：母函数加只读分支后
 * 越过 100 行函数门禁，按职责把「菜单及其三个写项」独立成件；渲染与点击语义零变化）。
 *
 * 只读会话下三项 `enabled = false` 并在菜单底部说明原因——整改前菜单照常可点、
 * 控制器在 `launch` 前静默 `return`，用户只看到「点了没反应」。
 */
@Composable
private fun GroupRowMenu(
    readOnly: Boolean,
    showMenu: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onChangeIcon: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
        IconButton(onClick = onOpenMenu) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.btn_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu) {
            // 只读会话：合并语义节点，让整个菜单项（含文本）随 `enabled = false` 一并被读屏/
            // 无障碍树标记为禁用——分割子节点时文本节点自身的 enabled 不反映父项状态。
            DropdownMenuItem(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                text = { Text(stringResource(R.string.vault_folder_rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                // ISSUE-P3-644：只读会话不伪装可执行——置灰而非「点了没反应」
                enabled = !readOnly,
                onClick = { onDismissMenu(); onRename() }
            )
            DropdownMenuItem(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                text = { Text(stringResource(R.string.vault_folder_change_icon)) },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                enabled = !readOnly,
                onClick = { onDismissMenu(); onChangeIcon() }
            )
            DropdownMenuItem(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                text = { Text(stringResource(R.string.vault_folder_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                enabled = !readOnly,
                onClick = { onDismissMenu(); onDelete() }
            )
            // ISSUE-P3-644：菜单仍可展开（浏览操作照常可见），但必须在菜单内说明
            // 三项为何不可点——否则置灰只留下「坏了」的观感
            if (readOnly) {
                Text(
                    text = stringResource(R.string.readonly_save_rejected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "分组行 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "分组行 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun KeePassGroupRowPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        KeePassGroupRow(
            group = com.keepasskey.app.ui.preview.PreviewGroupLogins,
            onClick = {},
            onRename = {},
            onChangeIcon = {},
            onDelete = {},
            icon = com.keepasskey.app.ui.preview.PreviewDefaultIcon,
            densitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL)
        )
    }
}

// ISSUE-P3-644：只读态预览——菜单项置灰与原因说明只在展开菜单后可见：
// 预览面板无法点击展开，故本态的**可见性**由该预览留档，行为判据由
// `VaultGroupWriteFeedbackWiringTest`（源码守卫）与 `VaultGroupWriteFeedbackDeviceTest`
// （真实点击 + 无障碍节点 `isEnabled` 断言）承担，避免默认态以外的分支不可见。
@androidx.compose.ui.tooling.preview.Preview(name = "分组行只读 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "分组行只读 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun KeePassGroupRowReadOnlyPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        KeePassGroupRow(
            group = com.keepasskey.app.ui.preview.PreviewGroupLogins,
            onClick = {},
            onRename = {},
            onChangeIcon = {},
            onDelete = {},
            readOnly = true,
            icon = com.keepasskey.app.ui.preview.PreviewDefaultIcon,
            densitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL)
        )
    }
}

// ISSUE-P3-348 AC③：分色双态并排预览——回收站（error 容器系）+ 普通分组（轮换容器色）
// 同屏对照，分色回归在预览图里一眼可辨（遵守 ISSUE-P3-340 补态规则）。
@androidx.compose.ui.tooling.preview.Preview(name = "分组行语义分色 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "分组行语义分色 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun KeePassGroupRowSemanticColorPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        androidx.compose.foundation.layout.Column {
            KeePassGroupRow(
                group = com.keepasskey.app.ui.preview.PreviewGroupLogins.copy(id = "g-a", name = "网站登录", iconName = "folder"),
                onClick = {}, onRename = {}, onChangeIcon = {}, onDelete = {},
                densitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL)
            )
            KeePassGroupRow(
                group = com.keepasskey.app.ui.preview.PreviewGroupLogins.copy(id = "g-b", name = "银行卡", iconName = "bank"),
                onClick = {}, onRename = {}, onChangeIcon = {}, onDelete = {},
                densitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL)
            )
            KeePassGroupRow(
                group = com.keepasskey.app.ui.preview.PreviewGroupLogins.copy(id = "g-c", name = "安全便签", iconName = "note"),
                onClick = {}, onRename = {}, onChangeIcon = {}, onDelete = {},
                densitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL)
            )
            KeePassGroupRow(
                group = com.keepasskey.app.ui.preview.PreviewGroupLogins.copy(id = "g-rb", name = "回收站", iconName = "trash", isRecycleBin = true),
                onClick = {}, onRename = {}, onChangeIcon = {}, onDelete = {},
                densitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL)
            )
        }
    }
}
