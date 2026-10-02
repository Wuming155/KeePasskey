package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.SearchAdvancedOptions
import com.keepasskey.app.ui.screens.settings.SearchField

/**
 * 高级搜索选项面板（ISSUE-P3-439 AC①②）。
 *
 * 形态：搜索顶栏「高级」入口展开的页内面板（非模态），含三段——
 * ① 字段范围勾选（FilterChip 行，默认全选＝现行为零变化；全部勾选时不做「已收窄」强调）；
 * ② 「排除已过期」开关（仅影响应用内搜索展示，与填充链口径独立）；
 * ③ 「区分大小写」开关（默认不敏感＝现状）。
 *
 * 正则档**不在范围内**（AC③：如后续评估须单独立项）。
 * 面板只做选择与回传，过滤逻辑在 [selectSortedEntries]，持久化在
 * [com.keepasskey.app.data.repository.ExtendedSettingsStore]（不入库数据，AC④）。
 */
@Composable
internal fun VaultSearchAdvancedPanel(
    options: SearchAdvancedOptions,
    onFieldToggle: (SearchField) -> Unit,
    onExcludeExpiredChange: (Boolean) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.search_advanced_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ① 字段范围勾选（横向滚动芯片行，避免窄屏截断）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchField.entries.forEach { field ->
                    FilterChip(
                        selected = field in options.fields,
                        onClick = { onFieldToggle(field) },
                        label = { Text(stringResource(field.labelRes), style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            // ② 排除已过期（仅影响应用内搜索展示）
            SearchAdvancedSwitchRow(
                label = stringResource(R.string.search_advanced_exclude_expired),
                checked = options.excludeExpired,
                onChange = onExcludeExpiredChange
            )

            // ③ 大小写档（默认不敏感＝现状）
            SearchAdvancedSwitchRow(
                label = stringResource(R.string.search_advanced_case_sensitive),
                checked = options.caseSensitive,
                onChange = onCaseSensitiveChange
            )
        }
    }
}

@Composable
private fun SearchAdvancedSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// ISSUE-P3-439 AC④：面板展开态预览（收起态为「不渲染本组件」，由列表页默认态承载）
@androidx.compose.ui.tooling.preview.Preview(name = "高级搜索面板 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "高级搜索面板 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultSearchAdvancedPanelPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Column {
            // 默认全选态（＝现行为零变化）
            VaultSearchAdvancedPanel(
                options = SearchAdvancedOptions(),
                onFieldToggle = {},
                onExcludeExpiredChange = {},
                onCaseSensitiveChange = {}
            )
            // 收窄 + 双开态（ISSUE-P3-340 规则：开关须画反向那一态）
            VaultSearchAdvancedPanel(
                options = SearchAdvancedOptions(
                    fields = setOf(SearchField.TITLE, SearchField.USERNAME),
                    excludeExpired = true,
                    caseSensitive = true
                ),
                onFieldToggle = {},
                onExcludeExpiredChange = {},
                onCaseSensitiveChange = {}
            )
        }
    }
}
