package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * 高级搜索选项对话框（`ISSUE-P3-439` 的选项面，`ISSUE-P2-544` 改为对话框形态）。
 *
 * 含三段：① 字段范围勾选（FilterChip 行，默认全选＝现行为零变化）；
 * ② 「排除已过期」开关（仅影响应用内搜索展示，与填充链口径独立）；
 * ③ 「区分大小写」开关（默认不敏感＝现状）。正则档**不在范围内**（AC③）。
 *
 * **改动即时生效、无「确认」语义**（沿用 `ISSUE-P3-439` 原面板口径）：本对话框只做选择与回传，
 * 过滤逻辑在 [selectSortedEntries]，持久化在
 * [com.keepasskey.app.data.repository.ExtendedSettingsStore]（不入库数据，AC④）；
 * 底部按钮仅关闭。
 *
 * **为何是对话框而不是页内面板**（`ISSUE-P2-544`，2026-10-08 真机实测取证）：页内面板挂在
 * 列表 `LazyColumn` 的 item 上，用户在**已滚动**的列表里点击入口时，面板被插到当前视口**上方**
 * ——状态确实翻转、界面却毫无变化（真机读数：点后视口内文本零变化，滚回顶部才见面板）。
 * 与「排序 / 扫码」同槽的对话框不受列表滚动位置影响，入口与反馈必然同屏。
 */
@Composable
internal fun VaultSearchAdvancedDialog(
    options: SearchAdvancedOptions,
    onFieldToggle: (SearchField) -> Unit,
    onExcludeExpiredChange: (Boolean) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.search_advanced_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column {
                // ① 字段范围勾选（横向滚动芯片行，避免窄屏截断）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
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
// 默认态（＝现行为零变化：全字段 / 不排除 / 不敏感）
@androidx.compose.ui.tooling.preview.Preview(name = "高级搜索对话框 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "高级搜索对话框 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultSearchAdvancedDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        VaultSearchAdvancedDialog(
            options = SearchAdvancedOptions(),
            onFieldToggle = {},
            onExcludeExpiredChange = {},
            onCaseSensitiveChange = {},
            onDismiss = {}
        )
    }
}

// ISSUE-P3-340 规则：开关须画反向那一态（字段收窄 + 双开）
@androidx.compose.ui.tooling.preview.Preview(name = "高级搜索对话框-收窄双开 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "高级搜索对话框-收窄双开 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultSearchAdvancedDialogNarrowedPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        VaultSearchAdvancedDialog(
            options = SearchAdvancedOptions(
                fields = setOf(SearchField.TITLE, SearchField.USERNAME),
                excludeExpired = true,
                caseSensitive = true
            ),
            onFieldToggle = {},
            onExcludeExpiredChange = {},
            onCaseSensitiveChange = {},
            onDismiss = {}
        )
    }
}
