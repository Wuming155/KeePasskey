package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillHealthIssue
import com.keepasskey.app.autofill.AutofillHealthReport
import com.keepasskey.app.passkey.CredentialProviderRegistration
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.SystemSettingsNavigation

/**
 * 自动填充服务健康状态卡片（ISSUE-P3-41）。
 *
 * 展示 [AutofillHealthProbe] 的实时探测结果与**可操作的修复指引**，
 * 解决「用户遇到不出候选却无从下手」的排障缺口。首帧（报告为 null）不渲染任何状态文案。
 *
 * 「可操作」的实际含义（对齐 [HealthIssueRow]）：凡问题有**唯一**的系统设置落点者
 * （当前为「系统未选中本应用」），直接给出一次点击的跳转入口；无落点者仍为纯说明文字。
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
                HealthIssueRow(issue = issue)
            }
        }
    }
}

/**
 * 单条健康问题渲染。
 *
 * 可修复项附带**一次点击直达**系统设置的入口：此前卡片只把问题写成一行说明文字
 * （`bodySmall` + 项目符号），而文案却是「请前往系统设置启用」——指了一条用户无法在应用内
 * 执行的路，须自行走完 4~5 层厂商各异的系统设置。现在该项右侧直接给出入口，
 * 把「读一句指引 + 自己找路」收敛为「点一下」。
 *
 * 降级口径：`resolveActivity` 为空（系统不响应该 action）时不渲染入口，只留原纯文案；
 * 启动被 ROM 拦截时同样静默降级——**不得**让排障入口成为新的崩溃点。
 */
@Composable
private fun HealthIssueRow(issue: AutofillHealthIssue) {
    val context = LocalContext.current
    // 有明确且唯一的系统设置落点者给出入口：自动填充服务选择页 / 凭据提供程序页；
    // 其余问题（Manifest 缺声明、开关关闭、字段屏蔽密钥不可用等）没有对应的用户可操作页面，
    // 保持纯文案。
    // ISSUE-P2-239：「系统未登记本应用」与「登记状态未知」两态都指向同一系统页——
    // 前者是修复路径，后者是让用户自行核对（读不到就如实说读不到，不谎报「正常」）。
    val systemSettingsIntent = remember(issue, context) {
        when (issue) {
            AutofillHealthIssue.SYSTEM_NOT_ENABLED -> SystemSettingsNavigation.autofillServiceIntent(context)
            AutofillHealthIssue.CREDENTIAL_PROVIDER_NOT_REGISTERED,
            AutofillHealthIssue.CREDENTIAL_PROVIDER_STATE_UNKNOWN ->
                SystemSettingsNavigation.credentialProviderIntent(context)
            else -> null
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "• " + stringResource(issue.labelRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (systemSettingsIntent != null) {
            TextButton(
                onClick = { SystemSettingsNavigation.launchSafely(context, systemSettingsIntent) }
            ) {
                Text(
                    text = stringResource(R.string.system_settings_open),
                    style = MaterialTheme.typography.labelLarge
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
    // ISSUE-P2-239：CM 通道系统登记的「未登记 / 未知」两态（后者不得并入「正常」）
    AutofillHealthIssue.CREDENTIAL_PROVIDER_NOT_REGISTERED ->
        R.string.autofill_health_issue_cp_not_registered
    AutofillHealthIssue.CREDENTIAL_PROVIDER_STATE_UNKNOWN ->
        R.string.autofill_health_issue_cp_state_unknown
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "自动填充健康卡（全部正常） - 浅色", showBackground = true)
@Preview(
    name = "自动填充健康卡（全部正常） - 深色",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
internal fun AutofillHealthCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AutofillHealthCardContent(
            report = AutofillHealthReport(
                serviceDeclared = true,
                appEnabled = true,
                systemEnabled = true,
                credentialManagerAvailable = true,
                credentialProviderRegistration = CredentialProviderRegistration.REGISTERED
            )
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "自动填充健康卡（存在异常项） - 浅色", showBackground = true)
@Preview(
    name = "自动填充健康卡（存在异常项） - 深色",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
internal fun AutofillHealthCardIssuesPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AutofillHealthCardContent(
            report = AutofillHealthReport(
                serviceDeclared = true,
                appEnabled = false,
                systemEnabled = false,
                credentialManagerAvailable = false,
                // ISSUE-P2-239：异常预览覆盖 CM 通道「未登记」这一新增异常项
                credentialProviderRegistration = CredentialProviderRegistration.NOT_REGISTERED
            )
        )
    }
}
