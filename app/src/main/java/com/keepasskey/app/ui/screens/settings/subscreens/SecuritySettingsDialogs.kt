package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R

/**
 * 安全设置页剩余的**确认类**弹窗。
 *
 * 本文件原有三个「单值选择弹窗」（自动锁定超时 / 剪贴板清空倒计时 / 最长锁定时长），
 * 已由页内就地 `SecurityChoiceChips` 取代并整体删除——它们把「改一个偏好」变成
 * 「可点行 → 弹窗 → 选中」两次点击外加一次模态打断，而同类设置在本应用内已有
 * 一次点击的形态（TOTP 分段控件 / 外观模式卡片直选）。
 *
 * 保留的 [FlagSecureRiskDialog] 语义**不同**：它不是在选值，而是对「关闭强制防护」这一
 * 高风险动作做二次确认（ISSUE-P2-09 AC①），**必须**保留确认步骤，不得一并就地化。
 */

/**
 * ISSUE-P2-09 验收标准 1：关闭「禁止截屏与录屏」的风险确认（确认后才真正回调关闭）
 */
@Composable
internal fun FlagSecureRiskDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sec_flag_secure_risk_title)) },
        text = {
            Text(
                text = stringResource(R.string.sec_flag_secure_risk_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.sec_flag_secure_risk_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sec_flag_secure_risk_cancel))
            }
        }
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "关闭防截屏风险确认 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "关闭防截屏风险确认 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun FlagSecureRiskDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        FlagSecureRiskDialog(onConfirm = {}, onDismiss = {})
    }
}
