package com.keepasskey.app.ui.screens.database

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 */
@Composable
internal fun VaultRemovalConfirmDialog(
    confirmation: VaultRemovalConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
