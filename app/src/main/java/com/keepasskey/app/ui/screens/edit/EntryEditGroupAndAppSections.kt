package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.apps.InstalledAppOption
import com.keepasskey.app.apps.InstalledAppsCatalog
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.theme.CapsuleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 编辑页的**分组选择分节**与**绑定应用解析**（§160 自 `EntryEditFormSections.kt` 纯结构性搬家，
 * 组件体逐字未改；母文件当时 438 行仍在 `ISSUE-P3-188` 第一档阈值之上）。
 *
 * 为什么这两块同文件：分组 `FilterChip` 行与「绑定应用」图标解析都属于
 * **「URL 字段的取值上下文」**——前者决定条目落哪个分组，后者决定 `EntryUrlField`
 * 前置图标与应用名可读性；两者都不含业务判断，只依赖 `EntryEditUiState` 的展示字段。
 *
 * `rememberBoundAppOption` 原为母文件 `private`，本批放宽为 `internal`（仅同模块可见）。
 */

/** 所属群组 / 文件夹选择（回收站分组不作为可选目标）。 */
@Composable
internal fun ColumnScope.EntryEditGroupSection(
    uiState: EntryEditUiState,
    onGroupChange: (String?) -> Unit
) {
    // ISSUE-P3-179：回收站过滤下沉到 `LazyRow` **之外**——`LazyRow` 的 content 是 `LazyListScope`
    // （非 `@Composable`），不能在其中 `remember`；原实现把 `filter` 写在 `items(...)` 实参里，
    // 编辑表单每敲一个字符都会重组并重跑一次整表过滤。
    val selectableGroups = remember(uiState.availableGroups) {
        uiState.availableGroups.filter { !it.isRecycleBin }
    }
    if (uiState.availableGroups.isEmpty()) return

    Text(
        text = stringResource(R.string.edit_group_label),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = uiState.groupId == null,
                onClick = { onGroupChange(null) },
                label = { Text(stringResource(R.string.edit_group_root)) },
                shape = CapsuleShape,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
        items(selectableGroups, key = { it.id }) { grp ->
            val selected = uiState.groupId == grp.id
            FilterChip(
                selected = selected,
                onClick = { onGroupChange(grp.id) },
                label = { Text(grp.name) },
                shape = CapsuleShape,
                leadingIcon = {
                    Icon(
                        imageVector = getVaultIcon(grp.iconName),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

/**
 * 解析绑定包名的应用信息（应用名 + 图标，IO 线程）。
 *
 * 未绑定（[packageName] 为 null）时返回 null，不渲染前置图标与辅助文案；
 * 绑定但不可解析（未安装 / 受包可见性限制）时回落为「包名即名称 + 通用图标」，
 * 让用户看到绑定**确实存在但当前不可读**，而不是被静默隐藏。
 */
@Composable
internal fun rememberBoundAppOption(packageName: String?): InstalledAppOption? {
    if (packageName == null) return null
    val context = LocalContext.current
    val placeholder = remember(packageName) { InstalledAppOption(packageName, packageName) }
    val option = produceState(initialValue = placeholder, packageName) {
        val resolved = withContext(Dispatchers.IO) {
            InstalledAppsCatalog.lookup(context, packageName)
        }
        if (resolved != null) value = resolved
    }
    return option.value
}
