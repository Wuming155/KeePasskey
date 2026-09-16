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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillHealthIssue
import com.keepasskey.app.autofill.AutofillHealthReport
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

    AutofillHealthCardContent(report = report, modifier = modifier)
}

/**
 * 健康卡片**无状态渲染本体**：只负责把一份报告画出来，不持有 ViewModel、不触发探测。
 *
 * 拆分理由（纯结构性，行为逐字等价）：有状态入口 [AutofillHealthCard] 依赖
 * `hiltViewModel()`，在 IDE 预览面板中无法独立渲染——抽出本函数后，预览可传入构造好的
 * [AutofillHealthReport] 覆盖「健康 / 有异常」两条渲染分支，而生产路径（含首帧
 * `report == null` 不渲染任何文案的既有语义）完全不变。
 */
@Composable
private fun AutofillHealthCardContent(
    report: AutofillHealthReport?,
    modifier: Modifier = Modifier
) {
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
    // ISSUE-P3-113：字段屏蔽签名密钥不可用（fail-closed 的静默故障出口）
    AutofillHealthIssue.FIELD_BLOCK_SIGNATURE_UNAVAILABLE ->
        R.string.autofill_health_issue_field_signature_unavailable
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "自动填充健康卡（全部正常） - 浅色", showBackground = true)
@Preview(
    name = "自动填充健康卡（全部正常） - 深色",
    showBackground = true,
    uiMode = 0x20 /* UI_MODE_NIGHT_YES */
)
@Composable
internal fun AutofillHealthCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AutofillHealthCardContent(
            report = AutofillHealthReport(
                serviceDeclared = true,
                appEnabled = true,
                systemEnabled = true,
                credentialManagerAvailable = true
            )
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "自动填充健康卡（存在异常项） - 浅色", showBackground = true)
@Preview(
    name = "自动填充健康卡（存在异常项） - 深色",
    showBackground = true,
    uiMode = 0x20 /* UI_MODE_NIGHT_YES */
)
@Composable
internal fun AutofillHealthCardIssuesPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AutofillHealthCardContent(
            report = AutofillHealthReport(
                serviceDeclared = true,
                appEnabled = false,
                systemEnabled = false,
                credentialManagerAvailable = false
            )
        )
    }
}
