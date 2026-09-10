package com.keepasskey.app.ui.screens.settings.subscreens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette

/**
 * 外观设置页的主题类分节（1 亮度模式 / 2 调色盘 / 3 防窥遮掩）。
 *
 * ISSUE-P3-31 批次 C：由 `ThemeSettingsScreen.kt`（原 762 行）按**纯结构性拆分**搬出；
 * 列表/搜索/图标集/语言四节见 `ThemeSettingsListSections.kt`。
 * 每个分节仍以「标题 item + 卡片 item」两个 `LazyColumn` item 落位，
 * **item 数量、顺序与内部渲染树逐字保留**，故列表滚动/复用行为与拆分前完全一致。
 */

/** 1. 界面与主题亮度（含 OLED 纯黑与 Material You 动态取色）。 */
internal fun LazyListScope.themeModeSection(
    uiState: SettingsUiState,
    onThemeSelected: (AppThemeMode) -> Unit,
    onOledOptimizationToggle: (Boolean) -> Unit,
    onDynamicColorToggle: (Boolean) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.theme_section_mode)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.theme_mode_pick_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemeSelectionCard(
                        title = stringResource(R.string.theme_mode_light),
                        subtitle = stringResource(R.string.theme_mode_light_sub),
                        icon = Icons.Default.LightMode,
                        isSelected = uiState.themeMode == AppThemeMode.LIGHT,
                        isDarkCard = false,
                        modifier = Modifier.weight(1f),
                        onClick = { onThemeSelected(AppThemeMode.LIGHT) }
                    )

                    ThemeSelectionCard(
                        title = stringResource(R.string.theme_mode_dark),
                        subtitle = stringResource(R.string.theme_mode_dark_sub),
                        icon = Icons.Default.DarkMode,
                        isSelected = uiState.themeMode == AppThemeMode.DARK,
                        isDarkCard = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onThemeSelected(AppThemeMode.DARK) }
                    )

                    ThemeSelectionCard(
                        title = stringResource(R.string.settings_lang_system),
                        subtitle = stringResource(R.string.theme_mode_system_sub),
                        icon = Icons.Default.BrightnessAuto,
                        isSelected = uiState.themeMode == AppThemeMode.SYSTEM,
                        isSplitCard = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onThemeSelected(AppThemeMode.SYSTEM) }
                    )
                }

                AnimatedVisibility(visible = uiState.themeMode == AppThemeMode.DARK) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.theme_oled_title),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.theme_oled_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.oledBlackOptimization,
                            onCheckedChange = onOledOptimizationToggle
                        )
                    }
                }

                // Material You 动态取色（Android 12+）：跟随壁纸取色，覆盖品牌调色盘
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    DisplayPrefRow(
                        title = stringResource(R.string.theme_dynamic_title),
                        subtitle = stringResource(R.string.theme_dynamic_sub),
                        checked = uiState.dynamicColorEnabled,
                        onCheckedChange = onDynamicColorToggle
                    )
                }
            }
        }
    }
}

/** 2. 现代化内置主题调色盘（5 套全色相风格）。 */
internal fun LazyListScope.themePaletteSection(
    uiState: SettingsUiState,
    onPaletteSelected: (AppThemePalette) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.theme_section_palette)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.theme_palette_pick_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AppThemePalette.entries.forEach { palette ->
                    val isSelected = uiState.themePalette == palette
                    ThemePaletteItemCard(
                        palette = palette,
                        isSelected = isSelected,
                        isDarkTheme = uiState.themeMode == AppThemeMode.DARK,
                        onClick = { onPaletteSelected(palette) }
                    )
                }
            }
        }
    }
}

/**
 * 3. 敏感信息防窥保护（KP2A 特性）。
 *
 * ISSUE-P3-17：密码与 TOTP 的**初始**遮掩态已由 `FieldMaskPolicy` 真实消费
 * （`EntryDetailViewModel` → `EntryDetailUiState` → `EntryDetailComponents`），非预留开关。
 */
internal fun LazyListScope.themePeekSection(
    uiState: SettingsUiState,
    onMaskPasswordsDefaultToggle: (Boolean) -> Unit,
    onMaskTotpDefaultToggle: (Boolean) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.theme_section_peek)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DisplayPrefRow(
                    title = stringResource(R.string.theme_mask_pwd_title),
                    subtitle = stringResource(R.string.theme_mask_pwd_sub),
                    checked = uiState.maskPasswordsDefault,
                    onCheckedChange = onMaskPasswordsDefaultToggle
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_mask_totp_title),
                    subtitle = stringResource(R.string.theme_mask_totp_sub),
                    checked = uiState.maskTotpDefault,
                    onCheckedChange = onMaskTotpDefaultToggle
                )
            }
        }
    }
}
