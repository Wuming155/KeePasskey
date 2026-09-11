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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

@Composable
internal fun RandomModeOptions(
    uiState: GeneratorUiState,
    viewModel: GeneratorViewModel
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

            Slider(
                value = uiState.randomLength.toFloat(),
                onValueChange = { viewModel.setRandomLength(it.toInt()) },
                valueRange = 6f..64f,
                steps = 57,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )

            OptionSwitchRow(title = stringResource(R.string.gen_opt_upper), checked = uiState.useUpper, onCheckedChange = viewModel::setUseUpper)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_lower), checked = uiState.useLower, onCheckedChange = viewModel::setUseLower)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_digits), checked = uiState.useDigits, onCheckedChange = viewModel::setUseDigits)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_symbols), checked = uiState.useSymbols, onCheckedChange = viewModel::setUseSymbols)
            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_exclude_ambiguous),
                subtitle = stringResource(R.string.gen_opt_exclude_ambiguous_sub),
                checked = uiState.excludeAmbiguous,
                onCheckedChange = viewModel::setExcludeAmbiguous
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PassphraseModeOptions(
    uiState: GeneratorUiState,
    viewModel: GeneratorViewModel
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

            Slider(
                value = uiState.wordCount.toFloat(),
                onValueChange = { viewModel.setWordCount(it.toInt()) },
                valueRange = 3f..8f,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )

            Text(
                text = stringResource(R.string.gen_separator_label),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                separators.forEach { sep ->
                    val actualSep = if (sep == spaceSeparator) " " else sep
                    val isSelected = uiState.separator == actualSep
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setSeparator(actualSep) },
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
                onCheckedChange = viewModel::setCapitalizeWords
            )

            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_append_number),
                subtitle = stringResource(R.string.gen_opt_append_number_sub),
                checked = uiState.includeNumberInPassphrase,
                onCheckedChange = viewModel::setIncludeNumberInPassphrase
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaskModeOptions(
    uiState: GeneratorUiState,
    viewModel: GeneratorViewModel
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
                onValueChange = viewModel::setMaskPattern,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { (labelRes, pattern) ->
                    val isSelected = uiState.maskPattern == pattern
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setMaskPattern(pattern) },
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
