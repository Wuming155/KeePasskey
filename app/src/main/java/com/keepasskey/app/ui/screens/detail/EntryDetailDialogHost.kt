package com.keepasskey.app.ui.screens.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.vault.VaultBatchMoveDialog
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 凭据详情页对话框编排（ISSUE-P3-188：自 [EntryDetailContent] 拆出，纯结构性拆分，
 * 与 [com.keepasskey.app.ui.screens.vault.VaultListDialogHost] 同构）。
 *
 * 把「六个二次确认 / 预览对话框的可见性与目标对象」从巨型 Composable 中收敛为一个
 * [Stable] 状态持有者，再由 [EntryDetailDialogHost] 统一渲染；页面本体只保留触发者
 * （顶栏菜单 / 卡片行回调）对开关的置位，不再内联对话框接线。
 */
@Stable
internal class EntryDetailDialogController {
    // ISSUE-P3-02：自定义图标删除二次确认（库级共享资源，防误删）
    var showDeleteIconConfirm by mutableStateOf(false)

    // ISSUE-P3-48：单条删除二次确认（移入回收站，防误删）
    var showDeleteEntryConfirm by mutableStateOf(false)

    // ISSUE-P3-51：单条移动到分组的分组选择器
    var showMoveDialog by mutableStateOf(false)

    var revisionToRollback by mutableStateOf<UiEntryRevision?>(null)
    var revisionToDiff by mutableStateOf<UiEntryRevision?>(null)
    var attachmentToPreview by mutableStateOf<UiAttachment?>(null)
}

@Composable
internal fun rememberEntryDetailDialogController(): EntryDetailDialogController =
    remember { EntryDetailDialogController() }

/**
 * 渲染 [EntryDetailContent] 的全部对话框；每个对话框在关闭 / 确认后自行复位控制器状态。
 *
 * 接线与拆分前逐字一致（含 `LaunchedEffect(rev.id)` 的差异预备与 `onClearRevisionDiff` 收口）。
 */
@Composable
internal fun EntryDetailDialogHost(
    controller: EntryDetailDialogController,
    uiState: EntryDetailUiState,
    entry: UiVaultEntry?,
    onDeleteCustomIcon: () -> Unit,
    onDeleteEntry: () -> Unit,
    onMoveEntry: (String?) -> Unit,
    onRollbackRevision: (UiEntryRevision) -> Unit,
    onPrepareRevisionDiff: (String) -> Unit,
    onClearRevisionDiff: () -> Unit,
    onExportAttachment: (UiAttachment) -> Unit
) {
    // ISSUE-P3-02：自定义图标删除确认（库级共享资源，确认后才上行删除）
    if (controller.showDeleteIconConfirm) {
        DeleteCustomIconDialog(
            onConfirm = {
                controller.showDeleteIconConfirm = false
                onDeleteCustomIcon()
            },
            onDismiss = { controller.showDeleteIconConfirm = false }
        )
    }

    // ISSUE-P3-48：单条删除确认（确认后才上行；语义为移入回收站 / 站内彻底删除）
    if (controller.showDeleteEntryConfirm) {
        AlertDialog(
            onDismissRequest = { controller.showDeleteEntryConfirm = false },
            title = { Text(stringResource(R.string.detail_delete_entry_title)) },
            text = { Text(stringResource(R.string.detail_delete_entry_message)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.showDeleteEntryConfirm = false
                    onDeleteEntry()
                }) {
                    Text(
                        text = stringResource(R.string.btn_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.showDeleteEntryConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ISSUE-P3-51：单条移动到分组选择器（回收站分组由对话框统一过滤）
    if (controller.showMoveDialog) {
        VaultBatchMoveDialog(
            allGroups = uiState.allGroups,
            onDismiss = { controller.showMoveDialog = false },
            onMove = { targetGroupId ->
                controller.showMoveDialog = false
                onMoveEntry(targetGroupId)
            },
            titleRes = R.string.detail_move_dialog_title
        )
    }

    // 版本回滚确认对话框
    controller.revisionToRollback?.let { rev ->
        AlertDialog(
            onDismissRequest = { controller.revisionToRollback = null },
            title = { Text(stringResource(R.string.detail_history_rollback)) },
            text = { Text(stringResource(R.string.detail_history_rollback_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRollbackRevision(rev)
                        controller.revisionToRollback = null
                    },
                    shape = CapsuleShape
                ) {
                    Text(stringResource(R.string.btn_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.revisionToRollback = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 版本历史差异对比对话框 (Visual Diff)
    controller.revisionToDiff?.let { rev ->
        LaunchedEffect(rev.id) { onPrepareRevisionDiff(rev.id) }
        entry?.let { current ->
            RevisionVisualDiffDialog(
                currentEntry = current,
                revision = rev,
                currentPassword = uiState.revealedPassword.orEmpty(),
                revisionPassword = uiState.revealedRevisionPasswords[rev.id].orEmpty(),
                onDismiss = {
                    onClearRevisionDiff()
                    controller.revisionToDiff = null
                },
                onRollback = {
                    onRollbackRevision(rev)
                    controller.revisionToDiff = null
                }
            )
        }
    }

    // 安全附件预览对话框 (Safe Attachment Previewer)
    controller.attachmentToPreview?.let { att ->
        SafeAttachmentPreviewDialog(
            attachment = att,
            onDismiss = { controller.attachmentToPreview = null },
            onExport = {
                onExportAttachment(att)
                controller.attachmentToPreview = null
            }
        )
    }
}
