package com.keepasskey.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class SecurityColors(
    val passkey: Color,
    val passkeyContainer: Color,
    val success: Color,
    val warning: Color,
    val danger: Color
)

val LocalSecurityColors = staticCompositionLocalOf {
    SecurityColors(
        passkey = PasskeyPurpleLight,
        passkeyContainer = PasskeyContainerLight,
        success = SecuritySuccessLight,
        warning = SecurityWarningLight,
        danger = SecurityDangerLight
    )
}

val LocalThemeMode = compositionLocalOf { AppThemeMode.SYSTEM }
val LocalThemePalette = compositionLocalOf { AppThemePalette.SAPPHIRE }

@Composable
fun KeePasskeyTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    themePalette: AppThemePalette = AppThemePalette.SAPPHIRE,
    oledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val baseColorScheme = if (darkTheme) {
        if (oledBlack) OledDarkColorScheme else DarkColorScheme
    } else {
        LightColorScheme
    }

    // 根据选中的现代化主题色彩风格动态适配完整的 Material 3 调色板
    val colorScheme = if (darkTheme) {
        baseColorScheme.copy(
            primary = themePalette.primaryColorDark,
            onPrimary = themePalette.onPrimaryDark,
            primaryContainer = themePalette.containerColorDark,
            onPrimaryContainer = themePalette.onContainerColorDark,
            secondary = themePalette.secondaryColorDark,
            onSecondary = themePalette.onSecondaryDark,
            secondaryContainer = themePalette.secondaryContainerDark,
            onSecondaryContainer = themePalette.onSecondaryContainerDark,
            tertiary = themePalette.tertiaryColorDark,
            tertiaryContainer = themePalette.tertiaryContainerDark
        )
    } else {
        baseColorScheme.copy(
            primary = themePalette.primaryColorLight,
            onPrimary = themePalette.onPrimaryLight,
            primaryContainer = themePalette.containerColorLight,
            onPrimaryContainer = themePalette.onContainerColorLight,
            secondary = themePalette.secondaryColorLight,
            onSecondary = themePalette.onSecondaryLight,
            secondaryContainer = themePalette.secondaryContainerLight,
            onSecondaryContainer = themePalette.onSecondaryContainerLight,
            tertiary = themePalette.tertiaryColorLight,
            tertiaryContainer = themePalette.tertiaryContainerLight
        )
    }

    val securityColors = if (darkTheme) {
        SecurityColors(
            passkey = PasskeyPurpleDark,
            passkeyContainer = PasskeyContainerDark,
            success = SecuritySuccessDark,
            warning = SecurityWarningDark,
            danger = SecurityDangerDark
        )
    } else {
        SecurityColors(
            passkey = PasskeyPurpleLight,
            passkeyContainer = PasskeyContainerLight,
            success = SecuritySuccessLight,
            warning = SecurityWarningLight,
            danger = SecurityDangerLight
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalThemeMode provides themeMode,
        LocalThemePalette provides themePalette,
        LocalSecurityColors provides securityColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
