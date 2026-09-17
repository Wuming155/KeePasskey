package com.keepasskey.app.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 算法 / 数据结构专项批次的**接线守卫**（`ISSUE-P3-162` / `P3-172` / `P3-173`）。
 *
 * 本批改动的验收判据是「**不再产生某类工作**」——源文件内不再有逐次新建的重对象、
 * 不再有全表线性扫描——而「某对象被新建了几次」对运行时用例不可观测。
 * 本仓既有先例（`AutofillAuthResultWiringTest` / `TotpPeriodWiringGuardTest` /
 * `RuntimeIntegrityDetectionSurfaceTest`）一律以**源码文本**作守卫，本类沿用同一口径。
 *
 * **断言前先剥离注释**（§116 的教训）：本批的就地 KDoc 为解释动机**刻意引用了旧写法**
 * （如「原 `sortedBy { it.title.lowercase() }`」），直接全文断言会把「解释为什么改」
 * 判成「没改」。剥离范围：`/* … */` 块注释与**整行** `//` 注释；行尾内联注释不剥离
 * （本类断言的所有旧写法均不位于行尾注释中，故剥离局限不影响判据）。
 *
 * **本类只锁定「接线未被改回」，不构成性能证据**——收益量级未经实测（见批次文档的边界声明）。
 */
class AlgoHotPathGuardsTest {

    @Test
    fun `自动填充字段扫描的 token 切分正则必须为对象级常量`() {
        val source = stripped(SCANNER)
        assertTrue(
            "切分正则必须提为对象级常量（原实现写在 tokensOf 函数体内 ⇒ 每次调用重新编译 Pattern，" +
                "而 scan 对每个节点最多触发 4 次）",
            source.contains("private val TOKEN_SPLIT_REGEX = Regex(")
        )
        assertFalse(
            "不得残留函数内现编译正则的写法",
            source.contains("split(Regex(")
        )
    }

    @Test
    fun `列表页投影必须一次建索引且排序不得逐次小写化`() {
        val source = stripped(PROJECTION)
        assertTrue(
            "面包屑与回收站集合必须复用同一份分组索引",
            source.contains("groupsById") && source.contains("childrenByParent")
        )
        assertTrue("面包屑必须走索引查表 + 前插队列", source.contains("addFirst("))
        assertEquals(
            "两处名称排序都必须改用不敏感比较器（原 `sortedBy { it.title.lowercase() }` 的选择器" +
                "在每次比较中被调用）",
            2,
            Regex("String\\.CASE_INSENSITIVE_ORDER").findAll(source).count()
        )
        assertFalse(
            "排序选择器不得再逐次小写化",
            source.contains(".lowercase()")
        )
        assertFalse(
            "面包屑不得再用全表线性查找",
            source.contains("allGroups.find {")
        )
        assertFalse(
            "回收站后代不得再递归重扫全表（原实现按每个节点 `allGroups.filter { it.parentId == 传入参数 }`）",
            source.contains("allGroups.filter { it.parentId == parentId }")
        )
        assertFalse(
            "不得残留递归展开实现",
            source.contains("fun addDescendants(")
        )
        assertFalse(
            "面包屑不得再用 ArrayList 头插（逐元素搬移）",
            source.contains("breadcrumbs.add(0,")
        )
    }

    @Test
    fun `分组路径批量解析必须只建一次索引`() {
        val source = stripped(GROUP_PATH)
        assertTrue(
            "必须存在接收已建索引的私有实现（原 pathsOf 对每个分组各调一次 fullPathOf，" +
                "而后者每次 associeBy 重建全表）",
            source.contains("private fun pathOfIndexed(")
        )
        assertFalse(
            "pathsOf 不得再对每个分组调用「自建索引」的公开重载",
            source.contains("fullPathOf(groups, group.id)")
        )
    }

    @Test
    fun `WebDAV 响应解析的 DOM 工厂与日期格式必须缓存`() {
        val source = stripped(PROPFIND_PARSER)
        assertEquals(
            "DOM 工厂只允许在（按线程的）缓存初始化处新建一次",
            1,
            Regex("DocumentBuilderFactory\\.newInstance\\(\\)").findAll(source).count()
        )
        assertEquals(
            "HTTP 日期格式只允许在（按线程的）缓存初始化处新建一次",
            1,
            Regex("SimpleDateFormat\\(").findAll(source).count()
        )
        assertTrue(
            "缓存必须存在且每次解析从缓存取工厂（原实现每次响应重建工厂 + 逐项设 6 个特性）",
            source.contains("hardenedFactories") && source.contains("hardenedFactory()")
        )
    }

    @Test
    fun `KDBX XML 解析器的加固特性探测必须为进程级惰性缓存`() {
        val source = stripped(XML_PARSER)
        assertTrue(
            "探测结论必须惰性缓存一次（该类的实例是每次解析新建的，故缓存只能落在 companion）",
            source.contains("by lazy { probeHardenedFeatures() }")
        )
        assertTrue(
            "加固工厂必须按线程缓存（SAXParserFactory 非线程安全），且每次解析仍取全新 SAXParser",
            source.contains("hardenedFactories") &&
                source.contains("private fun buildHardenedParser(): SAXParser = hardenedFactory().newSAXParser()")
        )
    }

    @Test
    fun `OTP 引擎必须走查表而非线性查找与装箱`() {
        val source = stripped(OTP_ENGINE)
        assertTrue(
            "Base32 必须用反查表（原 ALPHABET.indexOf(Char) 是每字符 32 步线性扫描）",
            source.contains("DECODE_TABLE")
        )
        assertTrue(
            "10^n 必须用常量表（原每次取码一遍 10.0.pow）",
            source.contains("POW10") && source.contains("tenPow(digits)")
        )
        assertFalse(
            "不得残留字母表线性查找",
            source.contains("ALPHABET.indexOf(")
        )
        assertFalse(
            "Base32 输出缓冲不得逐字节装箱",
            source.contains("mutableListOf<Byte>")
        )
    }

    private fun stripped(path: String): String = readSource(path)
        .replace(BLOCK_COMMENT, "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCANNER = "app/src/main/java/com/keepasskey/app/autofill/AutofillFieldScanner.kt"
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListProjection.kt"
        const val GROUP_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/GroupPathPresenter.kt"
        const val PROPFIND_PARSER =
            "sync/src/main/java/com/keepasskey/sync/webdav/WebDavPropfindParser.kt"
        const val XML_PARSER =
            "database/src/main/java/com/keepasskey/database/xml/KdbxXmlParser.kt"
        const val OTP_ENGINE = "core/src/main/java/com/keepasskey/core/otp/OtpEngine.kt"

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""", RegexOption.MULTILINE)

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
