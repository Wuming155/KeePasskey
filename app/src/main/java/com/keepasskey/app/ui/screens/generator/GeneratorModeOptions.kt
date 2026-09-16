package com.keepasskey.app.ui.screens.generator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.keepasskey.app.ui.components.ValueSlider

@Composable
internal fun RandomModeOptions(
    uiState: GeneratorUiState,
    actions: GeneratorActions
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.gen_password_length, uiState.randomLength),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            ValueSlider(
                value = uiState.randomLength,
                onValueChange = actions::setRandomLength,
                valueRange = 6..64
            )

            OptionSwitchRow(title = stringResource(R.string.gen_opt_upper), checked = uiState.useUpper, onCheckedChange = actions::setUseUpper)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_lower), checked = uiState.useLower, onCheckedChange = actions::setUseLower)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_digits), checked = uiState.useDigits, onCheckedChange = actions::setUseDigits)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_symbols), checked = uiState.useSymbols, onCheckedChange = actions::setUseSymbols)
            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_exclude_ambiguous),
                subtitle = stringResource(R.string.gen_opt_exclude_ambiguous_sub),
                checked = uiState.excludeAmbiguous,
                onCheckedChange = actions::setExcludeAmbiguous
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PassphraseModeOptions(
    uiState: GeneratorUiState,
    actions: GeneratorActions
) {
    val spaceSeparator = stringResource(R.string.gen_separator_space)
    val separators = listOf("-", "_", spaceSeparator, ".", "/")

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.gen_word_count, uiState.wordCount),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            ValueSlider(
                value = uiState.wordCount,
                onValueChange = actions::setWordCount,
                valueRange = 3..8
            )

            Text(
                text = stringResource(R.string.gen_separator_label),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                separators.forEach { sep ->
                    val actualSep = if (sep == spaceSeparator) " " else sep
                    val isSelected = uiState.separator == actualSep
                    FilterChip(
                        selected = isSelected,
                        onClick = { actions.setSeparator(actualSep) },
                        label = { Text(sep) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_capitalize),
                subtitle = stringResource(R.string.gen_opt_capitalize_sub),
                checked = uiState.capitalizeWords,
                onCheckedChange = actions::setCapitalizeWords
            )

            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_append_number),
                subtitle = stringResource(R.string.gen_opt_append_number_sub),
                checked = uiState.includeNumberInPassphrase,
                onCheckedChange = actions::setIncludeNumberInPassphrase
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaskModeOptions(
    uiState: GeneratorUiState,
    actions: GeneratorActions
) {
    val presets: List<Pair<Int, String>> = listOf(
        Pair(R.string.gen_mask_preset_pin, "dddddd"),
        Pair(R.string.gen_mask_preset_serial, "xxxx-xxxx-xxxx-xxxx"),
        Pair(R.string.gen_mask_preset_upper_digits, "uuuu-dddd-uuuu-dddd"),
        Pair(R.string.gen_mask_preset_mixed, "uull-ddss-uull")
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.gen_mask_rules),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = uiState.maskPattern,
                onValueChange = actions::setMaskPattern,
                label = { Text(stringResource(R.string.gen_mask_format)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.gen_mask_presets_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (labelRes, pattern) ->
                    val isSelected = uiState.maskPattern == pattern
                    FilterChip(
                        selected = isSelected,
                        onClick = { actions.setMaskPattern(pattern) },
                        label = { Text(stringResource(labelRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun OptionSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：三个模式参数区块均以 (GeneratorUiState, GeneratorViewModel) 为入参（ViewModel 无法在预览中构造），
// 故预览本文件中可独立渲染的无状态组件 OptionSwitchRow
// 为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "密码生成选项开关行 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "密码生成选项开关行 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun GeneratorModeOptionsPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        OptionSwitchRow(
            title = "排除易混淆字符",
            subtitle = "Skip look-alike characters (0/O, 1/l, …)",
            checked = true,
            onCheckedChange = {}
        )
    }
}
