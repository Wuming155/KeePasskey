package com.keepasskey.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    dynamicColorEnabled: Boolean = false,
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

    // Material You 动态取色（Android 12+）：开启后以系统壁纸取色为基准，品牌调色盘让位；
    // 语义安全色 (LocalSecurityColors) 保持固定，不随壁纸漂移
    val useDynamicColor = dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        useDynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> baseColorScheme.copy(
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
        else -> baseColorScheme.copy(
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

    // 动态取色路径下 OLED 纯黑需手动接管（品牌暗色板已内置纯黑方案）
    val finalColorScheme = if (useDynamicColor && darkTheme && oledBlack) {
        colorScheme.copy(background = Color.Black, surface = Color.Black)
    } else {
        colorScheme
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
            colorScheme = finalColorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
