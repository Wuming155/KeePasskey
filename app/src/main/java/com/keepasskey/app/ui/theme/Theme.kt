package com.keepasskey.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
        // TASK-07：Material 3 Expressive——Expressive 主题承载官方弹性动效（MotionScheme），
        // 形状/排版在 Expressive 语义下渲染；品牌 ColorScheme/typography/shapes 定制保持不变
        MaterialExpressiveTheme(
            colorScheme = finalColorScheme,
            typography = Typography,
            shapes = Shapes,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}

/**
 * 主题调色盘总览预览（IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI）。
 *
 * 以品牌调色盘 [AppThemePalette.SAPPHIRE] 渲染主角色色块，用于在 Preview 面板中逐目比对
 * 浅色 / 深色两套语义色的实际观感。
 */
@Preview(name = "主题调色盘总览 - 浅色", showBackground = true)
@Preview(name = "主题调色盘总览 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun KeePasskeyThemePreview() {
    KeePasskeyTheme {
        // 主角色语义色抽样：用于在 Preview 面板中逐目比对浅色 / 深色两套观感
        val swatches = listOf(
            "primary" to MaterialTheme.colorScheme.primary,
            "primaryContainer" to MaterialTheme.colorScheme.primaryContainer,
            "secondary" to MaterialTheme.colorScheme.secondary,
            "tertiary" to MaterialTheme.colorScheme.tertiary,
            "surfaceVariant" to MaterialTheme.colorScheme.surfaceVariant,
            "error" to MaterialTheme.colorScheme.error
        )
        Column(modifier = Modifier.padding(12.dp)) {
            swatches.forEach { (label, color) ->
                Text(text = label, modifier = Modifier.padding(bottom = 4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(color)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}
