package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * ISSUE-P3-205：**豁免 regex 宽度机检**——版本界（或 artifactId 白名单）不得只作散文承诺。
     *
     * 缺陷背景：Kotlin 豁免曾为无界 `^pkg:maven/org\.jetbrains\.kotlin/.*$`（无版本界、
     * artifactId 通配）⇒ 一次降级 PR 或传递解析变化即可让 CVSS 闸门对受影响构件保持绿灯；
     * 护栏「<2.4.20 构件出现即失效」只写在 `<notes>`。
     *
     * 两条不变式（对**每条** `<suppress>` 生效）：
     * 1. **版本无界 ⇒ artifactId 必须白名单化**：`@` 之后的版本段为无界通配（`.*`）时，
     *    artifactId 段不得再含 `.*`（先例：androidx.sqlite 的 CPE 产品级误配，版本无界是
     *    误报机理的必然，但 artifactId 逐字枚举 `sqlite(-framework)?` 封住了横向扩散）；
     * 2. **artifactId 通配 ⇒ 版本必须显式有界**：artifactId 段含 `.*` 时，版本段必须落到
     *    显式版本（族）字面量（先例：org.jline `@3\.24\.1`、Kotlin 的显式版本族枚举）。
     */
    @Test
    fun `豁免 regex 必须有版本界或 artifactId 白名单化`() {
        val xml = stripXmlComments(readSource(SUPPRESSIONS_FILE))
        val blocks = SUPPRESS_BLOCK.findAll(xml).map { it.value }.toList()

        val violations = blocks.withIndex().mapNotNull { (index, block) ->
            val regex = packageUrlRegexOf(block)
                ?: return@mapNotNull "#${index + 1}: 缺 <packageUrl regex=\"true\">（结构变更？）"
            val rest = regex
                .removePrefix("^pkg:maven/")
                .removeSuffix("$")
            val at = rest.lastIndexOf('@')
            if (at <= 0) return@mapNotNull "#${index + 1}: regex 未按 `组/artifact@版本` 形态书写：$regex"
            val artifactPart = rest.substring(0, at)
            val versionPart = rest.substring(at + 1)
            val versionUnbounded = versionPart == WILDCARD
            val artifactWildcard = artifactPart.contains(WILDCARD)
            when {
                versionUnbounded && artifactWildcard ->
                    "#${index + 1}: 版本段与 artifactId 段双双无界（最宽形态，豁免即全量放行）：$regex"
                versionUnbounded && artifactPart.isBlank() ->
                    "#${index + 1}: 版本无界且 artifactId 为空：$regex"
                else -> null
            }
        }

        assertTrue(
            "[$SUPPRESSIONS_FILE] 以下豁免 regex 宽度越界（须加版本界或白名单化 artifactId）：$violations",
            violations.isEmpty()
        )
    }

    /**
     * ISSUE-P3-205：Kotlin 豁免的**降级旁路封闭**——运行时 stdlib 的「≥ 修复版」下界
     * 不靠 regex 而靠本断言：直接解析 `gradle/libs.versions.toml` 的 Kotlin 版本，
     * 与 `<notes>` 声明的修复版逐位比对。降级 PR 会先在这里红灯，而非静默享受豁免。
     */
    @Test
    fun `Kotlin 运行时版本不得低于豁免 notes 声明的修复版`() {
        val xml = readSource(SUPPRESSIONS_FILE)
        val kotlinBlock = SUPPRESS_BLOCK.findAll(xml).map { it.value }
            .firstOrNull { it.contains("org\\.jetbrains\\.kotlin") }
        assertNotNull("清单中须存在 org.jetbrains.kotlin 豁免条目（是否已被移除？）", kotlinBlock)

        // notes 声明的修复版必须可机检提取（防「散文承诺」回归：notes 里连版本号都没了）
        val declared = requireNotNull(kotlinBlock).contains(FIX_VERSION)
        assertTrue(
            "Kotlin 豁免 notes 须显式声明修复版 $FIX_VERSION（现无该字面量，护栏退化为散文）",
            declared
        )

        val toml = readSource(LIBS_VERSIONS_TOML)
        val match = KOTLIN_VERSION_ENTRY.find(toml)
        assertNotNull("gradle/libs.versions.toml 须有 `kotlin = \"x.y.z\"` 版本条目", match)
        val current = requireNotNull(match).groupValues[1]
        assertTrue(
            "Kotlin 版本 $current 低于豁免 notes 声明的修复版 $FIX_VERSION——" +
                "降级后本豁免不得再适用，须先重新评估该 suppression（ISSUE-P3-205）",
            compareVersionSegments(current, FIX_VERSION) >= 0
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

    /** 剥离 XML 注释（文件头维护说明与「示例条目」均落在注释里，不参与守卫判定） */
    private fun stripXmlComments(xml: String): String =
        xml.replace(Regex("<!--[\\s\\S]*?-->"), "")

    /** 取出单个 `<suppress>` 块内 `<packageUrl regex="true">` 的 regex 原文，无则返回 null */
    private fun packageUrlRegexOf(block: String): String? {
        val open = block.indexOf(PACKAGE_URL_OPEN)
        val close = block.indexOf(PACKAGE_URL_CLOSE, open)
        if (open < 0 || close <= open) return null
        return block.substring(open + PACKAGE_URL_OPEN.length, close).trim()
    }

    /** 逐段比较点分版本号（长度不等时短侧补 0），返回负 / 零 / 正 */
    private fun compareVersionSegments(left: String, right: String): Int {
        val l = left.split('.').map { it.toIntOrNull() ?: 0 }
        val r = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, r.size)) {
            val li = l.getOrElse(i) { 0 }
            val ri = r.getOrElse(i) { 0 }
            if (li != ri) return li.compareTo(ri)
        }
        return 0
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
        const val LIBS_VERSIONS_TOML = "gradle/libs.versions.toml"

        /** ISSUE-P3-205：Kotlin 豁免 notes 声明的修复版（CVE-2026-53914，厂商公告口径） */
        const val FIX_VERSION = "2.4.20"

        /** 版本条目提取：`kotlin = "2.4.20"`（[versions] 段内的裸字面量行） */
        val KOTLIN_VERSION_ENTRY = Regex("""(?m)^kotlin\s*=\s*"(\d+(?:\.\d+)+)"""")

        /** 无界通配片段（regex 源文中的 `.*`） */
        const val WILDCARD = ".*"

        const val PACKAGE_URL_OPEN = "<packageUrl regex=\"true\">"
        const val PACKAGE_URL_CLOSE = "</packageUrl>"

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
