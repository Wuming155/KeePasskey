# -*- coding: utf-8 -*-
# §211 拆分脚本（临时，用后删）：KeePasskeyTheme
import io

p = 'app/src/main/java/com/keepasskey/app/ui/theme/Theme.kt'
t = io.open(p, encoding='utf-8').read()

old = '''    // Material You 动态取色（Android 12+）：开启后以系统壁纸取色为基准，品牌调色盘让位；
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
            tertiaryContainer = themePalette.tertiaryColorDark
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
            tertiaryContainer = themePalette.tertiaryColorLight
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
'''
new = '''    // Material You 动态取色（Android 12+）：开启后以系统壁纸取色为基准，品牌调色盘让位；
    // 语义安全色 (LocalSecurityColors) 保持固定，不随壁纸漂移
    val useDynamicColor = dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = resolveAppColorScheme(
        useDynamicColor = useDynamicColor,
        darkTheme = darkTheme,
        baseColorScheme = baseColorScheme,
        themePalette = themePalette
    )

    // 动态取色路径下 OLED 纯黑需手动接管（品牌暗色板已内置纯黑方案）
    val finalColorScheme = if (useDynamicColor && darkTheme && oledBlack) {
        colorScheme.copy(background = Color.Black, surface = Color.Black)
    } else {
        colorScheme
    }

    val securityColors = resolveSecurityColors(darkTheme)
'''
assert old in t, 'theme anchor missing'
t = t.replace(old, new)

anchor = '''/**
 * 主题调色盘总览预览（IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI）。'''
section = '''/**
 * 解析生效的 [ColorScheme]：动态取色命中时以系统壁纸取色为基准；
 * 否则以品牌调色盘覆写 primary / secondary / tertiary 三族语义色（dark / light 两分支）。
 * §211 自 [KeePasskeyTheme] 下沉（纯函数，逐字搬动、零行为变更）。
 */
private fun resolveAppColorScheme(
    useDynamicColor: Boolean,
    darkTheme: Boolean,
    baseColorScheme: ColorScheme,
    themePalette: AppThemePalette
): ColorScheme = when {
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
        tertiaryContainer = themePalette.tertiaryColorLight
    )
}

/**
 * 解析语义安全色（passkey / success / warning / danger）的明暗两套取值。
 * §211 自 [KeePasskeyTheme] 下沉（纯函数，逐字搬动、零行为变更）。
 */
private fun resolveSecurityColors(darkTheme: Boolean): SecurityColors = if (darkTheme) {
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

''' + anchor
assert anchor in t
t = t.replace(anchor, section)
io.open(p, 'w', encoding='utf-8', newline='\n').write(t)
print('ok')
