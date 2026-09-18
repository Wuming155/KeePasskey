package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 密码库设置页「段落下沉」的接线守卫（§159，`ISSUE-P3-188` 剩余清单第 1 项第二段）。
 *
 * 本批把子库对话框群与导入群从整页移进两个同包段落组件。结构搬家若无判据，退化只会以
 * 「用户丢输入」的形式在未来某次改动里现身——且**编译与既有单测都看不见**。故按本仓先例
 * （`AlgoHotPathGuardsTest` / `OneTapInteractionWiringTest`）以**源码文本**钉住四条：
 *
 * 1. **选择器必须声明在门控块之前**：三个 `OpenDocument` 选择器若被挪进 `if (showDialog)`，
 *    SAF 往返期间对话框一关就丢表单（原注释记载的设计理由正是「选择器置于对话框之外」）；
 * 2. **段落必须无条件组合**：整页对两段落的调用不得落在任何 `if` 块内，且不得残留
 *    `if (showChildDbDialog)` / `if (showImportDialog)` 直调对话框的旧形态——
 *    条件包裹段落会让段落自身随对话框关闭而离开组合，§2.4 的「存活期等价」前提即失效；
 * 3. **子库关闭三口径齐备**：清来源、清挂载密钥文件、关父级态、复位反馈条，缺一即留残影；
 * 4. **导入两步式顺序**：先记来源再呼起选择器；回调内先捕获后置空（否则来源丢失、导入静默失败）。
 *
 * 断言前一律走 [stripCommentsOnly]（§157/§158 的统一口径：保留字符串内容、只去注释）——
 * 本批样例源里就有 SAF 通配字面量，naive 剥离会把真实代码当注释吞掉（`ISSUE-P3-194`）。
 */
class DatabaseSettingsSectionWiringTest {

    @Test
    fun `子库选择器必须声明在对话框门控之前`() {
        val body = functionBodyOf(sectionsSource(), "internal fun ChildDatabaseSection")
        val firstLauncher = body.indexOf("rememberLauncherForActivityResult(")
        val gate = body.indexOf("if (showDialog)")

        assertEquals(
            "子库段应有三个 SAF 选择器（来源 / 挂载密钥文件 / 解锁密钥文件）",
            3,
            Regex("rememberLauncherForActivityResult\\(").findAll(body).count()
        )
        assertTrue("未找到门控块或选择器（结构已变，须重排判据）", firstLauncher >= 0 && gate >= 0)
        assertTrue(
            "选择器必须先于 if (showDialog) 声明：落在门控块内会在 SAF 往返期间丢表单输入",
            firstLauncher < gate
        )
    }

    @Test
    fun `整页必须无条件组合两个段落且不得残留旧直调形态`() {
        val screen = stripCommentsOnly(readSource(PARENT))
        val childCall = indexOfTopLevelCall(screen, "ChildDatabaseSection(")
        val importCall = indexOfTopLevelCall(screen, "VaultImportSection(")

        assertTrue("整页必须调用 ChildDatabaseSection（段落被删即失去入口）", childCall >= 0)
        assertTrue("整页必须调用 VaultImportSection（段落被删即失去入口）", importCall >= 0)
        assertFalse(
            "不得残留「if (showChildDbDialog) { ChildDatabaseDialog(…) }」旧形态（条件包裹段落即丢状态）",
            Regex("if \\(showChildDbDialog\\)").containsMatchIn(screen) ||
                Regex("ChildDatabaseDialog\\(").containsMatchIn(screen)
        )
        assertFalse(
            "不得残留「if (showImportDialog) { ImportSourceDialog(…) }」旧形态",
            Regex("if \\(showImportDialog\\)").containsMatchIn(screen) ||
                Regex("ImportSourceDialog\\(").containsMatchIn(screen)
        )
    }

    @Test
    fun `子库对话框关闭必须一次做完清态与复位反馈`() {
        val body = functionBodyOf(sectionsSource(), "internal fun ChildDatabaseSection")
        val dismiss = body.substringAfter("onDismiss = {").substringBefore("}")

        listOf(
            "childDbSourceUri = null",
            "childDbMountKeyFileUri = null",
            "onDialogDismiss()",
            "onFeedbackDismiss()"
        ).forEach {
            assertTrue("子库对话框 onDismiss 缺少「$it」（漏 onFeedbackDismiss 即反馈条滞留）", dismiss.contains(it))
        }
    }

    @Test
    fun `导入段必须保持先记来源再呼选择器的两步式`() {
        val body = functionBodyOf(sectionsSource(), "internal fun VaultImportSection")

        assertTrue(
            "呼起选择器前必须先落 pendingImportSource（否则回调无从得知来源）",
            body.indexOf("pendingImportSource = source") < body.indexOf("importFileLauncher.launch(")
        )
        assertTrue(
            "回调内必须「先捕获来源、后置空」——顺序颠倒会让 source 恒为 null，导入静默失败",
            body.indexOf("val source = pendingImportSource") in 0 until body.indexOf("pendingImportSource = null")
        )
    }

    private fun sectionsSource(): String = stripCommentsOnly(readSource(SECTIONS))

    /** 顶层调用点的判定：调用行必须是 4 空格缩进（不在任何块内） */
    private fun indexOfTopLevelCall(source: String, call: String): Int =
        source.lines().indexOfFirst { it.startsWith("    $call") }

    /** 按花括号配平取出某个顶层函数的函数体（调用前已剥注释，故不会因注释里的括号错位） */
    private fun functionBodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到函数：$signature（是否被改名/移走）", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        error("函数体未闭合：$signature")
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val DIR = "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens"
        const val SECTIONS = "$DIR/DatabaseSettingsSections.kt"
        const val PARENT = "$DIR/DatabaseSettingsScreen.kt"

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
