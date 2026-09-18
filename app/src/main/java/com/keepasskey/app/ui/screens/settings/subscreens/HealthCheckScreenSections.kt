package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors

/**
 * 健康度详情页的**段落组件**（`ISSUE-P3-188` §175 自 [HealthCheckScreen] 逐字搬入，
 * 与 §159 的 `DatabaseSettingsSections` 同形态）。四件段落各自不读 `SettingsUiState`、
 * 不自持状态，只吃渲染所需的窄参数——这既让外层 LazyColumn 的**顺序**一眼可见，
 * 也让单段可被独立预览与断言。
 *
 * 首段是总体健康分仪表卡片。**刻意保留的语义**（勿在后续改动中丢掉）：
 * 1. 仪表盘圆心只放数字分数，英文标签放不进 96dp 圆（用户反馈过 score 错位成 'ore）；
 * 2. 分数→色阶：>=80 primary / >=50 tertiary / 其余 error；
 * 3. 重扫进行中必须**禁用按钮并就地显示进度**（ISSUE-P3-132 ③ 的禁用态可见边界走共用件）。
 */
@Composable
internal fun HealthCheckScoreCard(
    healthScore: Int,
    healthStatus: String,
    healthMessage: String,
    lastScanTime: String,
    isScanning: Boolean,
    onRescanClick: () -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 仪表盘内只放分数：英文 “Overall health score” 放不进 96dp 圆，
            // 强行内嵌会导致换行/重叠（用户反馈 score 错位为 ‘ore）
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                // M3 环形进度：分数映射 0–100，紧迫/良好经色阶表达
                CircularProgressIndicator(
                    progress = { (healthScore.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.size(96.dp),
                    color = when {
                        healthScore >= 80 -> MaterialTheme.colorScheme.primary
                        healthScore >= 50 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                    strokeWidth = 6.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${healthScore}",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 36.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.health_total_score),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.health_rating, healthStatus),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = healthMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.health_last_scan, lastScanTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onRescanClick,
                enabled = !isScanning,
                shape = CircleShape,
                // ISSUE-P3-132 ③：禁用态补可见边界（共用组件，理由见 ButtonStyles.kt）
                colors = disabledPrimaryButtonColors(),
                border = disabledPrimaryButtonBorder(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.health_scanning))
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.health_cd_rescan),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.health_rescan_btn))
                }
            }
        }
    }
}
