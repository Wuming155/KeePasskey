package com.keepasskey.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-132 界面整改的**接线守护**（静态源码比对，沿用 `ColdStartAttachmentPurgeWiringTest` /
 * `CiArtifactVisibilityGuardTest` 的同源先例）。
 *
 * 本批四项整改的失效形态都是「**被后人静默改回**」，而它们在宿主 JVM 上无法靠渲染断言：
 * 1. `outline` 语义色退回半透明（→ 全站 OutlinedTextField 边框、OutlinedButton 描边重新消失）；
 * 2. 弹窗重新传自定义 `shape`（→ 偏离 MD3 `DialogTokens.ContainerShape = CornerExtraLarge`）；
 * 3. 禁用态主按钮退回 MD3 默认 `onSurface @12%`（→ 在 background 画布上实测仅 1.28:1）；
 * 4. 详情页快捷操作退回竖向磁贴卡片（→ 首屏竖向空间被吃掉）。
 *
 * 另含**防空扫断言**（§77 纪律）：扫描器一旦因路径漂移/正则失效而扫不到东西，
 * 用例必须失败而不是「零命中即通过」。
 */
class UiMd3AlignmentWiringTest {

    private val colorSource: String get() = readSource("app/src/main/java/com/keepasskey/app/ui/theme/Color.kt")

    @Test
    fun `outline 语义色必须不透明（半透明会让全站边框消失）`() {
        val light = namedValue(colorSource, "val OutlineLight =")
        val dark = namedValue(colorSource, "val OutlineDark =")

        assertTrue("OutlineLight 必须是 0xFF 不透明色，实际：$light", light.startsWith("Color(0xFF"))
        assertTrue("OutlineDark 必须是 0xFF 不透明色，实际：$dark", dark.startsWith("Color(0xFF"))
        assertTrue(
            "历史缺陷值 0x3374777F（20% alpha）不得回归",
            !light.contains("0x33") && !dark.contains("0x40")
        )
    }

    @Test
    fun `弹窗容器形状统一走主题 extraLarge 而不各自覆盖`() {
        val sources = uiSourceFiles()
        assertTrue("未扫到任何界面源码，扫描路径可能已漂移", sources.size >= MIN_UI_SOURCE_FILES)

        val offenders = mutableListOf<String>()
        var dialogCount = 0
        sources.forEach { file ->
            alertDialogArgumentTexts(stripComments(file.readText())).forEach { args ->
                dialogCount++
                val topLevelShape = topLevelArguments(args)
                    .firstOrNull { SHAPE_ARGUMENT.containsMatchIn(it.trim()) }
                if (topLevelShape != null) {
                    offenders += "${file.name}: ${topLevelShape.trim().take(80)}"
                }
            }
        }

        assertTrue(
            "扫描器未命中任何 AlertDialog 调用点（防空扫）：$dialogCount",
            dialogCount >= MIN_ALERT_DIALOG_CALL_SITES
        )
        assertTrue(
            "下列弹窗仍在覆盖 shape，应改用 MD3 默认（DialogTokens.ContainerShape = extraLarge）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `主题必须把 extraLarge 定义为 28dp`() {
        val shapeSource = readSource("app/src/main/java/com/keepasskey/app/ui/theme/Shape.kt")

        assertTrue(
            "MD3 弹窗容器为 28dp extraLarge，主题不得改动该档位",
            shapeSource.contains("extraLarge = RoundedCornerShape(28.dp)")
        )
    }

    @Test
    fun `禁用态主按钮必须走共用组件而不是各自写 MD3 默认色`() {
        val legacy = "disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)"
        val offenders = uiSourceFiles().filter { it.readText().contains(legacy) }.map { it.name }

        assertTrue(
            "禁用态必须改用 disabledPrimaryButtonColors()/disabledPrimaryButtonBorder()，仍在直写的文件：" +
                offenders.joinToString("、"),
            offenders.isEmpty()
        )

        listOf(
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockContentSections.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/HealthCheckScreen.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncComponents.kt"
        ).forEach { path ->
            val source = readSource(path)
            assertTrue("$path 必须接入禁用态共用配色", source.contains("disabledPrimaryButtonColors()"))
            assertTrue("$path 必须接入禁用态共用描边", source.contains("disabledPrimaryButtonBorder()"))
        }
    }

    @Test
    fun `详情页快捷操作保持紧凑 chip 行`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailComponents.kt"
        )

        assertTrue("快捷操作必须为 AssistChip 行", source.contains("AssistChip("))
        assertFalse(
            "竖向磁贴卡片（QuickActionTile）不得回归——它是本批要消除的竖向空间占用来源",
            source.contains("QuickActionTile")
        )
    }

    @Test
    fun `密码库卡片路径走中段省略`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/database/VaultDatabaseCard.kt"
        )

        assertTrue(
            "路径必须经 middleEllipsize 保住文件名（末端省略会砍掉最有辨识度的部分）",
            source.contains("middleEllipsize(database.path, DATABASE_PATH_MAX_CHARS)")
        )
    }

    // ---------------- 工具 ----------------

    /** 取 `val X = ` 之后到行尾的值文本 */
    private fun namedValue(source: String, declaration: String): String {
        val line = source.lines().firstOrNull { it.trimStart().startsWith(declaration) }
        assertTrue("未找到声明：$declaration", line != null)
        return line!!.substringAfter("=").trim()
    }

    /** `ui/` 下的全部 Kotlin 源码（不含 build 产物） */
    private fun uiSourceFiles(): List<File> =
        File(repositoryRoot, "app/src/main/java/com/keepasskey/app/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * 摘出每个 `AlertDialog(` / `BasicAlertDialog(` 调用的**实参文本**（按括号配平切分）。
     *
     * 注释先被 [stripComments] 去掉，避免 KDoc 里举例写的 `AlertDialog(...)` 把扫描带偏。
     */
    private fun alertDialogArgumentTexts(source: String): List<String> {
        val results = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val marker = ALERT_DIALOG_MARKERS
                .map { source.indexOf(it, searchFrom) }
                .filter { it >= 0 }
                .minOrNull() ?: break
            val markerText = ALERT_DIALOG_MARKERS.first { source.startsWith(it, marker) }
            val start = marker + markerText.length
            var depth = 1
            var cursor = start
            while (cursor < source.length && depth > 0) {
                when (source[cursor]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                cursor++
            }
            results += source.substring(start, maxOf(start, cursor - 1))
            searchFrom = cursor
        }
        return results
    }

    /**
     * 取实参列表中的**顶层参数段**（按 `,` 切分，但跳过嵌套括号与 lambda 花括号内的逗号）。
     *
     * 必要性：`AlertDialog(...)` 的实参里嵌套着 `Button(shape = CapsuleShape)`、
     * `OutlinedTextField(shape = …)` 等**子组件自己的**形状参数——笼统搜索 `shape =`
     * 会把它们全部误报为「弹窗覆盖形状」（首版实现即如此，已由本用例自身暴露）。
     * 只有**顶层**的 `shape =` 才是传给 `AlertDialog` 的那一个。
     */
    private fun topLevelArguments(arguments: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        arguments.forEach { char ->
            when (char) {
                '(' , '[' -> parenDepth++
                ')' , ']' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
            }
            if (char == ',' && parenDepth == 0 && braceDepth == 0) {
                segments += current.toString()
                current.clear()
            } else {
                current.append(char)
            }
        }
        segments += current.toString()
        return segments
    }

    /** 去掉块注释与行注释（本用例只做模式匹配，字符串里的 `//` 被截断无害） */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 弹窗**自身**的形状实参（锚定在顶层参数段的起始处，避免误报子组件的 `shape =`） */
        val SHAPE_ARGUMENT = Regex("""^shape\s*=""")

        val ALERT_DIALOG_MARKERS = listOf("AlertDialog(", "BasicAlertDialog(")

        /** 防空扫下限：全仓界面层的弹窗调用点数量级（低于此值说明扫描器失效） */
        const val MIN_ALERT_DIALOG_CALL_SITES = 15

        /** 防空扫下限：ui/ 包下的源码文件数 */
        const val MIN_UI_SOURCE_FILES = 80

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
