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
val BackgroundLight = Color(0xFFF8F9FC)
val SurfaceLight = Color(0xFFF8F9FC)
val SurfaceContainerLowLight = Color(0xFFF1F4F9)
val SurfaceContainerLight = Color(0xFFEBEFF5)
val SurfaceContainerHighLight = Color(0xFFE5E9EF)
val SurfaceContainerHighestLight = Color(0xFFE0E4EB)
val OutlineLight = Color(0x3374777F)
val OutlineVariantLight = Color(0xFFDCE2EC)

val BackgroundDark = Color(0xFF101418)
val SurfaceDark = Color(0xFF101418)
val SurfaceContainerLowDark = Color(0xFF181C20)
val SurfaceContainerDark = Color(0xFF1D2024)
val SurfaceContainerHighDark = Color(0xFF272A2E)
val SurfaceContainerHighestDark = Color(0xFF32353A)
val OutlineDark = Color(0x408E9199)
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
