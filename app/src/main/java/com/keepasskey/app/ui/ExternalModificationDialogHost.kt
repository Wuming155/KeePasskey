package com.keepasskey.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * ISSUE-P2-378：外部修改三选对话框挂点（自 KeePasskeyApp 拆出，控文件行数）。
 *
 * `ISSUE-P1-631`：追加漂移合并的**冲突裁决**对话框——两者互斥呈现（裁决优先），
 * 裁决结果与三选共用同一提交入口。
 *
 * `ISSUE-P1-631`（重复提交守卫）：处置提交在途期间置 `submitting`——按钮禁用、新点击丢弃。
 * 覆盖「连点」「组合重建（旋转屏幕）后同一提示仍在时的再次点击」两类重复提交；守卫在提交
 * 调用**返回后**（含失败重提示）复位，故不会因「同一提示被重新置起（值相等）」而卡死。
 *
 * 旋转屏幕的取消边界（如实登记）：提交协程挂在组合作用域上，旋转会取消在途调用；但
 * `VaultFileDriftResolve` 的「采用 + 落盘」段为 `NonCancellable`——取消只会丢弃本次回执展示，
 * 不会在磁盘与会话树之间留下半步状态。
 */
@Composable
internal fun ExternalModificationDialogHost(
    pending: com.keepasskey.app.security.VaultFileDriftPrompt?,
    mergeConflict: com.keepasskey.app.security.VaultFileDriftMergeConflict?,
    onChoice: suspend (com.keepasskey.app.security.ExternalModificationChoice) -> com.keepasskey.core.result.KdbxResult<Unit>,
    onMergeConflictCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 置位与复位都在同一（组合 / 主）线程：置位在点击回调内，复位在提交返回后
    var submitting by remember { mutableStateOf(false) }
    val guarded: (com.keepasskey.app.security.ExternalModificationChoice) -> Unit = { choice ->
        if (!submitting) {
            submitting = true
            scope.launch {
                try {
                    onChoice(choice)
                } finally {
                    submitting = false
                }
            }
        }
    }
    if (mergeConflict != null) {
        // 裁决优先：三选与裁决在协调器侧互斥，此分支仅防御两态交叠的过渡帧
        com.keepasskey.app.ui.screens.settings.subscreens.DriftMergeConflictDialog(
            conflict = mergeConflict,
            onChoice = guarded,
            onCancel = onMergeConflictCancel,
            enabled = !submitting
        )
        return
    }
    if (pending == null) return
    com.keepasskey.app.ui.screens.settings.subscreens.ExternalModificationDialog(
        onChoice = guarded,
        enabled = !submitting
    )
}

/**
 * `ISSUE-P2-378` + `ISSUE-P1-631`：外观层挂点——从设置页 ViewModel 订阅三选与合并冲突
 * 两个待决状态并驱动 [ExternalModificationDialogHost]（KeePasskeyApp 仅保留一行调用，
 * 控其函数行数门禁）。裁决结果与三选共用同一提交入口
 * （`SettingsViewModel.applyExternalModificationChoice`）。
 */
@Composable
internal fun DriftDialogsHost(settingsViewModel: SettingsViewModel) {
    val pending by settingsViewModel.externalModificationPending.collectAsStateWithLifecycle()
    val mergeConflict by settingsViewModel.driftMergeConflict.collectAsStateWithLifecycle()
    ExternalModificationDialogHost(
        pending = pending,
        mergeConflict = mergeConflict,
        onChoice = { settingsViewModel.applyExternalModificationChoice(it) },
        onMergeConflictCancel = { settingsViewModel.cancelDriftMergeConflict() }
    )
}
