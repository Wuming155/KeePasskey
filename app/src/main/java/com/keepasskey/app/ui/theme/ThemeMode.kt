package com.keepasskey.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.keepasskey.app.R

/**
 * 应用主题模式枚举 (浅色/深色/跟随系统)
 */
enum class AppThemeMode(@StringRes val displayNameRes: Int) {
    LIGHT(R.string.theme_mode_light),
    DARK(R.string.theme_mode_dark),
    SYSTEM(R.string.settings_lang_system)
}

/**
 * 现代化内置主题调色盘风格 (满足不同审美偏好与现代感视觉)
 * 完整提供 Material 3 配色体系 (Primary/Secondary/Tertiary 及对应容器色与文本反色)
 */
enum class AppThemePalette(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val primaryColorLight: Color,
    val onPrimaryLight: Color,
    val primaryColorDark: Color,
    val onPrimaryDark: Color,
    val containerColorLight: Color,
    val onContainerColorLight: Color,
    val containerColorDark: Color,
    val onContainerColorDark: Color,
    val secondaryColorLight: Color,
    val onSecondaryLight: Color,
    val secondaryColorDark: Color,
    val onSecondaryDark: Color,
    val secondaryContainerLight: Color,
    val onSecondaryContainerLight: Color,
    val secondaryContainerDark: Color,
    val onSecondaryContainerDark: Color,
    val tertiaryColorLight: Color,
    val tertiaryContainerLight: Color,
    val tertiaryColorDark: Color,
    val tertiaryContainerDark: Color
) {
    SAPPHIRE(
        titleRes = R.string.theme_palette_sapphire,
        subtitleRes = R.string.theme_palette_sapphire_sub,
        primaryColorLight = Color(0xFF00629E),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFF66B5FF),
        onPrimaryDark = Color(0xFF003257),
        containerColorLight = Color(0xFFCFE5FF),
        onContainerColorLight = Color(0xFF001D35),
        containerColorDark = Color(0xFF004977),
        onContainerColorDark = Color(0xFFCFE5FF),
        secondaryColorLight = Color(0xFF526070),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFB9C8DA),
        onSecondaryDark = Color(0xFF243240),
        secondaryContainerLight = Color(0xFFD5E4F7),
        onSecondaryContainerLight = Color(0xFF0E1D2A),
        secondaryContainerDark = Color(0xFF3A4857),
        onSecondaryContainerDark = Color(0xFFD5E4F7),
        tertiaryColorLight = Color(0xFF6A5779),
        tertiaryContainerLight = Color(0xFFF2DAFF),
        tertiaryColorDark = Color(0xFFD5BEE5),
        tertiaryContainerDark = Color(0xFF513F60)
    ),
    EMERALD(
        titleRes = R.string.theme_palette_emerald,
        subtitleRes = R.string.theme_palette_emerald_sub,
        primaryColorLight = Color(0xFF006C4C),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFF63DBA7),
        onPrimaryDark = Color(0xFF003825),
        containerColorLight = Color(0xFF8FF8C2),
        onContainerColorLight = Color(0xFF002114),
        containerColorDark = Color(0xFF005238),
        onContainerColorDark = Color(0xFF8FF8C2),
        secondaryColorLight = Color(0xFF4C6357),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFB3CCBD),
        onSecondaryDark = Color(0xFF1F352A),
        secondaryContainerLight = Color(0xFFCFE9D9),
        onSecondaryContainerLight = Color(0xFF092016),
        secondaryContainerDark = Color(0xFF354B40),
        onSecondaryContainerDark = Color(0xFFCFE9D9),
        tertiaryColorLight = Color(0xFF3E6374),
        tertiaryContainerLight = Color(0xFFC1E9FC),
        tertiaryColorDark = Color(0xFFA6CCE0),
        tertiaryContainerDark = Color(0xFF244B5B)
    ),
    AMETHYST(
        titleRes = R.string.theme_palette_amethyst,
        subtitleRes = R.string.theme_palette_amethyst_sub,
        primaryColorLight = Color(0xFF6B4EA2),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFFD4BBFF),
        onPrimaryDark = Color(0xFF3C1B71),
        containerColorLight = Color(0xFFECDCFF),
        onContainerColorLight = Color(0xFF250059),
        containerColorDark = Color(0xFF533688),
        onContainerColorDark = Color(0xFFECDCFF),
        secondaryColorLight = Color(0xFF635B70),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFCDC2DB),
        onSecondaryDark = Color(0xFF342D40),
        secondaryContainerLight = Color(0xFFE9DEF7),
        onSecondaryContainerLight = Color(0xFF1F182A),
        secondaryContainerDark = Color(0xFF4B4358),
        onSecondaryContainerDark = Color(0xFFE9DEF7),
        tertiaryColorLight = Color(0xFF7E5260),
        tertiaryContainerLight = Color(0xFFFFD9E2),
        tertiaryColorDark = Color(0xFFF1B7C8),
        tertiaryContainerDark = Color(0xFF643B49)
    ),
    AMBER_SUNSET(
        titleRes = R.string.theme_palette_amber_sunset,
        subtitleRes = R.string.theme_palette_amber_sunset_sub,
        primaryColorLight = Color(0xFF904D00),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFFFFB68C),
        onPrimaryDark = Color(0xFF4E2600),
        containerColorLight = Color(0xFFFFDCC0),
        onContainerColorLight = Color(0xFF2F1500),
        containerColorDark = Color(0xFF6E3900),
        onContainerColorDark = Color(0xFFFFDCC0),
        secondaryColorLight = Color(0xFF745944),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFE4BFA7),
        onSecondaryDark = Color(0xFF422B1A),
        secondaryContainerLight = Color(0xFFFFDCC4),
        onSecondaryContainerLight = Color(0xFF2A1707),
        secondaryContainerDark = Color(0xFF5B422E),
        onSecondaryContainerDark = Color(0xFFFFDCC4),
        tertiaryColorLight = Color(0xFF656032),
        tertiaryContainerLight = Color(0xFFECE5AB),
        tertiaryColorDark = Color(0xFFCFC891),
        tertiaryContainerDark = Color(0xFF4D481D)
    ),
    OBSIDIAN(
        titleRes = R.string.theme_palette_obsidian,
        subtitleRes = R.string.theme_palette_obsidian_sub,
        primaryColorLight = Color(0xFF334155),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFF94A3B8),
        onPrimaryDark = Color(0xFF0F172A),
        containerColorLight = Color(0xFFCBD5E1),
        onContainerColorLight = Color(0xFF0F172A),
        containerColorDark = Color(0xFF334155),
        onContainerColorDark = Color(0xFFE2E8F0),
        secondaryColorLight = Color(0xFF475569),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFCBD5E1),
        onSecondaryDark = Color(0xFF1E293B),
        secondaryContainerLight = Color(0xFFE2E8F0),
        onSecondaryContainerLight = Color(0xFF1E293B),
        secondaryContainerDark = Color(0xFF475569),
        onSecondaryContainerDark = Color(0xFFF1F5F9),
        tertiaryColorLight = Color(0xFF64748B),
        tertiaryContainerLight = Color(0xFFF1F5F9),
        tertiaryColorDark = Color(0xFFE2E8F0),
        tertiaryContainerDark = Color(0xFF64748B)
    )
}
