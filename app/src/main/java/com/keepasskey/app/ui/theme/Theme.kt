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
import androidx.compose.ui.graphics.toArgb
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

@Composable
fun KeePasskeyTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

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
        LocalSecurityColors provides securityColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
