package com.keepasskey.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 品牌核心主色
val BluePrimaryLight = Color(0xFF00629E)
val BlueOnPrimaryLight = Color(0xFFFFFFFF)
val BluePrimaryContainerLight = Color(0xFFCFE5FF)
val BlueOnPrimaryContainerLight = Color(0xFF001D35)

val BluePrimaryDark = Color(0xFF66B5FF)
val BlueOnPrimaryDark = Color(0xFF003257)
val BluePrimaryContainerDark = Color(0xFF004977)
val BlueOnPrimaryContainerDark = Color(0xFFCFE5FF)

// 表面与背景 (2026 Material 3 Expressive 质感)
// ISSUE-P3-347：相邻档相对亮度差由 2~3% 拉开至 4~6%（实测 ×1.4~1.6），
// 列表卡片（surfaceContainerLow）贴背景不可辨的"层次靠猜"问题由此消除；
// onSurfaceVariant #43474E 对最深档仍保 6.51:1（≥ AC 的 6.0 富余线）。
val BackgroundLight = Color(0xFFF8F9FC)
val SurfaceLight = Color(0xFFF8F9FC)
val SurfaceContainerLowLight = Color(0xFFEEF1F7)
val SurfaceContainerLight = Color(0xFFE4E9F2)
val SurfaceContainerHighLight = Color(0xFFDAE0EC)
val SurfaceContainerHighestLight = Color(0xFFD0D8E7)
// Outline 语义色（ISSUE-P3-132 ①）：原值 `0x3374777F` 为 **20% alpha**，不是 MD3 的
// outline 角色——OutlinedTextField 未聚焦边框 / OutlinedButton 描边 / 次要图标全部取该令牌，
// 浅色下实测栅格化为 `#D8DBE1`，对 `surfaceContainerLow` 仅 **1.26:1**（等于「看不出边界」）。
// 改为不透明 `#74777F`（与 `TextTertiaryLight` 同色相），实测 4.26:1，对齐 MD3 基线
// `#79747E` 对 `#FFFBFE` 的 4.44:1；深色侧同理由 `0x408E9199` 改为不透明 `#8E9199`
// （5.87:1）。`outlineVariant` 维持原值（分隔线角色，实测 1.24:1，仍在 MD3 量级内）。
val OutlineLight = Color(0xFF74777F)
val OutlineVariantLight = Color(0xFFDCE2EC)

// ISSUE-P3-347：深色侧同步拉开（相邻档相对亮度差 ×1.1~2.0），卡片与背景可辨。
val BackgroundDark = Color(0xFF101418)
val SurfaceDark = Color(0xFF101418)
val SurfaceContainerLowDark = Color(0xFF1A1F26)
val SurfaceContainerDark = Color(0xFF21262E)
val SurfaceContainerHighDark = Color(0xFF2A303A)
val SurfaceContainerHighestDark = Color(0xFF343B47)
val OutlineDark = Color(0xFF8E9199)
val OutlineVariantDark = Color(0xFF33373B)

// 文字色彩
val TextPrimaryLight = Color(0xFF191C20)
val TextSecondaryLight = Color(0xFF43474E)
val TextTertiaryLight = Color(0xFF74777F)

val TextPrimaryDark = Color(0xFFE1E2E8)
val TextSecondaryDark = Color(0xFFC3C6CF)
val TextTertiaryDark = Color(0xFF8D9199)

// 语义与专属功能色彩
val PasskeyPurpleLight = Color(0xFF7047EB)
val PasskeyContainerLight = Color(0xFFF0EBFF)
val PasskeyPurpleDark = Color(0xFFB29BFF)
val PasskeyContainerDark = Color(0xFF2C1E57)

val SecuritySuccessLight = Color(0xFF00875A)
val SecuritySuccessDark = Color(0xFF4BE29A)
val SecurityWarningLight = Color(0xFFB27B00)
val SecurityWarningDark = Color(0xFFF5C344)
val SecurityDangerLight = Color(0xFFBA1A1A)
val SecurityDangerDark = Color(0xFFFFB4AB)

val LightColorScheme = lightColorScheme(
    primary = BluePrimaryLight,
    onPrimary = BlueOnPrimaryLight,
    primaryContainer = BluePrimaryContainerLight,
    onPrimaryContainer = BlueOnPrimaryContainerLight,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = SecurityDangerLight,
    onError = Color.White
)

val DarkColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = BlueOnPrimaryDark,
    primaryContainer = BluePrimaryContainerDark,
    onPrimaryContainer = BlueOnPrimaryContainerDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = Color(0xFF0C0F12),
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = SecurityDangerDark,
    onError = Color(0xFF690005)
)

// OLED 极黑优化配色：在标准深色配色基础上，将背景与最底层容器压至纯黑，
// 仅保留必要的容器层次以维持组件可辨识度（OLED 屏幕发光功耗最低）
// ISSUE-P3-347：各档同步等比拉开（纯黑起跳的相邻档差 ×1.9~1.4）。
val OledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF10141A),
    surfaceContainer = Color(0xFF181D24),
    surfaceContainerHigh = Color(0xFF20262F)
)
