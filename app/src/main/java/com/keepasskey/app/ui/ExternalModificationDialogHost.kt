package com.keepasskey.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * ISSUE-P2-378：外部修改三选对话框挂点（自 KeePasskeyApp 拆出，控文件行数）。
 *
 * `ISSUE-P1-631`：追加漂移合并的**冲突裁决**对话框——两者互斥呈现（裁决优先），
 * 裁决结果与三选共用同一提交入口。
 */
@Composable
internal fun ExternalModificationDialogHost(
    pending: com.keepasskey.app.security.VaultFileDriftPrompt?,
    mergeConflict: com.keepasskey.app.security.VaultFileDriftMergeConflict?,
    onChoice: suspend (com.keepasskey.app.security.ExternalModificationChoice) -> com.keepasskey.core.result.KdbxResult<Unit>,
    onMergeConflictCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    if (mergeConflict != null) {
        // 裁决优先：三选与裁决在协调器侧互斥，此分支仅防御两态交叠的过渡帧
        com.keepasskey.app.ui.screens.settings.subscreens.DriftMergeConflictDialog(
            conflict = mergeConflict,
            onChoice = { choice -> scope.launch { onChoice(choice) } },
            onCancel = onMergeConflictCancel
        )
        return
    }
    if (pending == null) return
    com.keepasskey.app.ui.screens.settings.subscreens.ExternalModificationDialog(
        onChoice = { choice ->
            scope.launch { onChoice(choice) }
        }
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
