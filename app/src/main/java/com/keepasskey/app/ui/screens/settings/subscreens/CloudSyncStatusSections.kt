package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * `SyncStatusCard` 的段落组件（`ISSUE-P3-188` §206：宿主文件 325 行、追加段落将贴近 400 行档沿，
 * 按 §192 / §196 的「余量逐案判」口径**另起新文件**；逐字搬动、零行为变更）。
 *
 * - [SyncStatusHeaderRow]：标题 + 连接状态徽章（同步中=primary / 已验证=success / 其余=warning）；
 * - [SyncFeedbackMessage]：同步反馈消息条（null 不渲染）；
 * - [SyncActionButtonsRow]：手动同步与测试连接按钮行（未验证连接时禁用主同步防误触）。
 * 三段均**不自持状态**，取值全部来自 [SettingsUiState] 并经参数回传。
 */

@Composable
internal fun SyncStatusHeaderRow(uiState: SettingsUiState) {
    val securityColors = LocalSecurityColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sync_connection_status),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        val statusVerified = uiState.isConnectionVerified
        val statusColor = when {
            uiState.isSyncing -> MaterialTheme.colorScheme.primary
            statusVerified -> securityColors.success
            else -> securityColors.warning
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.18f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = uiState.syncStatusText.ifEmpty {
                    stringResource(R.string.sync_status_unverified)
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor
            )
        }
    }
}

@Composable
internal fun SyncFeedbackMessage(message: UiMessage?) {
    if (message == null) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.resolveText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun SyncActionButtonsRow(
    isSyncing: Boolean,
    isConnectionVerified: Boolean,
    onTriggerSync: () -> Unit,
    onTestConnection: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onTriggerSync,
            // 未验证连接时禁用主同步，先走「测试连接」防误触
            enabled = !isSyncing && isConnectionVerified,
            shape = RoundedCornerShape(12.dp),
            // ISSUE-P3-132 ③：禁用态补可见边界（共用组件，理由见 ButtonStyles.kt）
            colors = disabledPrimaryButtonColors(),
            border = disabledPrimaryButtonBorder(),
            modifier = Modifier.weight(1f)
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.sync_btn_syncing))
            } else {
                Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.sync_cd_sync_now), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.sync_sync_now_btn))
            }
        }

        OutlinedButton(
            onClick = { onTestConnection() },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.NetworkCheck, contentDescription = stringResource(R.string.sync_cd_test_connection), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.sync_test_connection_btn))
        }
    }
}
