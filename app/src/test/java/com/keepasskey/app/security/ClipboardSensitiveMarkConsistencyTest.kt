package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 剪贴板保护的**文案 ↔ 实现**一致性守卫（ISSUE-P3-114）。
 *
 * 缺陷背景：设置页曾承诺「复制的敏感内容**不进入剪贴板历史与云同步**」，而实现只做了两件事：
 * ① 注入 `ClipDescription.EXTRA_IS_SENSITIVE`；② 按用户设定时长自动擦除。
 * 按平台文档，该标记的语义是**渲染提示**——「Adding this extra **does not change clipboard
 * behavior or add additional security** to the ClipData. Its purpose is essentially a rendering
 * hint from the source application」，实际效果是 Android 13+ 的**系统复制视觉确认不再显示明文**。
 * 「剪贴板历史」与「云同步」属系统 / 厂商实现面，本应用**无法强制**（非合规的剪贴板工具
 * 仍可在驻留窗口内读取），故原文案属**过度承诺**。
 *
 * 本用例双向锁定一致性：
 * - **文案侧**：中英两条不得再出现「剪贴板历史 / 云同步」式绝对承诺（重新引入须先给出平台级证据）；
 * - **实现侧**：两条敏感通道必须真设 `EXTRA_IS_SENSITIVE`，普通通道**不得**设
 *   （否则「敏感标记」会退化为无差别标记，文案所述能力同样失实）。
 *
 * 断言前剔除注释——整改说明自身会写出被断言的字面量。
 */
class ClipboardSensitiveMarkConsistencyTest {

    @Test
    fun `敏感复制通道必须真实设置敏感标记而普通通道不得设置`() {
        val code = stripComments(readSource(MANAGER_SOURCE))

        val sensitiveText = functionBody(code, "override fun copySensitiveText(")
        val sensitiveChars = functionBody(code, "override fun copySensitiveChars(")
        val plain = functionBody(code, "override fun copyPlainText(")

        assertTrue(
            "[$MANAGER_SOURCE] copySensitiveText 未设 EXTRA_IS_SENSITIVE：文案所述「标记为敏感」失实",
            sensitiveText.contains("ClipDescription.EXTRA_IS_SENSITIVE")
        )
        assertTrue(
            "[$MANAGER_SOURCE] copySensitiveChars 未设 EXTRA_IS_SENSITIVE：CharArray 通道绕过敏感标记",
            sensitiveChars.contains("ClipDescription.EXTRA_IS_SENSITIVE")
        )
        assertFalse(
            "[$MANAGER_SOURCE] copyPlainText 不得设敏感标记（普通文本通道应保持无标记）",
            plain.contains("EXTRA_IS_SENSITIVE")
        )
    }

    @Test
    fun `剪贴板文案不得再宣称不进入历史或云同步`() {
        val zh = readSource(ZH_STRINGS)
        val en = readSource(EN_STRINGS)

        val zhSub = stringValue(zh, "sec_clipboard_sub")
        val enSub = stringValue(en, "sec_clipboard_sub")

        assertFalse(
            "[$ZH_STRINGS] 文案过度承诺：EXTRA_IS_SENSITIVE 只是渲染提示，不改变剪贴板行为，" +
                "「不进入剪贴板历史 / 云同步」无法由本应用保证",
            zhSub.contains("剪贴板历史") || zhSub.contains("云同步")
        )
        assertFalse(
            "[$EN_STRINGS] 文案过度承诺：clipboard history / cloud sync 超出该标记的平台语义",
            enSub.contains("history", ignoreCase = true) || enSub.contains("cloud sync", ignoreCase = true)
        )

        // 正向：文案必须落在**可强制**的两件事上（敏感标记 + 自动擦除）
        assertTrue("文案须说明敏感标记（系统提示不显示明文）", zhSub.contains("敏感"))
        assertTrue("文案须说明自动擦除", zhSub.contains("擦除"))
        assertTrue("en 文案须说明 sensitive 标记", enSub.contains("sensitive", ignoreCase = true))
        assertTrue("en 文案须说明自动擦除", enSub.contains("auto-cleared", ignoreCase = true))
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 提取 `<string name="…">…</string>` 的正文 */
    private fun stringValue(xml: String, name: String): String {
        val marker = "<string name=\"$name\">"
        val start = xml.indexOf(marker)
        assertTrue("未找到字符串资源：$name", start >= 0)
        val bodyStart = start + marker.length
        val end = xml.indexOf("</string>", bodyStart)
        assertTrue("字符串资源未闭合：$name", end >= 0)
        return xml.substring(bodyStart, end)
    }

    /**
     * 按花括号配对提取函数体（含函数体本身）。
     * 调用前须剔除注释，否则注释中的 `{` / `}` 会破坏配对。
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到函数：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("函数缺少函数体：$signature", open >= 0)

        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("函数体未闭合：$signature")
    }

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量 */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        const val MANAGER_SOURCE =
            "app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
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
