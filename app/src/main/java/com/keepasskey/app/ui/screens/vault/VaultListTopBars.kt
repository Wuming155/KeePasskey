package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 批量选择操作顶栏
 *
 * ISSUE-P3-644：只读会话下三个批量写按钮（全选 / 批量移动 / 批量删除）一律置灰，
 * 并在操作区说明原因——整改前它们照常可点，控制器在 `launch` 前静默 `return`，
 * 用户只看到「点了没反应」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultListBatchModeTopBar(
    selectedCount: Int,
    onClearBatch: () -> Unit,
    onSelectAllBatch: () -> Unit,
    onBatchMoveClick: () -> Unit,
    onBatchDelete: () -> Unit,
    // ISSUE-P3-644：只读会话（VaultListUiState.isReadOnly）禁用三个批量写按钮
    isReadOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.vault_batch_selected_count, selectedCount),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        navigationIcon = {
            // 「取消批量」只退出选择模式、不写库 ⇒ 只读下保持可用
            IconButton(onClick = onClearBatch) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_cancel_batch))
            }
        },
        actions = {
            IconButton(onClick = onSelectAllBatch, enabled = !isReadOnly) {
                Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.cd_select_all))
            }
            IconButton(onClick = onBatchMoveClick, enabled = !isReadOnly) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.cd_batch_move))
            }
            IconButton(onClick = onBatchDelete, enabled = !isReadOnly) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_batch_delete), tint = MaterialTheme.colorScheme.error)
            }
            // ISSUE-P3-644：置灰必须配原因，否则只读用户只看到「坏了」
            if (isReadOnly) {
                Text(
                    text = stringResource(R.string.readonly_save_rejected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}

/**
 * 主顶栏：标题位为胶囊形搜索框；进入回收站后操作区替换为「清空回收站」入口。
 *
 * 常规态操作区收敛为一个竖排三点（MoreVert）溢出菜单，内含「排序」「高级搜索」
 * 「选择」「锁定」「扫码」若干项（`ISSUE-P2-544`：高级搜索自独立图标收进本菜单）。
 *
 * ISSUE-P3-17：
 * - [autoActivateSearch] 为 true 时聚焦搜索框并弹出输入法（一次性意图，消费后经
 *   [onAutoActivateSearchConsumed] 回执，避免重组反复抢焦点）；
 * - [onKillApp] 非空时溢出菜单追加「彻底退出应用」入口（偏好开启且宿主可终止才会非空）。
 *
 * 扫码项（otpauth 二维码 → 直接创建验证码条目）：[onScanClick] 为 null 时不渲染该项
 * （只读会话隐藏，与写入口同口径）。
 *
 * ISSUE-P3-644：[isReadOnly] 为真时**隐藏**「选择」批量入口——批量模式下的移动 / 删除
 * 在只读会话不可执行，提供入口只会把用户带进一个全是置灰按钮的模式。
 * 宿主（[VaultListContent]）仍显式传 `onSelectEntriesClick`，隐藏判定收在本组件内，
 * 与扫码项「由宿主传 null」的既有口径并存不冲突（两条路径都只读 ⇒ 都不渲染）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultListSearchTopBar(
    /** ISSUE-P2-356：须传**未防抖**的即时回显值——受控输入框接防抖值即快速打字回吞 */
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isInsideRecycleBin: Boolean,
    sortOption: VaultSortOption,
    onSortClick: () -> Unit,
    onLockClick: () -> Unit,
    onEmptyRecycleBinClick: () -> Unit,
    autoActivateSearch: Boolean = false,
    onAutoActivateSearchConsumed: () -> Unit = {},
    onKillApp: (() -> Unit)? = null,
    /** 非空才呈现「扫码」入口；只读会话传 null 隐藏 */
    onScanClick: (() -> Unit)? = null,
    /** ISSUE-P3-360 AC④a：非空才呈现溢出菜单「选择」项（进入批量模式；回收站内无溢出菜单） */
    onSelectEntriesClick: (() -> Unit)? = null,
    // ISSUE-P3-644：只读会话隐藏「选择」批量入口（批量写按钮在该会话一律不可执行）
    isReadOnly: Boolean = false,
    /** ISSUE-P3-439 / ISSUE-P2-544：溢出菜单「高级搜索」项（打开选项对话框） */
    onAdvancedSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoActivateSearch) {
        if (!autoActivateSearch) return@LaunchedEffect
        searchFocusRequester.requestFocus()
        keyboardController?.show()
        onAutoActivateSearchConsumed()
    }

    TopAppBar(
        modifier = modifier,
        title = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.vault_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester)
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.cd_clear_search),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (isInsideRecycleBin) {
                IconButton(onClick = onEmptyRecycleBinClick) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.vault_empty_recycle_bin),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                // 收敛后的竖排三点溢出菜单：排序 / 高级搜索（ISSUE-P2-544）/ 选择（ISSUE-P3-360 AC④a）
                // / 锁定 / 扫码（只读隐藏）/（可选）彻底退出
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.btn_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_sort)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = null,
                                    tint = if (sortOption != VaultSortOption.DEFAULT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                onSortClick()
                            }
                        )
                        // ISSUE-P2-544：高级搜索选项（字段范围 / 排除已过期 / 大小写）——自
                        // 「搜索顶栏独立图标」收敛进本菜单；对话框形态不受列表滚动位置影响，
                        // 点击后必然同屏可见（独立图标在已滚动的列表里会被插到视口上方，见该条目）
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.search_advanced_title)) },
                            leadingIcon = {
                                Icon(Icons.Default.Tune, contentDescription = null)
                            },
                            onClick = {
                                showOverflowMenu = false
                                onAdvancedSearchClick()
                            }
                        )
                        // ISSUE-P3-360 AC④a：批量模式的第二入口（首长按之外的可发现路径）
                        // ISSUE-P3-644：只读会话不提供——批量写按钮在该会话一律不可执行
                        if (onSelectEntriesClick != null && !isReadOnly) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_menu_select_entries)) },
                                leadingIcon = {
                                    Icon(Icons.Default.SelectAll, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onSelectEntriesClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_lock)) },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            },
                            onClick = {
                                showOverflowMenu = false
                                onLockClick()
                            }
                        )
                        // 扫码添加验证码条目（二维码解析成功后在当前分组直接落库）；只读会话不渲染
                        // （本项为静态动作文案，无凭据插值——由 PopupSecureFlagInventoryTest 复验）
                        if (onScanClick != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_scan_menu)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onScanClick()
                                }
                            )
                        }
                        // ISSUE-P3-17：showKillAppOption 开启且宿主可终止时的「彻底退出应用」入口
                        if (onKillApp != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sec_kill_app_action)) },
                                leadingIcon = {
                                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onKillApp()
                                }
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}
