package com.keepasskey.app.ui.screens.unlock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 解锁页的小型独立组件（`ISSUE-P2-645`：自 `UnlockScreen.kt` 拆分，纯搬运）。
 *
 * 状态胶囊与降级确认弹窗都不持有业务状态、只吃参数与资源文案，故独立成文件；
 * 解锁主链路的组合顺序与语义未变。
 */
/**
 * §436：品牌区状态胶囊（原型形态）——primary 圆点 + 副标题文案，
 * surfaceContainer 底 + outlineVariant 描边的胶囊容器。
 */
@Composable
internal fun UnlockStatusPill(subtitle: String) {
    Surface(
        shape = CapsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * ISSUE-P1-22：软件级 Keystore 快速解锁降级确认弹窗（AC②：建立封印前给出明确风险提示，
 * 用户显式选择后方可封印；取消 / 关闭弹窗一律按「不启用」处理，fail-closed 不留确认记录）。
 */
@Composable
internal fun QuickUnlockDowngradeConsentDialog(
    onConfirm: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(text = stringResource(R.string.unlock_downgrade_dialog_title)) },
        text = { Text(text = stringResource(R.string.unlock_downgrade_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.unlock_downgrade_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(text = stringResource(R.string.unlock_downgrade_decline))
            }
        }
    )
}
