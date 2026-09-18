package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 密码库健康度检查二级详情页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onRescanClick: () -> Unit,
    // TASK-47：已泄露密码检测为「显式开关 + 默认关闭」的联网特性，开关与说明同屏呈现
    onBreachCheckToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.health_screen_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 总体健康分仪表卡片
            item {
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
                                progress = { (uiState.healthScore.coerceIn(0, 100)) / 100f },
                                modifier = Modifier.size(96.dp),
                                color = when {
                                    uiState.healthScore >= 80 -> MaterialTheme.colorScheme.primary
                                    uiState.healthScore >= 50 -> MaterialTheme.colorScheme.tertiary
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
                                    text = "${uiState.healthScore}",
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
                            text = stringResource(R.string.health_rating, uiState.healthStatus),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = uiState.healthMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.health_last_scan, uiState.lastHealthScanTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = onRescanClick,
                            enabled = !uiState.isHealthScanning,
                            shape = CircleShape,
                            // ISSUE-P3-132 ③：禁用态补可见边界（共用组件，理由见 ButtonStyles.kt）
                            colors = disabledPrimaryButtonColors(),
                            border = disabledPrimaryButtonBorder(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState.isHealthScanning) {
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

            // 检查项目明细
            item {
                Text(
                    text = stringResource(R.string.health_section_audit),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                // ISSUE-P3-61：未扫描时徽标保持中性「未扫描」——「安全 / 需注意」这类结论
                // 只有真实扫描结果才能支撑；扫描后按实际计数给出对应徽标
                val weakStatus = if (!uiState.hasHealthScanned) {
                    Triple(
                        Icons.Default.Security,
                        MaterialTheme.colorScheme.outline,
                        stringResource(R.string.health_status_not_scanned)
                    )
                } else if (uiState.weakPasswordCount > 0) {
                    Triple(
                        Icons.Default.WarningAmber,
                        securityColors.warning,
                        stringResource(R.string.health_status_warn)
                    )
                } else {
                    Triple(
                        Icons.Default.CheckCircle,
                        securityColors.success,
                        stringResource(R.string.health_status_pass)
                    )
                }
                HealthAuditRowItem(
                    icon = weakStatus.first,
                    iconTint = weakStatus.second,
                    title = stringResource(R.string.health_weak_title),
                    subtitle = stringResource(R.string.health_weak_sub),
                    statusText = weakStatus.third,
                    isWarning = uiState.hasHealthScanned && uiState.weakPasswordCount > 0
                )
            }

            item {
                val reuseStatus = if (!uiState.hasHealthScanned) {
                    Triple(
                        Icons.Default.Security,
                        MaterialTheme.colorScheme.outline,
                        stringResource(R.string.health_status_not_scanned)
                    )
                } else if (uiState.reusedPasswordCount > 0) {
                    Triple(
                        Icons.Default.WarningAmber,
                        securityColors.warning,
                        stringResource(R.string.health_status_warn)
                    )
                } else {
                    Triple(
                        Icons.Default.CheckCircle,
                        securityColors.success,
                        stringResource(R.string.health_status_pass)
                    )
                }
                HealthAuditRowItem(
                    icon = reuseStatus.first,
                    iconTint = reuseStatus.second,
                    title = stringResource(R.string.health_reuse_title),
                    subtitle = stringResource(R.string.health_reuse_sub, uiState.reusedPasswordCount),
                    statusText = reuseStatus.third,
                    isWarning = uiState.hasHealthScanned && uiState.reusedPasswordCount > 0
                )
            }

            // TASK-47：泄露密码审计项——按真实检测状态呈现，绝不以「已防护」掩盖未检测 / 失败
            item {
                val leak = when (uiState.breachCheckStatus) {
                    BreachCheckStatus.DISABLED -> LeakRowPresentation(
                        icon = Icons.Default.Security,
                        iconTint = MaterialTheme.colorScheme.outline,
                        subtitle = stringResource(R.string.health_leak_sub_disabled),
                        statusText = stringResource(R.string.health_leak_status_disabled),
                        isWarning = false
                    )
                    BreachCheckStatus.CHECKING -> LeakRowPresentation(
                        icon = Icons.Default.Security,
                        iconTint = MaterialTheme.colorScheme.primary,
                        subtitle = stringResource(R.string.health_leak_sub_checking),
                        statusText = stringResource(R.string.health_leak_status_checking),
                        isWarning = false
                    )
                    BreachCheckStatus.CLEAN -> LeakRowPresentation(
                        icon = Icons.Default.CheckCircle,
                        iconTint = securityColors.success,
                        subtitle = stringResource(R.string.health_leak_sub_clean),
                        statusText = stringResource(R.string.health_status_safe),
                        isWarning = false
                    )
                    BreachCheckStatus.BREACHED -> LeakRowPresentation(
                        icon = Icons.Default.WarningAmber,
                        iconTint = securityColors.warning,
                        subtitle = stringResource(
                            R.string.health_leak_sub_breached, uiState.compromisedPasswordCount ?: 0
                        ),
                        statusText = stringResource(R.string.health_leak_status_breached),
                        isWarning = true
                    )
                    BreachCheckStatus.FAILED -> LeakRowPresentation(
                        icon = Icons.Default.WarningAmber,
                        iconTint = MaterialTheme.colorScheme.error,
                        subtitle = stringResource(R.string.health_leak_sub_failed, uiState.breachCheckMessage),
                        statusText = stringResource(R.string.health_leak_status_failed),
                        isWarning = true
                    )
                }
                HealthAuditRowItem(
                    icon = leak.icon,
                    iconTint = leak.iconTint,
                    title = stringResource(R.string.health_leak_title),
                    subtitle = leak.subtitle,
                    statusText = leak.statusText,
                    isWarning = leak.isWarning
                )
            }

            // TASK-47：泄露检测开关（默认关闭；联网查询须用户显式开启）
            item {
                BreachCheckToggleRow(
                    enabled = uiState.breachCheckEnabled,
                    onToggle = onBreachCheckToggle,
                    // 开启方向会就地触发扫描（见 onBreachCheckToggle 的接线），
                    // 故本行需要「正在扫描」就地反馈——否则扫到哪儿了只能看页面顶部的按钮
                    isScanning = uiState.isHealthScanning
                )
            }

            // 安全建议卡片
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Security tips",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.health_tips_title),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.health_tips_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "密码库健康度检查页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "密码库健康度检查页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun HealthCheckScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        HealthCheckScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState().copy(
                healthScore = 82,
                // 预览文案与生产资源同为中文，避免预览面板中英混排
                healthStatus = "良好",
                healthMessage = "布局预览专用健康摘要，非真实扫描结果。",
                weakPasswordCount = 2,
                reusedPasswordCount = 1,
                hasHealthScanned = true,
                lastHealthScanTime = "2026-01-02 12:00"
            ),
            onBackClick = {},
            onRescanClick = {},
            onBreachCheckToggle = {}
        )
    }
}
