package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.rememberMaybeHaptic
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 密码库列表页对话框编排（ISSUE-P3-29：自 `VaultListScreen.kt` 拆出，纯结构性拆分）。
 *
 * 把「10 个对话框的可见性 / 目标对象」状态从巨型 Composable 中收敛为一个
 * [Stable] 状态持有者，配合 [VaultListDialogHost] 完成渲染；Screen 只负责触发者
 * （顶栏 / FAB / 行回调）与宿主之间的一次性开关置位，不再内联 100 行对话框接线。
 */
@Stable
internal class VaultListDialogController {
    var showSortDialog by mutableStateOf(false)
    var showCreateTypeDialog by mutableStateOf(false)
    // ISSUE-P3-51：从模板新建的模板选择对话框
    var showTemplatePickerDialog by mutableStateOf(false)
    var showCreateGroupDialog by mutableStateOf(false)
    var showEmptyRecycleBinDialog by mutableStateOf(false)
    var showBatchMoveDialog by mutableStateOf(false)
    var groupToRename by mutableStateOf<VaultGroup?>(null)
    var groupToChangeIcon by mutableStateOf<VaultGroup?>(null)
    var groupToDelete by mutableStateOf<VaultGroup?>(null)
    // ISSUE-P2-357 AC①：批量删除确认（顶栏删除图标先置位，确认后才执行）
    var showBatchDeleteConfirm by mutableStateOf(false)
    // ISSUE-P2-357 AC①：回收站永久删除确认的目标条目（非空即确认对话框可见）
    var purgeEntryToDelete by mutableStateOf<UiVaultEntry?>(null)
}

@Composable
internal fun rememberVaultListDialogController(): VaultListDialogController =
    remember { VaultListDialogController() }

/**
 * 渲染 [VaultListScreen] 的全部对话框；每个对话框在关闭时自行复位控制器状态。
 */
@Composable
internal fun VaultListDialogHost(
    controller: VaultListDialogController,
    uiState: VaultListUiState,
    onSortOptionSelect: (VaultSortOption) -> Unit,
    onAddEntryClick: () -> Unit,
    // ISSUE-P3-51：从模板新建（入参为选中模板的条目 id）
    onCreateFromTemplate: (String) -> Unit,
    onCreateGroup: (name: String, icon: String) -> Unit,
    onRenameGroup: (VaultGroup, String) -> Unit,
    onChangeGroupIcon: (VaultGroup, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onBatchMove: (String?) -> Unit,
    // ISSUE-P2-357 AC①：两条破坏性删除的确认出口（确认后才执行）
    onBatchDelete: () -> Unit,
    onPurgeEntry: (String) -> Unit
) {
    // 排序选择对话框
    if (controller.showSortDialog) {
        VaultSortDialog(
            currentOption = uiState.sortOption,
            onSelect = { option ->
                onSortOptionSelect(option)
                controller.showSortDialog = false
            },
            onDismiss = { controller.showSortDialog = false }
        )
    }

    // 新建分类 / 从模板新建（ISSUE-P3-51）
    VaultListCreationDialogs(
        controller = controller,
        templateCount = uiState.templateEntries.size,
        templates = uiState.templateEntries,
        onAddEntryClick = onAddEntryClick,
        onCreateFromTemplate = onCreateFromTemplate
    )

    // 批量移动文件夹选择对话框
    if (controller.showBatchMoveDialog) {
        VaultBatchMoveDialog(
            allGroups = uiState.allGroups,
            onDismiss = { controller.showBatchMoveDialog = false },
            onMove = { targetGroupId ->
                onBatchMove(targetGroupId)
                controller.showBatchMoveDialog = false
            }
        )
    }

    // 群组类对话框（新建 / 重命名 / 换图标 / 删除）
    VaultListGroupDialogs(
        controller = controller,
        parentGroupName = uiState.breadcrumbs.lastOrNull()?.name,
        onCreateGroup = onCreateGroup,
        onRenameGroup = onRenameGroup,
        onChangeGroupIcon = onChangeGroupIcon,
        onDeleteGroup = onDeleteGroup
    )

    // 清空回收站确认对话框
    if (controller.showEmptyRecycleBinDialog) {
        VaultEmptyRecycleBinDialog(
            onDismiss = { controller.showEmptyRecycleBinDialog = false },
            onConfirm = {
                onEmptyRecycleBin()
                controller.showEmptyRecycleBinDialog = false
            }
        )
    }

    // ISSUE-P2-357 AC①：批量删除确认（含条目数与后果文案；确认后才执行软删除）
    if (controller.showBatchDeleteConfirm) {
        VaultBatchDeleteConfirmDialog(
            selectedCount = uiState.selectedEntryIds.size,
            onDismiss = { controller.showBatchDeleteConfirm = false },
            onConfirm = {
                controller.showBatchDeleteConfirm = false
                onBatchDelete()
            }
        )
    }

    // ISSUE-P2-357 AC①：回收站永久删除确认（不可逆；确认后才执行）
    controller.purgeEntryToDelete?.let { target ->
        VaultPurgeEntryConfirmDialog(
            entryTitle = target.title,
            onDismiss = { controller.purgeEntryToDelete = null },
            onConfirm = {
                controller.purgeEntryToDelete = null
                onPurgeEntry(target.id)
            }
        )
    }
}

/**
 * 新建类对话框（§196 自 `VaultListDialogHost` 原样下沉）：分类选择 + 模板选择。
 *
 * 二者的可见性开关都在 [VaultListDialogController] 里，本段只负责渲染与复位，
 * 「选完即关」的顺序（先关对话框、再触发跳转）逐字保留。
 */
@Composable
private fun VaultListCreationDialogs(
    controller: VaultListDialogController,
    templateCount: Int,
    templates: List<UiVaultEntry>,
    onAddEntryClick: () -> Unit,
    onCreateFromTemplate: (String) -> Unit
) {
    // 新建分类选择对话框
    if (controller.showCreateTypeDialog) {
        VaultCreateTypeDialog(
            onDismiss = { controller.showCreateTypeDialog = false },
            onAddEntry = {
                controller.showCreateTypeDialog = false
                onAddEntryClick()
            },
            onCreateFolder = {
                controller.showCreateTypeDialog = false
                controller.showCreateGroupDialog = true
            },
            // ISSUE-P3-51：库内已安装模板时呈现「从模板新建」并转入模板选择
            templateCount = templateCount,
            onCreateFromTemplate = {
                controller.showCreateTypeDialog = false
                controller.showTemplatePickerDialog = true
            }
        )
    }

    // ISSUE-P3-51：模板选择对话框（选中即携带模板 id 进入编辑页）
    if (controller.showTemplatePickerDialog) {
        VaultTemplatePickerDialog(
            templates = templates,
            onDismiss = { controller.showTemplatePickerDialog = false },
            onSelect = { templateId ->
                controller.showTemplatePickerDialog = false
                onCreateFromTemplate(templateId)
            }
        )
    }
}

/**
 * 群组类对话框（§196 原样下沉）：新建 / 重命名 / 换图标 / 删除，各自的目标对象都存在控制器里
 * （`groupToRename` 等），关闭时复位——渲染顺序不影响行为，故与「新建类」拆为两段。
 */
@Composable
private fun VaultListGroupDialogs(
    controller: VaultListDialogController,
    parentGroupName: String?,
    onCreateGroup: (String, String) -> Unit,
    onRenameGroup: (VaultGroup, String) -> Unit,
    onChangeGroupIcon: (VaultGroup, String) -> Unit,
    onDeleteGroup: (String) -> Unit
) {
    // 新建群组对话框
    if (controller.showCreateGroupDialog) {
        CreateGroupDialog(
            parentGroupName = parentGroupName,
            onDismiss = { controller.showCreateGroupDialog = false },
            onConfirm = { name, icon ->
                onCreateGroup(name, icon)
                controller.showCreateGroupDialog = false
            }
        )
    }

    // 重命名文件夹对话框
    controller.groupToRename?.let { grp ->
        VaultRenameGroupDialog(
            group = grp,
            onDismiss = { controller.groupToRename = null },
            onConfirm = { newName ->
                onRenameGroup(grp, newName)
                controller.groupToRename = null
            }
        )
    }

    // 更换文件夹图标对话框
    controller.groupToChangeIcon?.let { grp ->
        VaultChangeGroupIconDialog(
            group = grp,
            onSelectIcon = { newIcon ->
                onChangeGroupIcon(grp, newIcon)
                controller.groupToChangeIcon = null
            },
            onDismiss = { controller.groupToChangeIcon = null }
        )
    }

    // 删除文件夹确认对话框
    controller.groupToDelete?.let { grp ->
        VaultDeleteGroupDialog(
            group = grp,
            onDismiss = { controller.groupToDelete = null },
            onConfirm = {
                onDeleteGroup(grp.id)
                controller.groupToDelete = null
            }
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview(name = "新建分类对话框宿主 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "新建分类对话框宿主 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultListDialogHostPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        // 显式打开「新建分类」对话框：其余对话框的可见性开关保持默认关闭（互相独立，不叠加渲染）
        val controller = remember { VaultListDialogController() }.apply { showCreateTypeDialog = true }

        VaultListDialogHost(
            controller = controller,
            uiState = com.keepasskey.app.ui.screens.vault.VaultListUiState(
                databaseName = "Preview Vault.kdbx"
            ),
            onSortOptionSelect = {},
            onAddEntryClick = {},
            onCreateFromTemplate = {},
            onCreateGroup = { _, _ -> },
            onRenameGroup = { _, _ -> },
            onChangeGroupIcon = { _, _ -> },
            onDeleteGroup = {},
            onEmptyRecycleBin = {},
            onBatchMove = {},
            onBatchDelete = {},
            onPurgeEntry = {}
        )
    }
}

/**
 * ISSUE-P2-357 AC①/AC③：批量删除确认对话框——文案含**条目数与后果说明**（软删除 → 回收站可找回）。
 * 样式 / 按钮措辞 / error 色与既有三处确认同口径（分组删除 [VaultDeleteGroupDialog]、
 * 清空回收站 [VaultEmptyRecycleBinDialog]、详情页单条删除）：error 图标 + error 容器色确认键 +
 * Reject 触感 + `btn_delete` / `btn_cancel` 措辞。
 */
@Composable
internal fun VaultBatchDeleteConfirmDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val maybeHaptic = rememberMaybeHaptic()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.vault_batch_delete_confirm_title)) },
        text = { Text(stringResource(R.string.vault_batch_delete_confirm_message, selectedCount)) },
        confirmButton = {
            Button(
                onClick = {
                    maybeHaptic(HapticFeedbackType.Reject)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(stringResource(R.string.btn_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * ISSUE-P2-357 AC①/AC③：回收站永久删除确认对话框——文案明示**永久、无法恢复**（不可逆）。
 * 样式与 [VaultBatchDeleteConfirmDialog] / [VaultEmptyRecycleBinDialog] 同口径
 * （DeleteForever 图标 + error 容器色确认键 + Reject 触感 + `btn_delete` / `btn_cancel`）。
 */
@Composable
internal fun VaultPurgeEntryConfirmDialog(
    entryTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val maybeHaptic = rememberMaybeHaptic()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.vault_purge_entry_confirm_title)) },
        text = { Text(stringResource(R.string.vault_purge_entry_confirm_message, entryTitle)) },
        confirmButton = {
            Button(
                onClick = {
                    maybeHaptic(HapticFeedbackType.Reject)
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(stringResource(R.string.btn_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
