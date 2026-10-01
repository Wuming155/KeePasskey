package com.keepasskey.app.ui.screens.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 生成型密钥文件的一次性保存提示（ISSUE-P3-21 验收 2）。
 *
 * 该密钥文件是复合密钥的第二因子：**不保存即永久无法解锁**（会话锁定后内存副本立即清零，
 * 且该文件不会被再次生成）。因此：
 * - 主按钮直达 SAF 另存为（写盘复用既有导出通道 `exportKeyFileBytes`）；
 * - 次按钮文案如实写出后果，不提供「假装已保存」的第三条路径；
 * - 点击弹窗外部不关闭（`onDismissRequest` 不做任何事），杜绝误触导致第二因子静默丢失。
 */
@Composable
internal fun KeyFileOneTimeSaveDialog(
    suggestedFileName: String,
    onSaveClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 必须显式选择：误触外部若静默关闭，用户将永久失去该密码库的第二因子
        },
        // 对话框窗口的 FLAG_SECURE 由 Compose 的 SecureFlagPolicy 决定（默认 Inherit ← **宿主窗口**），
        // 宿主不带该 flag 时 Inherit 会清掉本窗的 flag（ISSUE-P2-246 真机实测）；故显式要求 SecureOn
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                text = stringResource(R.string.db_picker_keyfile_backup_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 对话框由 Compose 创建**独立窗口**，Activity 的 FLAG_SECURE 不会传播过来
                // （官方："You must set FLAG_SECURE explicitly for every window created by the
                // activity, including dialogs."）。本窗展示一次性密钥文件保存提示，属敏感面。
                com.keepasskey.app.security.SecureDialogWindowEffect()
                Text(
                    text = stringResource(R.string.db_picker_keyfile_backup_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = suggestedFileName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onSaveClick, shape = CapsuleShape) {
                Text(stringResource(R.string.db_picker_keyfile_backup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkipClick) {
                Text(stringResource(R.string.db_picker_keyfile_backup_skip))
            }
        }
    )
}
