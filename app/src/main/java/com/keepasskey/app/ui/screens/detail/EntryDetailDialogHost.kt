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

/** 破坏性确认对话框上报的用户意图（ISSUE-P3-188 剩余清单第 5 项：把出口决策变成可 JVM 断言的纯函数）。 */
internal enum class EntryDetailConfirmIntent { CONFIRM, DISMISS }

/**
 * 破坏性确认的出口决策：是否上行不可逆动作、是否复位触发态。
 * 唯一裁定入口是 [entryDetailConfirmExit]——「取消 → 只复位、绝不上行」与
 * 「确认 → 复位并上行」不得在调用点各自手写，否则「确认后才上行」的闸门语义无从断言。
 */
internal data class EntryDetailConfirmExit(
    val propagateAction: Boolean,
    val resetTriggerState: Boolean
)

internal fun entryDetailConfirmExit(intent: EntryDetailConfirmIntent): EntryDetailConfirmExit =
    when (intent) {
        EntryDetailConfirmIntent.CONFIRM ->
            EntryDetailConfirmExit(propagateAction = true, resetTriggerState = true)
        EntryDetailConfirmIntent.DISMISS ->
            EntryDetailConfirmExit(propagateAction = false, resetTriggerState = true)
    }

/**
 * 出口执行器：意图 → [entryDetailConfirmExit] → 按仓库口径「**先复位、再上行**」执行
 * （§169 导出确认与 §198 删除确认同款顺序）。回滚确认历史上是「先上行、再复位」的孤例，
 * §200 并入口径——唯一行为差异是 [propagate] 同步抛错时对话框先关闭，批次文档已留痕。
 */
internal fun entryDetailConfirmExitHandler(
    reset: () -> Unit,
    propagate: () -> Unit
): (EntryDetailConfirmIntent) -> Unit = { intent ->
    val exit = entryDetailConfirmExit(intent)
    if (exit.resetTriggerState) reset()
    if (exit.propagateAction) propagate()
}

/**
 * 渲染 [EntryDetailContent] 的全部对话框；每个对话框在关闭 / 确认后自行复位控制器状态。
 *
 * §198 下沉时与拆分前逐字一致；§200 起两份破坏性确认（单条删除 / 版本回滚）的出口
 * 改经 [entryDetailConfirmExitHandler] 统一裁定（顺序：先复位、再上行），其余接线
 * （含 `LaunchedEffect(rev.id)` 的差异预备与 `onClearRevisionDiff` 收口）保持原样。
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
        EntryDetailDeleteEntryConfirm(
            onExit = entryDetailConfirmExitHandler(
                reset = { controller.showDeleteEntryConfirm = false },
                propagate = onDeleteEntry
            )
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
        EntryDetailRollbackConfirm(
            onExit = entryDetailConfirmExitHandler(
                reset = { controller.revisionToRollback = null },
                propagate = { onRollbackRevision(rev) }
            )
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

/**
 * 单条删除确认对话框（§198 自 [EntryDetailDialogHost] **原样下沉**，文案 / error 色 / 出口结构未改）。
 *
 * §200 起只上报 [EntryDetailConfirmIntent]、自己不复位也不上行——「先复位、再上行」的组合
 * 统一由调用点经 [entryDetailConfirmExitHandler] 执行（与 §169 对导出确认的同一口径：
 * 状态所有权只有一处，段组件不持复位职责）。
 */
@Composable
private fun EntryDetailDeleteEntryConfirm(
    onExit: (EntryDetailConfirmIntent) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onExit(EntryDetailConfirmIntent.DISMISS) },
        title = { Text(stringResource(R.string.detail_delete_entry_title)) },
        text = { Text(stringResource(R.string.detail_delete_entry_message)) },
        confirmButton = {
            TextButton(onClick = { onExit(EntryDetailConfirmIntent.CONFIRM) }) {
                Text(
                    text = stringResource(R.string.btn_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onExit(EntryDetailConfirmIntent.DISMISS) }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * 版本回滚确认对话框（§198 原样下沉；`Button` + `CapsuleShape` 的出口样式刻意**不与上面的
 * TextButton 版合并**——两者视觉语义不同，为省 20 行而统一它们属于为指标改动）。
 *
 * §200 起出口与删除确认同构：只上报 [EntryDetailConfirmIntent]，复位与上行由调用点的
 * [entryDetailConfirmExitHandler] 执行；其「先上行、再复位」的历史顺序已并入口径（见该函数 KDoc）。
 */
@Composable
private fun EntryDetailRollbackConfirm(
    onExit: (EntryDetailConfirmIntent) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onExit(EntryDetailConfirmIntent.DISMISS) },
        title = { Text(stringResource(R.string.detail_history_rollback)) },
        text = { Text(stringResource(R.string.detail_history_rollback_confirm)) },
        confirmButton = {
            Button(
                onClick = { onExit(EntryDetailConfirmIntent.CONFIRM) },
                shape = CapsuleShape
            ) {
                Text(stringResource(R.string.btn_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = { onExit(EntryDetailConfirmIntent.DISMISS) }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * 明文附件导出的二次确认对话框（`ISSUE-P3-188` §169 自 [EntryDetailScreen] **归位**到本文件，
 * 与其余详情页对话框同处一份；UI 树、文案与 error 色正文逐字未改）。
 *
 * **状态所有权仍在外层**：`showPlaintextExportConfirm` 与两个 pending 态由页面持有，
 * 「取消 → 清理 SAF 已建空文档 → 复位三态」与「确认 → 复位三态 → 放行导出」的**顺序**逐字保持
 * （本组件只呈现两条出口，避免同一状态出现两处真相）。
 */
@Composable
internal fun EntryDetailPlaintextExportConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.detail_attachment_export_warn_title)) },
        text = {
            Text(
                text = stringResource(R.string.detail_attachment_export_warn_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.detail_attachment_export_warn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
