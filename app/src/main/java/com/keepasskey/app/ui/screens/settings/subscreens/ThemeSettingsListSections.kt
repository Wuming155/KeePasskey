package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.IconSetOption
import com.keepasskey.app.ui.screens.settings.ListDensity
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 外观设置页的列表与导航类分节（4 列表排版 / 5 搜索与通知 / 6 图标集 / 7 界面语言）。
 *
 * ISSUE-P3-31 批次 C：由 `ThemeSettingsScreen.kt`（原 762 行）按**纯结构性拆分**搬出；
 * 主题亮度 / 调色盘 / 防窥三节见 `ThemeSettingsSections.kt`。
 * item 数量、顺序与内部渲染树逐字保留。
 */

/**
 * 4. 列表展示密度与排版（含底部导航栏页签开关）。
 *
 * ISSUE-P3-17：`listDensity` 已由 `ListDensityPresenter`/`ListDensitySpec` 真实驱动行高、内边距与字号。
 */
internal fun LazyListScope.themeListSection(
    uiState: SettingsUiState,
    onListDensitySelected: (ListDensity) -> Unit,
    onShowUsernameInList: (Boolean) -> Unit,
    onShowOtpInList: (Boolean) -> Unit,
    onShowPasskeyBadge: (Boolean) -> Unit,
    onShowUrlInList: (Boolean) -> Unit,
    onHideFabOnScrollToggle: (Boolean) -> Unit,
    onHapticFeedbackToggle: (Boolean) -> Unit,
    onShowAuthenticatorTabToggle: (Boolean) -> Unit,
    onShowGeneratorTabToggle: (Boolean) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.theme_section_list)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text(
                        text = stringResource(R.string.theme_density_title),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.theme_density_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListDensity.entries.forEach { density ->
                            FilterChip(
                                selected = uiState.listDensity == density,
                                onClick = { onListDensitySelected(density) },
                                label = { Text(stringResource(density.labelRes)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                DisplayPrefRow(
                    title = stringResource(R.string.theme_show_username_title),
                    subtitle = stringResource(R.string.theme_show_username_sub),
                    checked = uiState.showUsernameInList,
                    onCheckedChange = onShowUsernameInList
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_show_otp_title),
                    subtitle = stringResource(R.string.theme_show_otp_sub),
                    checked = uiState.showOtpInList,
                    onCheckedChange = onShowOtpInList
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_show_passkey_title),
                    subtitle = stringResource(R.string.theme_show_passkey_sub),
                    checked = uiState.showPasskeyBadge,
                    onCheckedChange = onShowPasskeyBadge
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_show_url_title),
                    subtitle = stringResource(R.string.theme_show_url_sub),
                    checked = uiState.showUrlInList,
                    onCheckedChange = onShowUrlInList
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_hide_fab_title),
                    subtitle = stringResource(R.string.theme_hide_fab_sub),
                    checked = uiState.hideFabOnScroll,
                    onCheckedChange = onHideFabOnScrollToggle
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_haptic_title),
                    subtitle = stringResource(R.string.theme_haptic_sub),
                    checked = uiState.hapticFeedbackEnabled,
                    onCheckedChange = onHapticFeedbackToggle
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Text(
                    text = stringResource(R.string.theme_section_navbar),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_show_auth_tab_title),
                    subtitle = stringResource(R.string.theme_show_auth_tab_sub),
                    checked = uiState.showAuthenticatorTab,
                    onCheckedChange = onShowAuthenticatorTabToggle
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_show_gen_tab_title),
                    subtitle = stringResource(R.string.theme_show_gen_tab_sub),
                    checked = uiState.showGeneratorTab,
                    onCheckedChange = onShowGeneratorTabToggle
                )
            }
        }
    }
}

/**
 * 5. 搜索、分组与常驻通知（KP2A 特性）。
 *
 * ISSUE-P3-18：`showUnlockedNotification` 真实控制常驻通知的收发（通知通道 + POST_NOTIFICATIONS 已落地）。
 * ISSUE-P3-17：自动聚焦搜索、搜索结果分组路径与详情页分组路径均已真实消费。
 */
internal fun LazyListScope.themeNavSearchSection(
    uiState: SettingsUiState,
    onShowUnlockedNotificationToggle: (Boolean) -> Unit,
    onAutoActivateSearchOnOpenToggle: (Boolean) -> Unit,
    onShowGroupInSearchResultToggle: (Boolean) -> Unit,
    onShowGroupInEntryToggle: (Boolean) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.theme_section_nav_search)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DisplayPrefRow(
                    title = stringResource(R.string.theme_unlocked_notif_title),
                    subtitle = stringResource(R.string.theme_unlocked_notif_sub),
                    checked = uiState.showUnlockedNotification,
                    onCheckedChange = onShowUnlockedNotificationToggle
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_auto_search_title),
                    subtitle = stringResource(R.string.theme_auto_search_sub),
                    checked = uiState.autoActivateSearchOnOpen,
                    onCheckedChange = onAutoActivateSearchOnOpenToggle
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_group_in_search_title),
                    subtitle = stringResource(R.string.theme_group_in_search_sub),
                    checked = uiState.showGroupInSearchResult,
                    onCheckedChange = onShowGroupInSearchResultToggle
                )

                DisplayPrefRow(
                    title = stringResource(R.string.theme_group_in_entry_title),
                    subtitle = stringResource(R.string.theme_group_in_entry_sub),
                    checked = uiState.showGroupInEntry,
                    onCheckedChange = onShowGroupInEntryToggle
                )
            }
        }
    }
}

/** 6. 图标风格集（KP2A 特性）。 */
internal fun LazyListScope.themeIconSetSection(
    uiState: SettingsUiState,
    onIconSetSelected: (IconSetOption) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.theme_section_iconset)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconSetOption.entries.forEach { option ->
                    ThemeRadioOptionRow(
                        label = stringResource(option.labelRes),
                        description = stringResource(option.descRes),
                        isSelected = uiState.iconSet == option,
                        cornerRadius = 8,
                        verticalPadding = 6,
                        onClick = { onIconSetSelected(option) }
                    )
                }
            }
        }
    }
}

/** 7. 界面语言设置（i18n）。 */
internal fun LazyListScope.themeLanguageSection(
    uiState: SettingsUiState,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    item { ThemeSectionTitle(stringResource(R.string.settings_language)) }

    item {
        BentoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(AppLanguage.SYSTEM, stringResource(R.string.settings_lang_system), stringResource(R.string.theme_lang_system_desc)),
                    Triple(AppLanguage.ZH_CN, stringResource(R.string.settings_lang_zh), stringResource(R.string.theme_lang_zh_desc)),
                    Triple(AppLanguage.EN_US, stringResource(R.string.settings_lang_en), "English User Interface")
                ).forEach { (lang, label, desc) ->
                    ThemeRadioOptionRow(
                        label = label,
                        description = desc,
                        isSelected = uiState.appLanguage == lang,
                        cornerRadius = 10,
                        verticalPadding = 8,
                        onClick = { onLanguageSelected(lang) }
                    )
                }
            }
        }
    }
}
