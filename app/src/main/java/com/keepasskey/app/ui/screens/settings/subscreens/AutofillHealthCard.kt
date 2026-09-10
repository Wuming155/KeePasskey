package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillHealthIssue
import com.keepasskey.app.ui.components.BentoCard

/**
 * 自动填充服务健康状态卡片（ISSUE-P3-41）。
 *
 * 展示 [AutofillHealthProbe] 的实时探测结果与**可操作的修复指引**，
 * 解决「用户遇到不出候选却无从下手」的排障缺口。首帧（报告为 null）不渲染任何状态文案。
 */
@Composable
fun AutofillHealthCard(
    appEnabled: Boolean,
    modifier: Modifier = Modifier,
    viewModel: AutofillHealthViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsStateWithLifecycle()

    // 应用内开关变化即重新探测（系统侧状态变化在进入页面时重新采集）
    LaunchedEffect(appEnabled) {
        viewModel.refresh(appEnabled)
    }

    val current = report ?: return
    val healthy = current.isFullyOperational

    BentoCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (healthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (healthy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(
                        if (healthy) R.string.autofill_health_ok else R.string.autofill_health_attention
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            current.issues.forEach { issue ->
                Text(
                    text = "• " + stringResource(issue.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@StringRes
private fun AutofillHealthIssue.labelRes(): Int = when (this) {
    AutofillHealthIssue.SERVICE_NOT_DECLARED -> R.string.autofill_health_issue_service_missing
    AutofillHealthIssue.APP_DISABLED -> R.string.autofill_health_issue_app_disabled
    AutofillHealthIssue.SYSTEM_NOT_ENABLED -> R.string.autofill_health_issue_system_disabled
    AutofillHealthIssue.CREDENTIAL_MANAGER_UNAVAILABLE -> R.string.autofill_health_issue_cm_unavailable
}
