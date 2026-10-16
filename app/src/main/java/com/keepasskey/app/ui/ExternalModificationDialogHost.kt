package com.keepasskey.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.screens.settings.SettingsViewModel

/**
 * ISSUE-P2-378：外部修改三选对话框挂点（自 KeePasskeyApp 拆出，控文件行数）。
 *
 * `ISSUE-P1-631`：追加漂移合并的**冲突裁决**对话框——两者互斥呈现（裁决优先），
 * 裁决结果与三选共用同一提交入口。
 *
 * `ISSUE-P1-631`（生命周期整改，原「组合内 rememberCoroutineScope 提交」已废除）：
 * - 处置提交不再由本组合发起：[onChoice] 已是**普通回调**，调用方（生产
 *   `DriftDialogsHost` → `SettingsViewModel.submitExternalModificationChoice` /
 *   设备侧 `DriftDialogHostActivity`）经协调器 `submitChoice` 把执行体挂到
 *   **应用级作用域**——漂移裁决是数据层持久化事务，真机实测（复现用例 A/B）证明
 *   挂在组合作用域上时用户点击后退出页面即产生「选择已被 UI 消费、提示已清、
 *   文件未变」的数据丢失第三态，故提交生命周期必须与组合解耦；
 * - 重入守卫改由协调器 `choiceSubmissionInProgress` 承载（应用级状态，组合重建不丢）：
 *   真值期间三选 / 裁决按钮禁用、「稍后处理」取消被忽略，覆盖「连点」「组合重建后
 *   再次点击」两类重复提交；
 * - UI 状态不得领先持久化事实：提交在途期间冲突对话框**保持可见**（仅禁用按钮），
 *   由 `VaultFileDriftResolve` 的终结路径（成功清提示 / 失败保留裁决）决定其存续。
 */
@Composable
internal fun ExternalModificationDialogHost(
    pending: com.keepasskey.app.security.VaultFileDriftPrompt?,
    mergeConflict: com.keepasskey.app.security.VaultFileDriftMergeConflict?,
    submissionInProgress: Boolean,
    onChoice: (com.keepasskey.app.security.ExternalModificationChoice) -> Unit,
    onMergeConflictCancel: () -> Unit
) {
    if (mergeConflict != null) {
        // 裁决优先：三选与裁决在协调器侧互斥，此分支仅防御两态交叠的过渡帧
        com.keepasskey.app.ui.screens.settings.subscreens.DriftMergeConflictDialog(
            conflict = mergeConflict,
            onChoice = onChoice,
            onCancel = { if (!submissionInProgress) onMergeConflictCancel() },
            enabled = !submissionInProgress
        )
        return
    }
    if (pending == null) return
    com.keepasskey.app.ui.screens.settings.subscreens.ExternalModificationDialog(
        onChoice = onChoice,
        enabled = !submissionInProgress
    )
}

/**
 * `ISSUE-P2-378` + `ISSUE-P1-631`：外观层挂点——从设置页 ViewModel 订阅三选与合并冲突
 * 两个待决状态并驱动 [ExternalModificationDialogHost]（KeePasskeyApp 仅保留一行调用，
 * 控其函数行数门禁）。裁决结果与三选共用同一稳定提交入口
 * （`SettingsViewModel.submitExternalModificationChoice`，应用级作用域执行）。
 */
@Composable
internal fun DriftDialogsHost(settingsViewModel: SettingsViewModel) {
    val pending by settingsViewModel.externalModificationPending.collectAsStateWithLifecycle()
    val mergeConflict by settingsViewModel.driftMergeConflict.collectAsStateWithLifecycle()
    val submissionInProgress by settingsViewModel.choiceSubmissionInProgress.collectAsStateWithLifecycle()
    ExternalModificationDialogHost(
        pending = pending,
        mergeConflict = mergeConflict,
        submissionInProgress = submissionInProgress,
        onChoice = { settingsViewModel.submitExternalModificationChoice(it) },
        onMergeConflictCancel = { settingsViewModel.cancelDriftMergeConflict() }
    )
}
