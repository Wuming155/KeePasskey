package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.IconPickerDialog
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 排序选择对话框
 */
@Composable
internal fun VaultSortDialog(
    currentOption: VaultSortOption,
    onSelect: (VaultSortOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 紧凑化②（空间利用）：标题与选项列表**都放 title 槽**——
        // ① 丢掉 headlineSmall 大行高（32dp 行）与自带 16dp 下间距，改为 titleLarge 28dp
        //    行高 + 自管 4dp 间距；
        // ② 不用 text 槽 ⇒ 其**固定的 24dp 底部 padding**（列表与「关闭」之间的空白）
        //    换成 title 槽自带的 16dp，净省 8dp；
        // ③ 选项显式 onSurfaceVariant：挪槽后 LocalContentColor 变为标题的 onSurface，
        //    显式着色保持原 text 槽观感（正文体色未写时会继承槽位颜色）。
        title = {
            Column {
                Text(
                    text = stringResource(R.string.cd_sort),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                VaultSortOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            // 整行 selectable（自带 Role.RadioButton 语义）替代
                            // 「行 clickable + 圈自身 48dp 最小触控」的双重命中区；
                            // 圈传 onClick = null 后只按 20dp 视觉尺寸排版。
                            .selectable(
                                selected = currentOption == option,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentOption == option,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

/**
 * 新建分类选择对话框：新建条目或新建文件夹
 */
@Composable
internal fun VaultCreateTypeDialog(
    onDismiss: () -> Unit,
    onAddEntry: () -> Unit,
    onCreateFolder: () -> Unit,
    // ISSUE-P3-51：库内模板数量 > 0 时呈现「从模板新建」入口
    templateCount: Int = 0,
    onCreateFromTemplate: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onAddEntry, role = Role.Button),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.vault_new_entry), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                            Text(stringResource(R.string.vault_new_entry_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onCreateFolder, role = Role.Button),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.vault_new_folder), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                            Text(stringResource(R.string.vault_new_folder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ISSUE-P3-51：库内已安装模板库时提供「从模板新建」
                if (templateCount > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onCreateFromTemplate, role = Role.Button),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.vault_new_from_template), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                                Text(stringResource(R.string.vault_new_from_template_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * ISSUE-P3-51：从模板新建的模板选择对话框。
 * 逐条列出库内「模板」分组条目，选中即携带模板 id 进入编辑页（预填为**新条目**）。
 */
@Composable
internal fun VaultTemplatePickerDialog(
    templates: List<UiVaultEntry>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_template_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach { template ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(template.id) }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(getVaultIcon(template.iconName), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = template.title.ifBlank { template.id },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * `ISSUE-P2-291` AC②：库身份绑定不符的整库覆盖确认对话框——确认按钮用 error 色
 * 强化「不可恢复」语义；取消即保持本地与云端现状。
 *
 * `ISSUE-P2-529` AC①：补第三条出口「另存云端副本并覆盖」——先把云端副本另存为本地独立库
 * （`<原名> (副本 yyyyMMdd-HHmm).kdbx`），再把当前库覆盖上传。用户不再只有
 * 「覆盖（不可恢复）/ 取消」两条路。该动作同属破坏性（照样覆盖云端），故同样用 error 色。
 */
@Composable
internal fun VaultBindingTakeoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * AC① 的两份都留出口：先另存云端副本，成功后才覆盖。调用方负责失败时的用户提示
     * （副本另存失败即中止，云端数据保持不动）。
     */
    onConfirmKeepingCopy: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_vault_takeover_title)) },
        text = { Text(stringResource(R.string.sync_vault_takeover_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.sync_vault_takeover_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            // 单槽内两枚按钮必须自包 Row（按钮槽按 FlowRow 排布，平铺两枚仍是同层兄弟）
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                TextButton(onClick = onConfirmKeepingCopy) {
                    Text(
                        text = stringResource(R.string.sync_vault_takeover_confirm_keep_copy),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

/**
 * 批量移动文件夹选择对话框：支持移动到根目录或任一非回收站文件夹
 *
 * [titleRes] 供单条移动等复用场景替换标题（默认沿用批量移动标题）。
 */
@Composable
internal fun VaultBatchMoveDialog(
    allGroups: List<VaultGroup>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
    @androidx.annotation.StringRes titleRes: Int = R.string.vault_batch_move_title
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onMove(null) }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.vault_move_to_root), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }

                // ISSUE-P3-179：分组列表改**惰性 + 高度上限**——`AlertDialog` 的 `text` 槽**自身不滚动**，
                // 原实现把全部分组 `forEach` 铺进 `Column` ⇒ 分组多时超出屏幕的部分**无法触达**；
                // 同时补 `key`，使选择位置在增删分组后可稳定复用（同 `AppPickerDialog` 的写法）。
                // 过滤在组合之外做一次（原实现写在 `forEach` 实参里，每次重组都重跑一次整表过滤）。
                // ISSUE：根组（parentId == null，即库名本身，如「phellords」）已在上方以
                // 「移动至根目录」哨兵项（onMove(null)）独立呈现；投影层把根组也拍平进列表，
                // 若此处不过滤，根组会作为普通行与哨兵项重复、且两者落到同一位置（均归根组）。
                // 故显式排除根组（唯一 parentId == null 的节点），顶层子组不受影响。
                val selectableGroups = remember(allGroups) { allGroups.filter { !it.isRecycleBin && it.parentId != null } }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(selectableGroups, key = { it.id }) { grp ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onMove(grp.id) }
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(getVaultIcon(grp.iconName), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(grp.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "新建类型对话框 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "新建类型对话框 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultCreateTypeDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        VaultCreateTypeDialog(
            onDismiss = {},
            onAddEntry = {},
            onCreateFolder = {},
            templateCount = 2,
            onCreateFromTemplate = {}
        )
    }
}
