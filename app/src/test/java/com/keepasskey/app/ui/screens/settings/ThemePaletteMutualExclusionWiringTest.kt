package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-263 / PD-30（候选 A「互斥单选」）的**接线守卫**。
 *
 * 动态取色与主题调色盘的互斥关系分布在存储（`RealSettingsRepository`）、判据
 * （`resolveColorSource` 共用）、呈现（`ThemePaletteItemCard` 置灰）与装配
 * （`ThemeSettingsScreen` / NavGraph 一步切回）四处——任一处被改回「互不感知」
 * 即复现整改前「屏幕上两根矛盾选中同时成立」的缺陷面。判据形态与本仓其余接线
 * 守卫一致：读源码文本比对，不依赖运行时（Compose 呈现无法在 JVM 断言）。
 */
class ThemePaletteMutualExclusionWiringTest {

    @Test
    fun `生产仓库点选调色盘必须单事务互斥关闭动态取色`() {
        val source = readSource(REPOSITORY)
        val body = extractSetThemePaletteBody(source)
        assertTrue(
            "setThemePalette 必须在同一 DataStore edit{} 事务内写两键（原子性）：" +
                "缺 KEY_THEME_PALETTE 写入", body.contains("KEY_THEME_PALETTE")
        )
        assertTrue(
            "setThemePalette 必须在同一 DataStore edit{} 事务内写两键（原子性）：缺 " +
                "KEY_DYNAMIC_COLOR = false 互斥写（PD-30 候选 A）",
            body.contains("KEY_DYNAMIC_COLOR] = false")
        )
    }

    @Test
    fun `调色盘条目必须以 enabled 参数承载置灰与不可点`() {
        val source = readSource(COMPONENTS)
        assertTrue(
            "ThemePaletteItemCard 必须暴露 enabled 参数（ISSUE-P3-263 AC②）",
            source.contains("enabled: Boolean = true")
        )
        assertTrue(
            "clickable 必须传 enabled（置灰期间不得可点）",
            source.contains(".clickable(enabled = enabled, onClick = onClick)")
        )
        assertTrue(
            "置灰须有视觉呈现（整体降透明度）",
            source.contains(".alpha(if (enabled) 1f else 0.55f)")
        )
    }

    @Test
    fun `调色盘分区判据必须单点化且选中态被 paletteUsable 门控`() {
        val source = readSource(SECTIONS)
        assertTrue(
            "设置页必须与 Theme 层共用 resolveColorSource（AC① 判据单点化，禁止另写开关×SDK）",
            source.contains("resolveColorSource(uiState.dynamicColorEnabled, Build.VERSION.SDK_INT)")
        )
        assertTrue(
            "选中态必须被 paletteUsable 门控——动态取色生效期间不得呈现任何已选中",
            source.contains("isSelected = paletteUsable && uiState.themePalette == palette")
        )
        assertTrue(
            "5 项必须整节置灰（enabled = paletteUsable）",
            source.contains("enabled = paletteUsable")
        )
        assertTrue(
            "置灰分区必须附行内原因说明（AC③）",
            source.contains("R.string.theme_palette_dynamic_notice")
        )
        assertTrue(
            "置灰分区必须提供一步切回动作（AC③）",
            source.contains("R.string.theme_palette_use_instead")
        )
    }

    @Test
    fun `一步切回动作必须自页面直通导航装配`() {
        val screen = readSource("$SUBSCREEN_DIR/ThemeSettingsScreen.kt")
        assertTrue(
            "ThemeSettingsScreen 必须透传 onSwitchToBrandPalette",
            screen.contains("onSwitchToBrandPalette = onSwitchToBrandPalette")
        )
        val navGraph = readSource(NAV_GRAPH)
        assertTrue(
            "NavGraph 必须把一步切回接线到关闭动态取色（偏好值已落盘，无须另行选择）",
            navGraph.contains("onSwitchToBrandPalette = { settingsViewModel.setDynamicColorEnabled(false) }")
        )
    }

    @Test
    fun `Theme 层不得再出现内联的动态取色判据`() {
        val theme = readSource(THEME)
        assertTrue(
            "Theme 层判据必须单点化（AC①）",
            theme.contains("resolveColorSource(")
        )
        assertFalse(
            "Theme 层不得保留「开关 × SDK」内联推导（双写漂移即复现 ISSUE-P3-263）",
            theme.contains("dynamicColorEnabled && Build.VERSION.SDK_INT")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 截取 RealSettingsRepository.setThemePalette 的函数体（KDoc 起至下一 override 前），防跨函数误报 */
    private fun extractSetThemePaletteBody(source: String): String {
        val start = source.indexOf("override suspend fun setThemePalette")
        assertTrue("RealSettingsRepository 缺 setThemePalette", start >= 0)
        val end = source.indexOf("override suspend fun setOledBlackOptimization", start)
        return source.substring(start, if (end > start) end else source.length)
    }

    private companion object {
        const val SUBSCREEN_DIR =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens"

        const val REPOSITORY =
            "app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt"
        const val COMPONENTS = "$SUBSCREEN_DIR/ThemeSettingsComponents.kt"
        const val SECTIONS = "$SUBSCREEN_DIR/ThemeSettingsSections.kt"
        const val NAV_GRAPH = "app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraph.kt"
        const val THEME = "app/src/main/java/com/keepasskey/app/ui/theme/Theme.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
