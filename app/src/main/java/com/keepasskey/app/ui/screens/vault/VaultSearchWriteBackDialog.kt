package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 「把搜索词写入该条目 URL？」询问对话框（`ISSUE-P3-442` AC①）。
 *
 * 文案面口径（AC②）：对话框里出现的搜索词**恒为已通过 [SearchWriteBackPolicy] 的域名形态词**——
 * 口令形态（`ISSUE-P2-105` 同源红线）根本走不到这里（`shouldOffer` 先拦），
 * 故此处预填 / 回显搜索词**不构成**敏感文本外泄面。
 *
 * 「本次会话不再询问」是**会话级**取舍（勾选后本次列表会话内不再询问任何条目；
 * 离开列表页 / 锁库即随 ViewModel 销毁复位），不落盘——把「这次不要」记成永久拒绝会
 * 静默吞掉将来的匹配纠正入口。
 */
@Composable
internal fun VaultSearchWriteBackDialog(
    prompt: SearchWriteBackPrompt,
    onConfirm: (rememberChoice: Boolean) -> Unit,
    onDismiss: (rememberChoice: Boolean) -> Unit
) {
    var rememberChoice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss(rememberChoice) },
        icon = {
            Icon(imageVector = Icons.Default.Link, contentDescription = null)
        },
        title = {
            Text(text = stringResource(R.string.search_write_back_title))
        },
        text = {
            // 内容槽整体自包 Column（M3 对话框槽内多节点的叠放风险，见工程规则「框架内容槽同为 Box」）
            Column {
                Text(
                    text = stringResource(R.string.search_write_back_message, prompt.proposedUrl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (prompt.currentUrl.isBlank()) {
                        stringResource(R.string.search_write_back_current_none)
                    } else {
                        stringResource(R.string.search_write_back_current, prompt.currentUrl)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.search_write_back_no_ask),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(rememberChoice) }) {
                Text(text = stringResource(R.string.search_write_back_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(rememberChoice) }) {
                Text(text = stringResource(R.string.search_write_back_cancel))
            }
        }
    )
}
