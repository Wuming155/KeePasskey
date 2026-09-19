package com.keepasskey.app.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R

/**
 * 详情页顶栏的溢出菜单（§194 自 `EntryDetailTopBar.kt` 原样下沉）。
 *
 * `showOverflowMenu` 随本段一并迁入：**该状态只被本菜单的开合使用**，留在父级只是把局部 UI 态
 * 抬成了跨组件参数。明文与凭据不经此处——三项全是静态动作文案，
 * 故 `PopupSecureFlagInventoryTest` 的「无需接线」判定不变（其清单锁随本次搬移同步换名，
 * 菜单项计数仍为 8，由该测试当场复验）。
 *
 * @param showsCustomIconDelete 条目确实绑定了库级自定义图标时才追加第三项；
 *   图标是共享资源，删除会连带回退全部引用条目，故点击后仍须经确认弹窗。
 */
@Composable
internal fun EntryDetailOverflowMenu(
    showsCustomIconDelete: Boolean,
    onRequestMoveEntry: () -> Unit,
    onRequestDeleteEntry: () -> Unit,
    onRequestDeleteCustomIcon: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    // ISSUE-P3-48：溢出菜单——非只读会话恒呈现（单条「移入回收站」入口）；
    // 条目绑定了库级自定义图标时追加「删除自定义图标」项。
    Box(modifier = modifier) {
        IconButton(onClick = { showOverflowMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false }
        ) {
            // ISSUE-P3-51：单条移动到分组（对话框内过滤回收站）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_move_to_group)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    showOverflowMenu = false
                    onRequestMoveEntry()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.detail_delete_entry),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    showOverflowMenu = false
                    onRequestDeleteEntry()
                }
            )
            // ISSUE-P3-02：自定义图标删除入口——仅「条目确实绑定了自定义图标」时呈现；
            // 图标是库级共享资源，删除会连带回退全部引用条目，故点击后仍须经确认弹窗
            if (showsCustomIconDelete) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.vault_icon_delete_action),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showOverflowMenu = false
                        onRequestDeleteCustomIcon()
                    }
                )
            }
        }
    }
}
