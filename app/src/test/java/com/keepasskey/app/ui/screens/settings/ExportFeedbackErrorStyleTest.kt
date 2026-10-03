package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-353 AC④ 回归：导出错误样式必须按**类型化 `isError` 字段**判定，不依赖展示文案。
 *
 * 缺陷背景：`DatabaseSettingsScreen` 用 `feedback.resolveText().contains("失败")` 决定
 * `DatabaseFeedbackItem` 的错误配色——切英文语言后失败条目以成功样式呈现。
 *
 * 断言分两层：
 * 1. **产生处**（`SettingsExportController`）：每个失败反馈构造必须显式带 `isError = true`，
 *    成功反馈不得带（否则样式判定失去鉴别力）；
 * 2. **消费处**（`ImportExportSettingsScreen`；ISSUE-P3-467 自 `DatabaseSettingsScreen` 纯迁位拆出，
 *    消费点随段落走）：必须读 `feedback.isError`，旧的 `contains("失败")` 文案嗅探不得回归。
 */
class ExportFeedbackErrorStyleTest {

    @Test
    fun `消费处按类型化 isError 上错误样式 文案嗅探不得回归`() {
        val screen = stripCommentsOnly(readSource(SCREEN))

        assertTrue(
            "错误样式必须读类型化 isError 字段",
            screen.contains("isError = feedback.isError")
        )
        assertFalse(
            "旧形态不得回归：按展示文案 contains(\"失败\") 判错误样式（英文语言下失效）",
            screen.contains("contains(\"失败\")")
        )
    }

    @Test
    fun `导出控制器全部失败反馈显式标记 isError 成功反馈不标记`() {
        val source = stripCommentsOnly(readSource(CONTROLLER))
        // 逐个 UiMessage(…) 实参表检查（按括号配平切片，避免被嵌套 listOf(…) 干扰）
        val argTables = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = source.indexOf("UiMessage(", cursor)
            if (start < 0) break
            val open = start + "UiMessage(".length - 1
            val end = matchParen(source, open)
            assertTrue("UiMessage( 实参表未闭合（源码结构已变）", end > open)
            argTables.add(source.substring(open + 1, end))
            cursor = end + 1
        }
        assertTrue("控制器内必须存在 UiMessage 构造点（扫描器空转即判红）", argTables.isNotEmpty())

        val failureTables = argTables.filter {
            it.contains("op_failed") || it.contains("debug_export_failed")
        }
        assertTrue("必须扫到失败反馈构造点（正则失效即判红）", failureTables.isNotEmpty())
        failureTables.forEach { table ->
            assertTrue(
                "失败反馈必须显式带 isError = true：${table.take(80)}",
                table.contains("isError = true")
            )
        }

        val successTables = argTables.filter {
            it.contains("dbset_templates_installed") || it.contains("debug_export_done")
        }
        assertTrue("必须扫到成功反馈构造点（正则失效即判红）", successTables.isNotEmpty())
        successTables.forEach { table ->
            assertFalse(
                "成功反馈不得标记 isError（否则样式判定失去鉴别力）：${table.take(80)}",
                table.contains("isError = true")
            )
        }
    }

    private fun matchParen(text: String, openIdx: Int): Int {
        var depth = 0
        for (idx in openIdx until text.length) {
            when (text[idx]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return idx
                }
            }
        }
        return -1
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被改名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        // ISSUE-P3-467：导出反馈消费点已随段落自 DatabaseSettingsScreen 迁至 ImportExportSettingsScreen
        const val SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ImportExportSettingsScreen.kt"
        const val CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExportController.kt"
        const val ROOT_SEARCH_DEPTH = 6

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
    }
}
