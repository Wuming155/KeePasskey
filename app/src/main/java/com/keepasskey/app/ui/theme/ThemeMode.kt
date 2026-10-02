package com.keepasskey.app.ui.theme

import android.os.Build
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
 * 配色来源（ISSUE-P3-263 / PD-30，候选 A「互斥单选」；ISSUE-P3-441 AC① 扩第三形态）。
 *
 * [DYNAMIC]：系统壁纸动态取色（Material You）实际生效，品牌调色盘与自定义种子色让位；
 * [BRAND_PALETTE]：品牌调色盘实际生效；
 * [CUSTOM]：用户自定义种子色实际生效（种子 → 三族明暗方案，见 `SeedSchemeGenerator`）。
 *
 * 这是「当前实际生效」的唯一判据——设置页与 [KeePasskeyTheme] 必须共用
 * [resolveColorSource]，禁止 UI 与 Theme 层各写一份「开关 × SDK」推导
 * （否则即复现本条整改前的「UI 不判、Theme 独判」双写漂移）。
 */
enum class ColorSource {
    DYNAMIC,
    BRAND_PALETTE,
    CUSTOM
}

/**
 * 配色来源唯一判据（ISSUE-P3-263 AC① 纯函数；ISSUE-P3-441 AC① 扩种子色分支）。
 *
 * 优先级：动态取色命中当且仅当「偏好开启 **且** 设备支持」（Android 12 / API 31 起）；
 * 其次「已设置自定义种子色」（[seedColor] 非 null）即 [ColorSource.CUSTOM]；
 * 否则回落品牌调色盘。动态取色可能经备份恢复为 `true` 而设备不支持，此时显式回落
 * （种子色已设则到自定义、未设则品牌调色盘），不得依赖渲染层隐式兜底。
 *
 * 存储层互斥口径（PD-30，同一次原子事务）：写入种子色即幂等关动态取色；点选调色盘
 * 即幂等关动态取色**并清除**种子色——三态互斥由存储不变量兜底，本函数只做静态判定。
 */
fun resolveColorSource(
    dynamicColorEnabled: Boolean,
    seedColor: Long?,
    sdkInt: Int
): ColorSource = when {
    dynamicColorEnabled && sdkInt >= Build.VERSION_CODES.S -> ColorSource.DYNAMIC
    seedColor != null -> ColorSource.CUSTOM
    else -> ColorSource.BRAND_PALETTE
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
        // ISSUE-P3-346：secondary 提为有蓝相的石板色、tertiary 换为与品牌蓝对比的青绿系
        //（对比度按真实默认 on 色核算：白 7.28 / 6.47，容器 12.67 / 13.05，均 ≥4.5）
        secondaryColorLight = Color(0xFF3E5974),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFA9C3E0),
        onSecondaryDark = Color(0xFF1A3248),
        secondaryContainerLight = Color(0xFFCFDFF6),
        onSecondaryContainerLight = Color(0xFF0E1D2A),
        secondaryContainerDark = Color(0xFF33485F),
        onSecondaryContainerDark = Color(0xFFCFDFF6),
        tertiaryColorLight = Color(0xFF00696E),
        tertiaryContainerLight = Color(0xFF97F0F0),
        tertiaryColorDark = Color(0xFF51D3D6),
        tertiaryContainerDark = Color(0xFF004F52)
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
        // ISSUE-P3-346：secondary 提绿相、tertiary 转青蓝（与绿色 primary 形成第二色相）
        secondaryColorLight = Color(0xFF3D6B55),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFA3CDB5),
        onSecondaryDark = Color(0xFF1F352A),
        secondaryContainerLight = Color(0xFFC3ECD8),
        onSecondaryContainerLight = Color(0xFF092016),
        secondaryContainerDark = Color(0xFF2F4F41),
        onSecondaryContainerDark = Color(0xFFCFE9D9),
        tertiaryColorLight = Color(0xFF265E7E),
        tertiaryContainerLight = Color(0xFFC5ECFF),
        tertiaryColorDark = Color(0xFF7FC4E8),
        tertiaryContainerDark = Color(0xFF1E5266)
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
        // ISSUE-P3-346：secondary 转蓝紫相、tertiary 提玫红彩度
        secondaryColorLight = Color(0xFF5C5A85),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFC7C2E8),
        onSecondaryDark = Color(0xFF342D40),
        secondaryContainerLight = Color(0xFFE4DDFC),
        onSecondaryContainerLight = Color(0xFF1F182A),
        secondaryContainerDark = Color(0xFF46446B),
        onSecondaryContainerDark = Color(0xFFE9DEF7),
        tertiaryColorLight = Color(0xFF9D4A63),
        tertiaryContainerLight = Color(0xFFFFD6E0),
        tertiaryColorDark = Color(0xFFFFAFC4),
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
        // ISSUE-P3-346：secondary 暖棕提彩、tertiary 橄榄提亮（与橙 primary 同暖调但色相可辨）
        secondaryColorLight = Color(0xFF7A5433),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFEAC49F),
        onSecondaryDark = Color(0xFF422B1A),
        secondaryContainerLight = Color(0xFFFFDCC4),
        onSecondaryContainerLight = Color(0xFF2A1707),
        secondaryContainerDark = Color(0xFF634530),
        onSecondaryContainerDark = Color(0xFFFFDCC4),
        tertiaryColorLight = Color(0xFF6F6820),
        tertiaryContainerLight = Color(0xFFECE5AB),
        tertiaryColorDark = Color(0xFFD9D288),
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
    ),
    // ===== ISSUE-P3-441 AC③：预设调色盘适度扩充（纯数据追加，覆写管线零改动） =====
    ROSE(
        titleRes = R.string.theme_palette_rose,
        subtitleRes = R.string.theme_palette_rose_sub,
        primaryColorLight = Color(0xFF8F3B44),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFFFFB3AE),
        onPrimaryDark = Color(0xFF5D1219),
        containerColorLight = Color(0xFFFFDAD7),
        onContainerColorLight = Color(0xFF410006),
        containerColorDark = Color(0xFF73292F),
        onContainerColorDark = Color(0xFFFFDAD7),
        secondaryColorLight = Color(0xFF775657),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFE7BDBD),
        onSecondaryDark = Color(0xFF2C1516),
        secondaryContainerLight = Color(0xFFFFD9D9),
        onSecondaryContainerLight = Color(0xFF2C1516),
        secondaryContainerDark = Color(0xFF44292A),
        onSecondaryContainerDark = Color(0xFFFFD9D9),
        tertiaryColorLight = Color(0xFF7C5800),
        tertiaryContainerLight = Color(0xFFFFDEAB),
        tertiaryColorDark = Color(0xFFF7BC48),
        tertiaryContainerDark = Color(0xFF573E00)
    ),
    CYAN_MIST(
        titleRes = R.string.theme_palette_cyan_mist,
        subtitleRes = R.string.theme_palette_cyan_mist_sub,
        primaryColorLight = Color(0xFF00696D),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFF4CDADF),
        onPrimaryDark = Color(0xFF003739),
        containerColorLight = Color(0xFF9CF0F3),
        onContainerColorLight = Color(0xFF002021),
        containerColorDark = Color(0xFF004F52),
        onContainerColorDark = Color(0xFF9CF0F3),
        secondaryColorLight = Color(0xFF4A6365),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFB1CBCD),
        onSecondaryDark = Color(0xFF051F21),
        secondaryContainerLight = Color(0xFFCCE8E9),
        onSecondaryContainerLight = Color(0xFF051F21),
        secondaryContainerDark = Color(0xFF334B4D),
        onSecondaryContainerDark = Color(0xFFCCE8E9),
        tertiaryColorLight = Color(0xFF4B607B),
        tertiaryContainerLight = Color(0xFFD3E4FF),
        tertiaryColorDark = Color(0xFFB3C8E7),
        tertiaryContainerDark = Color(0xFF33475D)
    ),
    INDIGO_NIGHT(
        titleRes = R.string.theme_palette_indigo_night,
        subtitleRes = R.string.theme_palette_indigo_night_sub,
        primaryColorLight = Color(0xFF4954A8),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryColorDark = Color(0xFFBCC2FF),
        onPrimaryDark = Color(0xFF172465),
        containerColorLight = Color(0xFFDEE0FF),
        onContainerColorLight = Color(0xFF00105C),
        containerColorDark = Color(0xFF324092),
        onContainerColorDark = Color(0xFFDEE0FF),
        secondaryColorLight = Color(0xFF5B5D72),
        onSecondaryLight = Color(0xFFFFFFFF),
        secondaryColorDark = Color(0xFFC4C4DD),
        onSecondaryDark = Color(0xFF181A2C),
        secondaryContainerLight = Color(0xFFE0E1F9),
        onSecondaryContainerLight = Color(0xFF181A2C),
        secondaryContainerDark = Color(0xFF454659),
        onSecondaryContainerDark = Color(0xFFE0E1F9),
        tertiaryColorLight = Color(0xFF77536D),
        tertiaryContainerLight = Color(0xFFFFD7F1),
        tertiaryColorDark = Color(0xFFE6BAD7),
        tertiaryContainerDark = Color(0xFF5E3A55)
    )
}
