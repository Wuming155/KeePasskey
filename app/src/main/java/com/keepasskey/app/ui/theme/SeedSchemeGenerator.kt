package com.keepasskey.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * 自定义种子色 → Material 3 三族（primary / secondary / tertiary）明暗配色生成器
 * （ISSUE-P3-441 AC①）。
 *
 * 裁决与边界：
 * - **零第三方依赖**：用 `androidx.core.graphics.ColorUtils` 的 HSL 通道做明度 / 彩度推导
 *   （AndroidX 自带、纯 Java 数学，JVM 单测可直跑）；
 * - **只生成三族语义色**：surface / background / error 等基座色仍由 [LightColorScheme] /
 *   [DarkColorScheme] / [OledDarkColorScheme] 提供——生成结果经 Theme 层既有的
 *   「三族覆写管线」拷上基座（AC①「按现有三族覆写管线生成完整明暗方案」的本意）；
 * - **语义安全色不随种子色漂移**（LocalSecurityColors 口径不变）。
 *
 * 对比度保障：on 色取白（浅色）或低明度深色（深色），**约束本体色明度**直至
 * 对比对侧 ≥ 4.5:1（WCAG AA 正文）；循环带 0.04..0.96 明度边界兜底，任意输入种子色收敛。
 */
internal object SeedSchemeGenerator {

    /** 生成结果：与 [AppThemePalette] 的 20 个色字段一一对应（不含标题/副标题） */
    data class SeedColorFamilies(
        val primaryLight: Color,
        val onPrimaryLight: Color,
        val primaryContainerLight: Color,
        val onPrimaryContainerLight: Color,
        val secondaryLight: Color,
        val onSecondaryLight: Color,
        val secondaryContainerLight: Color,
        val onSecondaryContainerLight: Color,
        val tertiaryLight: Color,
        val onTertiaryLight: Color,
        val tertiaryContainerLight: Color,
        val onTertiaryContainerLight: Color,
        val primaryDark: Color,
        val onPrimaryDark: Color,
        val primaryContainerDark: Color,
        val onPrimaryContainerDark: Color,
        val secondaryDark: Color,
        val onSecondaryDark: Color,
        val secondaryContainerDark: Color,
        val onSecondaryContainerDark: Color,
        val tertiaryDark: Color,
        val onTertiaryDark: Color,
        val tertiaryContainerDark: Color,
        val onTertiaryContainerDark: Color
    )

    /** tertiary 相位偏移（与 primary 形成第二色相，对齐既有调色盘「三族不同相」的样式） */
    private const val HUE_TERTIARY_SHIFT = 60f

    /** WCAG AA 正文对比度下限 */
    private const val CONTRAST_AA = 4.5f

    private const val L_STEP = 0.02f
    private const val L_MIN = 0.04f
    private const val L_MAX = 0.96f

    /** 由 ARGB 种子色生成完整三族明暗配色 */
    fun generate(seedArgb: Long): SeedColorFamilies {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(seedArgb.toInt(), it) }
        val h = hsl[0]
        val s = hsl[1].coerceIn(0.15f, 1f)
        val l = hsl[2]
        val hT = (h + HUE_TERTIARY_SHIFT) % 360f
        val s2 = s * 0.45f

        return SeedColorFamilies(
            primaryLight = fitOnWhite(h, s, l),
            onPrimaryLight = Color.White,
            primaryContainerLight = tone(h, s, 0.90f),
            onPrimaryContainerLight = tone(h, s, 0.12f),
            secondaryLight = fitOnWhite(h, s2, l),
            onSecondaryLight = Color.White,
            secondaryContainerLight = tone(h, s2, 0.88f),
            onSecondaryContainerLight = tone(h, s2, 0.14f),
            tertiaryLight = fitOnWhite(hT, s, l),
            onTertiaryLight = Color.White,
            tertiaryContainerLight = tone(hT, s, 0.86f),
            onTertiaryContainerLight = tone(hT, s, 0.14f),
            primaryDark = fitOnDark(h, s, l),
            onPrimaryDark = tone(h, s, 0.10f),
            primaryContainerDark = tone(h, s, 0.30f),
            onPrimaryContainerDark = tone(h, s, 0.90f),
            secondaryDark = fitOnDark(h, s2, l),
            onSecondaryDark = tone(h, s2, 0.12f),
            secondaryContainerDark = tone(h, s2, 0.32f),
            onSecondaryContainerDark = tone(h, s2, 0.88f),
            tertiaryDark = fitOnDark(hT, s, l),
            onTertiaryDark = tone(hT, s, 0.12f),
            tertiaryContainerDark = tone(hT, s, 0.28f),
            onTertiaryContainerDark = tone(hT, s, 0.88f)
        )
    }

    /**
     * 浅色本体色：初始明度 = min(种子明度, 0.40)，向下压明度直至对白 ≥ [CONTRAST_AA]。
     */
    private fun fitOnWhite(h: Float, s: Float, seedL: Float): Color {
        var l = minOf(seedL, 0.40f)
        var color = tone(h, s, l)
        while (contrastWithWhite(color) < CONTRAST_AA && l > L_MIN) {
            l -= L_STEP
            color = tone(h, s, l)
        }
        return color
    }

    /** 深色本体色：初始明度 = max(种子明度, 0.72)，向上提明度直至对深 on 色 ≥ [CONTRAST_AA]。 */
    private fun fitOnDark(h: Float, s: Float, seedL: Float): Color {
        var l = maxOf(seedL, 0.72f)
        val onColor = tone(h, s, 0.10f)
        var color = tone(h, s, l)
        while (contrastOf(color, onColor) < CONTRAST_AA && l < L_MAX) {
            l += L_STEP
            color = tone(h, s, l)
        }
        return color
    }

    /** HSL → Compose Color（输入超域自动收敛） */
    private fun tone(h: Float, s: Float, l: Float): Color = Color(
        ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
    )

    private fun contrastWithWhite(color: Color): Float =
        contrastOf(color, Color.White)

    /** WCAG 相对亮度对比度 */
    private fun contrastOf(a: Color, b: Color): Float {
        val la = ColorUtils.calculateLuminance(a.toArgb())
        val lb = ColorUtils.calculateLuminance(b.toArgb())
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return ((lighter + 0.05) / (darker + 0.05)).toFloat()
    }
}
