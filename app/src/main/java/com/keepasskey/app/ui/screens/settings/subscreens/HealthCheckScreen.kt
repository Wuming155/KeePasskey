package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.HealthIssueRiskUi
import com.keepasskey.app.ui.screens.settings.HealthIssueUi
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库健康度检查二级详情页（ISSUE-P3-188 拆分：本体只保留脚手架与 LazyColumn 装配）。
 *
 * ISSUE-P3-405：新增 [onEntryClick]——问题条目列表点击后跳转条目详情，用户可就地修改弱/复用/过期密码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onRescanClick: () -> Unit,
    // TASK-47：已泄露密码检测为「显式开关 + 默认关闭」的联网特性，开关与说明同屏呈现
    onBreachCheckToggle: (Boolean) -> Unit,
    // ISSUE-P3-405：问题条目 → 条目详情（entryId；空实现仅供预览与无导航宿主的单测构造点）
    onEntryClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    SettingsSubscreenScaffold(
        titleRes = R.string.settings_health,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        HealthCheckContent(
            uiState = uiState,
            onRescanClick = onRescanClick,
            onBreachCheckToggle = onBreachCheckToggle,
            onEntryClick = onEntryClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
        )
    }
}

/** 审计明细列表体（自 HealthCheckScreen 拆出，ISSUE-P3-382 补重复条目行后单函数超 100 行） */
@Composable
internal fun HealthCheckContent(
    uiState: SettingsUiState,
    onRescanClick: () -> Unit,
    onBreachCheckToggle: (Boolean) -> Unit,
    onEntryClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HealthCheckScoreCard(
                healthScore = uiState.healthScore,
                healthStatus = uiState.healthStatus,
                healthMessage = uiState.healthMessage,
                lastScanTime = uiState.lastHealthScanTime,
                isScanning = uiState.isHealthScanning,
                onRescanClick = onRescanClick
            )
        }
        HealthCheckAuditSectionItems(
            uiState = uiState,
            onEntryClick = onEntryClick,
            onBreachCheckToggle = onBreachCheckToggle
        )
        item { HealthTipsCard() }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * 安全审计段落（弱/复用/过期/重复/泄露/开关）。
 * ISSUE-P3-409 拆分：`HealthCheckContent` 曾达 101 行触发 `long_functions` 红线，审计行装配独立成段。
 */
private fun LazyListScope.HealthCheckAuditSectionItems(
    uiState: SettingsUiState,
    onEntryClick: (String) -> Unit,
    onBreachCheckToggle: (Boolean) -> Unit
) {
    item {
        Text(
            text = stringResource(R.string.health_section_audit),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
    item {
        HealthCountAuditRow(
            title = stringResource(R.string.health_weak_title),
            subtitle = stringResource(R.string.health_weak_sub),
            hasScanned = uiState.hasHealthScanned,
            violationCount = uiState.weakPasswordCount,
            issues = uiState.healthIssues.filter {
                it.risk == HealthIssueRiskUi.WEAK
            },
            onIssueClick = onEntryClick
        )
    }
    item {
        HealthCountAuditRow(
            title = stringResource(R.string.health_reuse_title),
            subtitle = stringResource(R.string.health_reuse_sub, uiState.reusedPasswordCount),
            hasScanned = uiState.hasHealthScanned,
            violationCount = uiState.reusedPasswordCount,
            issues = uiState.healthIssues.filter {
                it.risk == HealthIssueRiskUi.REUSED
            },
            onIssueClick = onEntryClick
        )
    }
    item {
        HealthCountAuditRow(
            title = stringResource(R.string.health_expired_title),
            subtitle = stringResource(R.string.health_expired_sub),
            hasScanned = uiState.hasHealthScanned,
            violationCount = uiState.expiredPasswordCount,
            issues = uiState.healthIssues.filter {
                it.risk == HealthIssueRiskUi.EXPIRED
            },
            onIssueClick = onEntryClick
        )
    }
    // ISSUE-P3-382 / P3-409：库内重复条目只读报告（合并走条目编辑/删除管线）；
    // 明细列表与弱密码同构，点击进条目详情便于清理
    item {
        HealthCountAuditRow(
            title = stringResource(R.string.health_duplicate_title),
            subtitle = if (uiState.hasHealthScanned && uiState.duplicateGroupCount > 0) {
                stringResource(R.string.health_duplicate_sub, uiState.duplicateGroupCount)
            } else if (uiState.hasHealthScanned) {
                stringResource(R.string.health_duplicate_none)
            } else {
                stringResource(R.string.health_duplicate_merge_hint)
            },
            hasScanned = uiState.hasHealthScanned,
            violationCount = if (uiState.hasHealthScanned) uiState.duplicateGroupCount else 0,
            issues = uiState.duplicateIssues,
            onIssueClick = onEntryClick
        )
    }
    item {
        HealthBreachAuditRow(
            status = uiState.breachCheckStatus,
            compromisedCount = uiState.compromisedPasswordCount ?: 0,
            breachMessage = uiState.breachCheckMessage,
            title = stringResource(R.string.health_leak_title)
        )
    }
    item {
        BreachCheckToggleRow(
            enabled = uiState.breachCheckEnabled,
            onToggle = onBreachCheckToggle,
            isScanning = uiState.isHealthScanning
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "密码库健康度检查页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "密码库健康度检查页 - 深色", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun HealthCheckScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        HealthCheckScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState().copy(
                healthScore = 82,
                healthStatus = "良好",
                healthMessage = "布局预览专用健康摘要，非真实扫描结果。",
                weakPasswordCount = 2,
                reusedPasswordCount = 1,
                expiredPasswordCount = 1,
                healthIssues = listOf(
                    HealthIssueUi(
                        entryId = "preview-weak-1",
                        title = "示例银行",
                        username = "user@example.com",
                        risk = HealthIssueRiskUi.WEAK,
                        description = "密码过弱（长度: 6，强度评分 1/4）"
                    ),
                    HealthIssueUi(
                        entryId = "preview-expired-1",
                        title = "示例旧账号",
                        username = "legacy@example.com",
                        risk = HealthIssueRiskUi.EXPIRED,
                        description = "该条目凭据已过期，请更新密码或清除过期标记"
                    )
                ),
                hasHealthScanned = true,
                lastHealthScanTime = "2026-01-02 12:00"
            ),
            onBackClick = {},
            onRescanClick = {},
            onBreachCheckToggle = {}
        )
    }
}
