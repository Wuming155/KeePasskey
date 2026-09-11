package com.keepasskey.app.ui.screens.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 密码库列表页对话框编排（ISSUE-P3-29：自 `VaultListScreen.kt` 拆出，纯结构性拆分）。
 *
 * 把「8 个对话框的可见性 / 目标对象」状态从巨型 Composable 中收敛为一个
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
    onBatchMove: (String?) -> Unit
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
            templateCount = uiState.templateEntries.size,
            onCreateFromTemplate = {
                controller.showCreateTypeDialog = false
                controller.showTemplatePickerDialog = true
            }
        )
    }

    // ISSUE-P3-51：模板选择对话框（选中即携带模板 id 进入编辑页）
    if (controller.showTemplatePickerDialog) {
        VaultTemplatePickerDialog(
            templates = uiState.templateEntries,
            onDismiss = { controller.showTemplatePickerDialog = false },
            onSelect = { templateId ->
                controller.showTemplatePickerDialog = false
                onCreateFromTemplate(templateId)
            }
        )
    }

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

    // 新建群组对话框
    if (controller.showCreateGroupDialog) {
        CreateGroupDialog(
            parentGroupName = uiState.breadcrumbs.lastOrNull()?.name,
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
}
