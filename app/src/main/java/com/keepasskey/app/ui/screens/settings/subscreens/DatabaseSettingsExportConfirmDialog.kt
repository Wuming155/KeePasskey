package com.keepasskey.app.ui.screens.settings.subscreens

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.ExportArtifactKind
import com.keepasskey.app.ui.screens.settings.ExportConfirmationPolicy
import com.keepasskey.app.ui.screens.settings.ExportTicket
import com.keepasskey.app.ui.screens.settings.SafDocumentCleanup

/**
 * 明文类导出（整库 XML / 通用 CSV / 会话绑定密钥文件）的**二次确认对话框**。
 *
 * 来源：`DatabaseSettingsScreen` 内三处同形实现（对话框 6b / 6c / 6d，分属
 * ISSUE-P2-10 (ZT-15) / ISSUE-P3-73 / ISSUE-P3-128）在本批收敛为一份（ISSUE-P3-188 剩余清单第 1 项）。
 * 三处原实现的差异只有**三项**：标题与正文文案、[artifactKind]、确认后的回调 [onConfirmed]；
 * 其余安全语义**逐字保持**：
 *
 * 1. **取消即清理**（ISSUE-P2-20）：SAF `CreateDocument` 已创建的空目标文档必须删除，不留 0 字节残留；
 * 2. **确认后才签发令牌**（ISSUE-P3-110）：`ExportTicket` 只能由 [ExportConfirmationPolicy.confirm]
 *    产出，且它是导出控制器入口的**必填参数** ⇒ UI 无法绕过确认直接调用；
 * 3. **fail-closed**：目标为 null 或令牌为 null 时**一律不调用**导出回调；
 * 4. **待确认目标由调用方持有**：本对话框只消费 [targetUri] 并经 [onCancel] / [onTargetConsumed]
 *    回写关闭与清空，保持「先捕获目标、再复位状态、最后放行」的原顺序。
 *
 * @param onCancel 关闭确认态（调用方置 `show…Confirm = false`）
 * @param onTargetConsumed 清空待确认目标（调用方置 `pending…Uri = null`）
 */
@Composable
internal fun ExportConfirmationDialog(
    titleResId: Int,
    messageResId: Int,
    artifactKind: ExportArtifactKind,
    targetUri: Uri?,
    onCancel: () -> Unit,
    onTargetConsumed: () -> Unit,
    onConfirmed: (Uri, ExportTicket) -> Unit
) {
    val localContext = LocalContext.current

    fun cleanupCancelledTarget() {
        targetUri?.let { SafDocumentCleanup.deleteCreatedDocument(localContext, it) }
        onCancel()
        onTargetConsumed()
    }

    AlertDialog(
        onDismissRequest = { cleanupCancelledTarget() },
        title = { Text(stringResource(titleResId)) },
        text = {
            Text(
                text = stringResource(messageResId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val target = targetUri
                onCancel()
                onTargetConsumed()
                val ticket = ExportConfirmationPolicy.confirm(
                    kind = artifactKind,
                    userConfirmed = true
                )
                if (target != null && ticket != null) onConfirmed(target, ticket)
            }) {
                Text(stringResource(R.string.dbset_export_plain_warn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { cleanupCancelledTarget() }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
