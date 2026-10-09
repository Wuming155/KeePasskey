package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R

/**
 * ISSUE-P3-310：过期编辑行——「永不过期」开关；开启后展示日期入口（Material3 日期选择器）。
 * §313 结构性拆分：自 `EntryEditComponents.kt` 搬出（纯搬移，行为零变更）。
 * ISSUE-P3-389：选择器毫秒 ↔ 过期日的双向换算收敛到 `ExpiryDatePickerDates.kt` 的
 * UTC 锚定纯函数（平台 `selectedDateMillis` 契约是所选日的 UTC 零点；旧实现按设备
 * 默认时区双向解释，非 UTC 时区用户双向各偏一天）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ExpiryEditorRow(
    expiresEnabled: Boolean,
    expiryDate: java.time.LocalDate?,
    onToggleExpiry: (Boolean) -> Unit,
    onExpiryDateSelected: (java.time.LocalDate) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember {
        // ISSUE-P3-552（同类）：日期 pattern 收敛为常量，避免同一字面量散落多处
        java.time.format.DateTimeFormatter.ofPattern(com.keepasskey.app.ui.model.RelativeTimeFormatter.PATTERN_ISO_DATE)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.edit_expiry_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (expiresEnabled) {
                    expiryDate?.format(dateFormatter)
                        ?: stringResource(R.string.edit_expiry_pick_date)
                } else {
                    stringResource(R.string.edit_expiry_never)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Switch(
            checked = expiresEnabled,
            onCheckedChange = onToggleExpiry
        )
    }

    if (expiresEnabled) {
        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = expiryDate?.format(dateFormatter)
                    ?: stringResource(R.string.edit_expiry_pick_date)
            )
        }
    }

    if (showDatePicker) {
        // ISSUE-P3-389：双向都走 UTC 锚定纯函数（initialSelectedDateMillis / selectedDateMillis
        // 的平台契约是所选日历日的 UTC 零点毫秒，与设备默认时区无关）。
        val initialMillis = expiryDateAsPickerMillis(expiryDate)
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = initialMillis
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val picked = pickerMillisAsExpiryDate(pickerState.selectedDateMillis)
                        if (picked != null) {
                            onExpiryDateSelected(picked)
                        }
                        showDatePicker = false
                    }
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        ) {
            androidx.compose.material3.DatePicker(state = pickerState)
        }
    }
}
