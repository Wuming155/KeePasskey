package com.keepasskey.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-138「全站 CJK 断行策略」的**接线守护**（静态源码比对，沿用
 * `UiMd3AlignmentWiringTest` 的同源先例）。
 *
 * 失效形态是「**被后人静默改回**」：`Typography` 的断行策略只影响**跨行**时的观感，
 * 单行文案完全看不出来，一旦新增排版槽位时漏带 `lineBreak`，或有人把参数退回
 * `LineBreak.Simple`，中文界面的避头尾违规（行末悬挂左括号一类）会无声回归，
 * 而宿主 JVM 上无法靠渲染断言发现。
 *
 * 另含**防空扫断言**：扫描器一旦因路径漂移 / 正则失效而扫不到东西，
 * 用例必须失败而不是「零命中即通过」。
 */
class UiCjkLineBreakWiringTest {

    private val typeSource: String
        get() = File(repositoryRoot, TYPE_PATH).let {
            assertTrue("排版源码不存在（是否被重命名/移动）：$TYPE_PATH", it.isFile)
            it.readText()
        }

    @Test
    fun `CJK 断行策略必须是官方为 CJK 设计的 Phrase 与 Strict 组合`() {
        assertTrue(
            "策略必须为 HighQuality（官方建议非 Balanced/Simple 场景默认）",
            typeSource.contains("strategy = LineBreak.Strategy.HighQuality")
        )
        assertTrue(
            "禁则（避头尾）必须为 Strict——Loose/Normal 会放行行末悬挂左括号",
            typeSource.contains("strictness = LineBreak.Strictness.Strict")
        )
        assertTrue(
            "短语内不断行必须为 WordBreak.Phrase——Default 会退回逐字断行",
            typeSource.contains("wordBreak = LineBreak.WordBreak.Phrase")
        )
    }

    @Test
    fun `每个自定义排版槽位都必须套用 CJK 断行策略`() {
        // 自定义槽位的判据：显式指定 FontFamily.Default（Monospace 保密样式不在此列，
        // 它们是单行凭据/验证码，不受跨行断行影响）
        val textStyleCount = FONT_FAMILY_DEFAULT.findAll(typeSource).count()
        val lineBreakCount = LINE_BREAK.findAll(typeSource).count()

        assertTrue(
            "扫描器未命中任何排版槽位（防空扫）：$textStyleCount",
            textStyleCount >= MIN_TEXT_STYLE_SLOTS
        )
        assertTrue(
            "有 $textStyleCount 个排版槽位，但只有 $lineBreakCount 处套用了 CjkLineBreak——" +
                "漏带的槽位会让该字号的文案退回逐字断行",
            lineBreakCount >= textStyleCount
        )
    }

    private companion object {
        const val TYPE_PATH = "app/src/main/java/com/keepasskey/app/ui/theme/Type.kt"

        val FONT_FAMILY_DEFAULT = Regex("""fontFamily\s*=\s*FontFamily\.Default""")
        val LINE_BREAK = Regex("""lineBreak\s*=\s*CjkLineBreak""")

        /** 防空扫下限：`Typography` 自定义槽位数 + `HeroTitleStyle` */
        const val MIN_TEXT_STYLE_SLOTS = 8

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
