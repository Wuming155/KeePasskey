package com.keepasskey.app.ui.screens.settings.subscreens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.IconSetOption
import com.keepasskey.app.ui.screens.settings.ListDensity
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.AppThemeMode

/**
 * 外观、显示与交互偏好二级设置页 (全面融合 KeePass2Android 显示定制与防肩窥设计)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onThemeSelected: (AppThemeMode) -> Unit,
    onPaletteSelected: (com.keepasskey.app.ui.theme.AppThemePalette) -> Unit = {},
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onOledOptimizationToggle: (Boolean) -> Unit,
    onDynamicColorToggle: (Boolean) -> Unit = {},
    onShowUsernameInList: (Boolean) -> Unit = {},
    onShowOtpInList: (Boolean) -> Unit = {},
    onShowPasskeyBadge: (Boolean) -> Unit = {},
    onShowUrlInList: (Boolean) -> Unit = {},
    onHideFabOnScrollToggle: (Boolean) -> Unit = {},
    onHapticFeedbackToggle: (Boolean) -> Unit = {},
    onShowAuthenticatorTabToggle: (Boolean) -> Unit = {},
    onShowGeneratorTabToggle: (Boolean) -> Unit = {},
    // KP2A 扩展显示操作
    onMaskPasswordsDefaultToggle: (Boolean) -> Unit = {},
    onMaskTotpDefaultToggle: (Boolean) -> Unit = {},
    onShowUnlockedNotificationToggle: (Boolean) -> Unit = {},
    onShowGroupInSearchResultToggle: (Boolean) -> Unit = {},
    onShowGroupInEntryToggle: (Boolean) -> Unit = {},
    onListDensitySelected: (ListDensity) -> Unit = {},
    onAutoActivateSearchOnOpenToggle: (Boolean) -> Unit = {},
    onIconSetSelected: (IconSetOption) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_theme),
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 界面与主题亮度
            item {
                Text(
                    text = stringResource(R.string.theme_section_mode),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

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

            // 2. 现代化内置主题调色盘 (5 套全色相风格)
            item {
                Text(
                    text = stringResource(R.string.theme_section_palette),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

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

                        com.keepasskey.app.ui.theme.AppThemePalette.entries.forEach { palette ->
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

            // 2. 敏感信息防窥保护 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.theme_section_peek),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DisplayPrefRow(
                            title = stringResource(R.string.theme_mask_pwd_title),
                            // ISSUE-P3-17：详情页密码**初始**遮掩态已由 FieldMaskPolicy 真实消费
                            // （EntryDetailViewModel → EntryDetailUiState → EntryDetailComponents）→ 移除标识
                            subtitle = stringResource(R.string.theme_mask_pwd_sub),
                            checked = uiState.maskPasswordsDefault,
                            onCheckedChange = onMaskPasswordsDefaultToggle
                        )

                        DisplayPrefRow(
                            title = stringResource(R.string.theme_mask_totp_title),
                            // ISSUE-P3-17：TOTP **初始**遮掩态已真实消费（同上链路；眼睛按钮可显式展开/收起）
                            subtitle = stringResource(R.string.theme_mask_totp_sub),
                            checked = uiState.maskTotpDefault,
                            onCheckedChange = onMaskTotpDefaultToggle
                        )
                    }
                }
            }

            // 3. 列表展示密度与排版
            item {
                Text(
                    text = stringResource(R.string.theme_section_list),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

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
                                // ISSUE-P3-17：listDensity 已由 ListDensityPresenter/ListDensitySpec 真实驱动
                                // 列表行高、内边距与字号 → 移除标识
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

            // 4. 搜索、分组与常驻通知 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.theme_section_nav_search),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DisplayPrefRow(
                            title = stringResource(R.string.theme_unlocked_notif_title),
                            // ISSUE-P3-18：通知通道 + POST_NOTIFICATIONS + 权限流程已落地，
                            // showUnlockedNotification 真实控制常驻通知的收发 → 移除「（预留，暂未生效）」标识
                            subtitle = stringResource(R.string.theme_unlocked_notif_sub),
                            checked = uiState.showUnlockedNotification,
                            onCheckedChange = onShowUnlockedNotificationToggle
                        )

                        DisplayPrefRow(
                            title = stringResource(R.string.theme_auto_search_title),
                            // ISSUE-P3-17：进库自动聚焦搜索栏已真实消费
                            // （VaultListViewModel 一次性意图 → VaultListTopBars 请求焦点 + 弹输入法）→ 移除标识
                            subtitle = stringResource(R.string.theme_auto_search_sub),
                            checked = uiState.autoActivateSearchOnOpen,
                            onCheckedChange = onAutoActivateSearchOnOpenToggle
                        )

                        DisplayPrefRow(
                            title = stringResource(R.string.theme_group_in_search_title),
                            // ISSUE-P3-17：搜索结果行完整分组路径已真实消费
                            // （VaultListViewModel 仅「搜索中且开关开启」时装配 entryGroupPaths）→ 移除标识
                            subtitle = stringResource(R.string.theme_group_in_search_sub),
                            checked = uiState.showGroupInSearchResult,
                            onCheckedChange = onShowGroupInSearchResultToggle
                        )

                        DisplayPrefRow(
                            title = stringResource(R.string.theme_group_in_entry_title),
                            // ISSUE-P3-17：详情页所属分组路径已真实消费
                            // （EntryDetailViewModel 按 showGroupInEntry 决定是否下发 groupPath）→ 移除标识
                            subtitle = stringResource(R.string.theme_group_in_entry_sub),
                            checked = uiState.showGroupInEntry,
                            onCheckedChange = onShowGroupInEntryToggle
                        )
                    }
                }
            }

            // 5. 图标风格集 (KP2A 特性)
            item {
                Text(
                    text = stringResource(R.string.theme_section_iconset),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconSetOption.entries.forEach { option ->
                            val isSelected = uiState.iconSet == option
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onIconSetSelected(option) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onIconSetSelected(option) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(option.labelRes),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(option.descRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. 界面语言设置 (i18n)
            item {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

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
                            val isSelected = uiState.appLanguage == lang
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onLanguageSelected(lang) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onLanguageSelected(lang) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DisplayPrefRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun ThemeSelectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    isDarkCard: Boolean = false,
    isSplitCard: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .then(
                        when {
                            isSplitCard -> Modifier.background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFE2E8F0), Color(0xFF1E293B))
                                )
                            )
                            isDarkCard -> Modifier.background(Color(0xFF0F172A))
                            else -> Modifier.background(Color(0xFFF1F5F9))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDarkCard) Color(0xFF38BDF8) else if (isSplitCard) Color(0xFF818CF8) else Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isSelected) {
                Spacer(modifier = Modifier.height(4.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun ThemePaletteItemCard(
    palette: com.keepasskey.app.ui.theme.AppThemePalette,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val primaryColor = if (isDarkTheme) palette.primaryColorDark else palette.primaryColorLight
    val secondaryColor = if (isDarkTheme) palette.secondaryColorDark else palette.secondaryColorLight
    val tertiaryColor = if (isDarkTheme) palette.tertiaryColorDark else palette.tertiaryColorLight
    val containerColor = if (isDarkTheme) palette.containerColorDark else palette.containerColorLight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surface
            )
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // 四色联动调色盘预览 (Primary, Secondary, Tertiary, Container)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(primaryColor))
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(secondaryColor))
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(tertiaryColor))
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(containerColor))
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = stringResource(palette.titleRes),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(palette.subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        RadioButton(
            selected = isSelected,
            onClick = onClick
        )
    }
}
