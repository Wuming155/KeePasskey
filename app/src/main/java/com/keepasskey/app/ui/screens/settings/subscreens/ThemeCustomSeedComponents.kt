package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.SeedSchemeGenerator

/**
 * 「自定义」卡片的四色联动预览（§280 规模门禁同批自 [ThemeCustomSeedCard] 逐字迁出，
 * 结构性拆分：渲染语义零变化）。已设置时为种子色生成色；未设置时为主题图标引导态。
 */
@Composable
private fun ThemeCustomSeedPreview(previewColors: List<Color>) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(5.dp)
            .height(26.dp),
        contentAlignment = Alignment.Center
    ) {
        if (previewColors.isEmpty()) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                previewColors.forEach { color ->
                    Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}

/**
 * ISSUE-P3-441 AC①②：「自定义」配色来源卡片与种子色选择器。
 *
 * - 卡片形态对齐 [ThemePaletteItemCard]（单选卡 + 四色预览 + 选中角标）；无种子色时为
 *   引导态（点按开选择器），已设置时以生成色预览呈现；
 * - 种子色选择器为**预设色板网格**（16 色内置，复用 [com.keepasskey.app.ui.components.IconPickerDialog]
 *   邻近的网格弹窗形态；不引第三方取色依赖——AC②）；
 * - 选择即上行 `onSelect(argb)`，清除（null）走同一回调——存储层互斥写由仓库原子事务兜底；
 * - 「清除自定义色」入口只在已设置时呈现（删除动作须对象存在）。
 */
@Composable
internal fun ThemeCustomSeedCard(
    seedColor: Long?,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    enabled: Boolean,
    onSelect: (Long?) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    val previewColors: List<Color> = if (seedColor != null) {
        val families = remember(seedColor) { SeedSchemeGenerator.generate(seedColor) }
        listOf(
            if (isDarkTheme) families.primaryDark else families.primaryLight,
            if (isDarkTheme) families.secondaryDark else families.secondaryLight,
            if (isDarkTheme) families.tertiaryDark else families.tertiaryLight,
            if (isDarkTheme) families.primaryContainerDark else families.primaryContainerLight
        )
    } else {
        emptyList()
    }

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surface
            )
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClickLabel = stringResource(R.string.theme_palette_custom)) {
                showPicker = true
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // 四色联动预览（已设置时为生成色；未设置时为主题图标引导态）
            ThemeCustomSeedPreview(previewColors)

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = stringResource(R.string.theme_palette_custom),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.theme_palette_custom_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(22.dp))
        }
    }

    if (showPicker) {
        SeedColorPickerDialog(
            currentSeed = seedColor,
            onSelect = { argb ->
                showPicker = false
                onSelect(argb)
            },
            onClear = {
                showPicker = false
                onSelect(null)
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** 内置预设色板（ISSUE-P3-441 AC②：不引第三方依赖，M3 基准 16 色覆盖常用色相） */
private val SEED_PRESET_COLORS = listOf(
    0xFFE53935, 0xFFEC407A, 0xFFAB47BC, 0xFF7E57C2,
    0xFF5C6BC0, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33,
    0xFFFDD835, 0xFFFB8C00, 0xFFF4511E, 0xFF8D6E63
)

/**
 * 种子色选择弹窗（网格色板；选中态 = primaryContainer 底 + 2dp 描边，对齐 IconPickerDialog 语义）。
 */
@Composable
internal fun SeedColorPickerDialog(
    currentSeed: Long?,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_seed_picker_title)) },
        text = {
            Column {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(SEED_PRESET_COLORS) { argb ->
                        val isSelected = currentSeed == argb
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(argb))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(
                                    role = Role.RadioButton,
                                    onClickLabel = stringResource(R.string.theme_seed_picker_title)
                                ) { onSelect(argb) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        },
        dismissButton = {
            // ISSUE-P3-441：清除入口仅在已设置时呈现（null 上行走 onSelect 同一互斥写链）
            if (currentSeed != null) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.theme_seed_clear))
                }
            }
        }
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "自定义种子色卡片-引导态 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "自定义种子色卡片-引导态 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ThemeCustomSeedCardEmptyPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ThemeCustomSeedCard(
            seedColor = null,
            isSelected = false,
            isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
            enabled = true,
            onSelect = {}
        )
    }
}

// ISSUE-P3-441 AC④：自定义生效态预览（选中态 + 生成色预览；明暗两态）
@androidx.compose.ui.tooling.preview.Preview(name = "自定义种子色卡片-生效 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "自定义种子色卡片-生效 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ThemeCustomSeedCardSelectedPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ThemeCustomSeedCard(
            seedColor = 0xFF00897B,
            isSelected = true,
            isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
            enabled = true,
            onSelect = {}
        )
    }
}

// ISSUE-P3-441 AC④：OLED 纯黑组合——种子色生成色在纯黑深色方案下的观感（深色 + OLED 一态即可代表该组合）
@androidx.compose.ui.tooling.preview.Preview(name = "自定义种子色卡片-生效 - 深色OLED", showBackground = true)
@Composable
internal fun ThemeCustomSeedCardSelectedOledPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme(themeMode = com.keepasskey.app.ui.theme.AppThemeMode.DARK, oledBlack = true) {
        ThemeCustomSeedCard(
            seedColor = 0xFF00897B,
            isSelected = true,
            isDarkTheme = true,
            enabled = true,
            onSelect = {}
        )
    }
}
