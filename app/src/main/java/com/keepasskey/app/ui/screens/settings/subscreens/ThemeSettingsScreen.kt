package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.material.icons.filled.DensityMedium
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.unit.sp
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
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onOledOptimizationToggle: (Boolean) -> Unit,
    onShowUsernameInList: (Boolean) -> Unit = {},
    onShowOtpInList: (Boolean) -> Unit = {},
    onShowPasskeyBadge: (Boolean) -> Unit = {},
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
                    text = "界面色彩与主题风格",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "选择您偏好的色彩风格，在不同环境光线下均保持舒适的对比度：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ThemeSelectionCard(
                                title = "浅色模式",
                                subtitle = "清爽纯净",
                                icon = Icons.Default.LightMode,
                                isSelected = uiState.themeMode == AppThemeMode.LIGHT,
                                isDarkCard = false,
                                modifier = Modifier.weight(1f),
                                onClick = { onThemeSelected(AppThemeMode.LIGHT) }
                            )

                            ThemeSelectionCard(
                                title = "深色模式",
                                subtitle = "沉浸护眼",
                                icon = Icons.Default.DarkMode,
                                isSelected = uiState.themeMode == AppThemeMode.DARK,
                                isDarkCard = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onThemeSelected(AppThemeMode.DARK) }
                            )

                            ThemeSelectionCard(
                                title = "跟随系统",
                                subtitle = "昼夜自适应",
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
                                        text = "OLED 极黑优化",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "将背景切换为深邃纯黑，大幅减少屏幕发光功耗",
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
                    }
                }
            }

            // 2. 敏感信息防窥保护 (KP2A 特性)
            item {
                Text(
                    text = "防肩窥与敏感信息遮掩 (KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DisplayPrefRow(
                            title = "条目详情默认遮掩密码 (Mask Passwords)",
                            subtitle = "进入条目时默认以 •••••••• 隐藏密码字符，点击眼睛图标才揭示",
                            checked = uiState.maskPasswordsDefault,
                            onCheckedChange = onMaskPasswordsDefaultToggle
                        )

                        DisplayPrefRow(
                            title = "默认遮掩 TOTP 动态验证码",
                            subtitle = "隐藏条目详情中的 6 位动态验证码，防止旁观者偷看",
                            checked = uiState.maskTotpDefault,
                            onCheckedChange = onMaskTotpDefaultToggle
                        )
                    }
                }
            }

            // 3. 列表展示密度与排版
            item {
                Text(
                    text = "列表视图排版与内容 (KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            Text(
                                text = "条目列表紧凑度 (List Density)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "调节条目行高与字体大小，适配不同屏幕尺寸与阅读偏好",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ListDensity.entries.forEach { density ->
                                    FilterChip(
                                        selected = uiState.listDensity == density,
                                        onClick = { onListDensitySelected(density) },
                                        label = { Text(density.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }

                        DisplayPrefRow(
                            title = "在条目列表中显示用户名",
                            subtitle = "关闭后条目行仅展示标题，信息更精简",
                            checked = uiState.showUsernameInList,
                            onCheckedChange = onShowUsernameInList
                        )

                        DisplayPrefRow(
                            title = "在条目列表中显示 OTP 动态码",
                            subtitle = "在条目右侧直接展示动态码与倒计时圈，免进详情",
                            checked = uiState.showOtpInList,
                            onCheckedChange = onShowOtpInList
                        )

                        DisplayPrefRow(
                            title = "在条目列表中标注 Passkey 徽标",
                            subtitle = "为具备通行密钥的条目渲染紫色 Passkey 胶囊标识",
                            checked = uiState.showPasskeyBadge,
                            onCheckedChange = onShowPasskeyBadge
                        )
                    }
                }
            }

            // 4. 搜索、分组与常驻通知 (KP2A 特性)
            item {
                Text(
                    text = "导航、搜索与通知协同 (KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DisplayPrefRow(
                            title = "显示已解锁常驻通知 (Unlocked Notification)",
                            subtitle = "密码库解锁后在系统状态栏显示常驻快捷方式，点击一键锁定或切回",
                            checked = uiState.showUnlockedNotification,
                            onCheckedChange = onShowUnlockedNotificationToggle
                        )

                        DisplayPrefRow(
                            title = "打开数据库后自动激活搜索栏",
                            subtitle = "解锁成功后自动聚焦搜索栏并弹出键盘，极速定位目标凭证",
                            checked = uiState.autoActivateSearchOnOpen,
                            onCheckedChange = onAutoActivateSearchOnOpenToggle
                        )

                        DisplayPrefRow(
                            title = "全局搜索结果中显示完整分组路径",
                            subtitle = "在匹配结果副标题中完整显示父级文件夹结构 (如：/工作/开发/GitHub)",
                            checked = uiState.showGroupInSearchResult,
                            onCheckedChange = onShowGroupInSearchResultToggle
                        )

                        DisplayPrefRow(
                            title = "条目详情页展示所属群组",
                            subtitle = "在条目详情卡片顶部标识其在密码库结构中所处的分组名",
                            checked = uiState.showGroupInEntry,
                            onCheckedChange = onShowGroupInEntryToggle
                        )
                    }
                }
            }

            // 5. 图标风格集 (KP2A 特性)
            item {
                Text(
                    text = "图标集风格 (Icon Set - KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
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
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = option.desc,
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
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(AppLanguage.SYSTEM, stringResource(R.string.settings_lang_system), "跟随操作系统语言设置"),
                            Triple(AppLanguage.ZH_CN, stringResource(R.string.settings_lang_zh), "强制简体中文界面"),
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
