package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 供应链豁免清单的**声明一致性与维护纪律**守卫（ISSUE-P3-126②）。
 *
 * 缺陷背景：`dependency-scan.yml` 的说明曾写「suppression 白名单**当前为空白名单**」，
 * 而 `.github/owasp-dependency-suppressions.xml` 实际含**多条**已核实的 `<suppress>`
 * （豁免若干 CVE）——属典型的「声明漂移」：文档说没有豁免，实际有豁免，
 * 于是任何人核对时都会得出错误结论。
 *
 * 本用例把纪律变成机器可查（两条不变式）：
 * 1. **不在 workflow 里复述条目数**：`dependency-scan.yml` 不得再出现「空白名单」式断言——
 *    数字会随每一次豁免登记而失真，正确做法是只声明**纪律**并指向清单文件；
 * 2. **每条豁免必须附核实说明**：清单内每个 `<suppress>` 都必须带非空 `<notes>`
 *    （登记人 / 核实方式 / 归属），杜绝「静默豁免」。
 */
class SupplyChainSuppressionPolicyTest {

    @Test
    fun `工作流说明不得再断言豁免清单为空`() {
        val workflow = readSource(DEPENDENCY_SCAN_WORKFLOW)

        assertFalse(
            "[$DEPENDENCY_SCAN_WORKFLOW] 不得再断言豁免清单为空——条目数会随每次豁免登记而失真；" +
                "应只声明纪律并指向清单文件（见 ISSUE-P3-126）",
            EMPTY_LIST_CLAIM.containsMatchIn(workflow)
        )
        assertTrue(
            "[$DEPENDENCY_SCAN_WORKFLOW] 须指向清单文件本身（唯一真相源）",
            workflow.contains(SUPPRESSIONS_FILE_NAME)
        )
    }

    @Test
    fun `每条豁免必须附带人工核实说明`() {
        val xml = readSource(SUPPRESSIONS_FILE)

        val blocks = SUPPRESS_BLOCK.findAll(xml).map { it.value }.toList()
        assertTrue("未解析出任何 <suppress> 块（文件结构是否已变更？）", blocks.isNotEmpty())

        val withoutNotes = blocks.withIndex()
            .filter { (_, block) -> notesTextOf(block).isBlank() }
            .map { (index, _) -> "#${index + 1}" }

        assertTrue(
            "[$SUPPRESSIONS_FILE] 以下豁免缺 <notes> 人工核实说明（禁止静默豁免）：$withoutNotes",
            withoutNotes.isEmpty()
        )
    }

    /** 取出单个 `<suppress>` 块内 `<notes>` 的正文（剥掉 CDATA 包裹），无则返回空串 */
    private fun notesTextOf(block: String): String {
        val open = block.indexOf(NOTES_OPEN)
        val close = block.indexOf(NOTES_CLOSE)
        if (open < 0 || close <= open) return ""
        return block.substring(open + NOTES_OPEN.length, close)
            .replace(CDATA_OPEN, "")
            .replace(CDATA_CLOSE, "")
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val DEPENDENCY_SCAN_WORKFLOW = ".github/workflows/dependency-scan.yml"
        const val SUPPRESSIONS_FILE = ".github/owasp-dependency-suppressions.xml"

        /** 仅取文件名，避免与上一常量重复匹配 */
        const val SUPPRESSIONS_FILE_NAME = "owasp-dependency-suppressions.xml"

        /** 禁止形态：「空白名单」式断言（含否定词一并覆盖，如「非空白名单」） */
        val EMPTY_LIST_CLAIM = Regex("""空\s*白名单|空白\s*白名单|white\s*list\s*is\s*empty""")

        /** 单个 `<suppress>` 块（非贪婪到对应闭合标签） */
        val SUPPRESS_BLOCK = Regex("""<suppress>[\s\S]*?</suppress>""")

        const val NOTES_OPEN = "<notes>"
        const val NOTES_CLOSE = "</notes>"
        const val CDATA_OPEN = "<![CDATA["
        const val CDATA_CLOSE = "]]>"

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
