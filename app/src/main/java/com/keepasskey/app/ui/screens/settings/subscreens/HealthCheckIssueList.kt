package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.HealthIssueRiskUi
import com.keepasskey.app.ui.screens.settings.HealthIssueUi
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 健康检查「受影响条目」列表段（ISSUE-P3-405）。
 *
 * 自 `HealthCheckComponents.kt` 拆出：原文件因补明细列表触顶 tier1（>500 行），
 * 把明细展示件独立成段，职责边界＝只画「哪些条目有问题」。
 *
 * `BentoCard` 内容槽是 **`Box`**：多个顶层子节点会互相叠放，故全部内容必须包在单一 `Column` 内。
 */
@Composable
internal fun HealthIssueListCard(
    issues: List<HealthIssueUi>,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BentoCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = LocalSecurityColors.current.warning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.health_issue_list_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            issues.forEach { issue ->
                HealthIssueRow(
                    issue = issue,
                    onClick = { onIssueClick(issue.entryId) }
                )
            }
        }
    }
}

/**
 * 单条问题条目行：标题 + 用户名 + 原因 + 风险标签；点击进入条目详情以便修改。
 * 点击区域覆盖整行，避免用户只能靠猜哪里能点。
 */
@Composable
internal fun HealthIssueRow(
    issue: HealthIssueUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    val riskLabel = when (issue.risk) {
        HealthIssueRiskUi.WEAK -> stringResource(R.string.health_issue_risk_weak)
        HealthIssueRiskUi.REUSED -> stringResource(R.string.health_issue_risk_reused)
        HealthIssueRiskUi.EXPIRED -> stringResource(R.string.health_issue_risk_expired)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = issue.title.ifBlank { stringResource(R.string.health_issue_title_fallback) },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (issue.username.isNotBlank()) {
                Text(
                    text = issue.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                text = issue.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(securityColors.warning.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = riskLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = securityColors.warning
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "问题条目列表 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "问题条目列表 - 深色", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun HealthIssueListCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        HealthIssueListCard(
            issues = listOf(
                HealthIssueUi(
                    entryId = "preview-1",
                    title = "示例银行",
                    username = "user@example.com",
                    risk = HealthIssueRiskUi.WEAK,
                    description = "密码过弱（长度: 6，强度评分 1/4）"
                ),
                HealthIssueUi(
                    entryId = "preview-2",
                    title = "示例复用账号",
                    username = "",
                    risk = HealthIssueRiskUi.REUSED,
                    description = "密码在 2 个不同条目中被重复使用"
                )
            ),
            onIssueClick = {}
        )
    }
}
