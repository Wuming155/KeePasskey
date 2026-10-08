package com.keepasskey.app.ui.screens.database

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R

/**
 * 「移除密码库」确认对话框（`ISSUE-P1-241`；§246 自 `DatabasePickerScreen` 下沉，
 * 使该页回到 400 行档沿之下——纯结构性改动，渲染结果逐字不变）。
 *
 * 本件**不**自行判定存储类型：措辞与危险性一律由调用方从
 * [VaultRemovalConfirmation.of] 投影后传入，避免出现「界面自判 / 数据层另判」的第二枚判据
 * （守卫见 `VaultRemovalWiringTest`）。
 *
 * 两类形态由 [VaultRemovalConfirmation.destructive] 驱动（AC②）：
 * - 应用私有库（真删文件、不可恢复）⇒ **红底危险按钮**，正文指名被删文件；
 * - 外部库（只摘登记）⇒ 普通文本按钮，正文承诺「不会删除物理文件」。
 *
 * `ISSUE-P2-529` AC①：应用私有库多出一条**非破坏性出路**「另存副本后移除」
 * （文案取 [VaultRemovalConfirmation.saveCopyRes]）——该按钮与「取消」「永久删除」并列，
 * 用户不再只有「取消 / 删除」两个选项。外部库（[VaultRemovalConfirmation.saveCopyRes] 为空）
 * 不呈现该按钮：它本就不丢数据（只摘登记），多给一个副本出口反而是噪音。
 */
@Composable
internal fun VaultRemovalConfirmDialog(
    confirmation: VaultRemovalConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * AC① 的两份都留出口：先把该库文件另存为同目录副本，**副本落地成功后才**移除原库
     * （副本另存失败则整体失败、原件保留，fail-closed）。
     */
    onSaveCopy: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(confirmation.titleRes)) },
        text = {
            Text(stringResource(confirmation.messageRes, *confirmation.messageArgs.toTypedArray()))
        },
        confirmButton = {
            if (confirmation.destructive) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(confirmation.confirmRes))
                }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(confirmation.confirmRes))
                }
            }
        },
        dismissButton = {
            // 单槽内两枚按钮必须自包 Row（Dialog 按钮槽按 FlowRow 排布，平铺两枚仍是同层兄弟）
            val saveCopyRes = confirmation.saveCopyRes
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (saveCopyRes != null) {
                    TextButton(onClick = onSaveCopy) {
                        Text(stringResource(saveCopyRes))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    )
}
