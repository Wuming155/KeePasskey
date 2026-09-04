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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.theme.AppThemeMode

/**
 * 外观与主题模式二级设置页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onThemeSelected: (AppThemeMode) -> Unit,
    onOledOptimizationToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "外观与主题",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
            item {
                Text(
                    text = "界面与主题亮度",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFFDD6B20),
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

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Design spec",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "KeePasskey 遵循 Material 3 Expressive 设计规范与人体工学高对比度配色体系，支持毫秒级无感知平滑切肤。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 主题卡片选项组件
 */
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
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    when {
                        isSplitCard -> Brush.horizontalGradient(
                            listOf(Color(0xFFEBEFF5), Color(0xFF1D2024))
                        )
                        isDarkCard -> Brush.linearGradient(
                            listOf(Color(0xFF101418), Color(0xFF1D2024))
                        )
                        else -> Brush.linearGradient(
                            listOf(Color(0xFFF8F9FC), Color(0xFFFFFFFF))
                        )
                    }
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
