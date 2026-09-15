package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导出确认令牌的「不可伪造 + 必经校验」守卫（ISSUE-P3-110）。
 *
 * 缺陷背景：二次确认此前**只在 UI 层**成立——`SettingsExportController` 的明文导出入口
 * 不带任何确认参数，`SettingsViewModel` 直接透传，调用方（含任何未来新增调用点）
 * 只要调一次方法即可把整库明文写出，确认弹窗只是「约定了要记得弹」。
 *
 * 本批把确认结果物化为 **`ExportTicket`**，并下沉为控制器入口的**必填参数**。本用例锁定该机制的三条支柱：
 * 1. **不可伪造**：令牌唯一实现类 `IssuedExportTicket` 必须是 `private class`，
 *    且**全仓 `app/src/main` 内不存在第二处 `: ExportTicket` 实现**——否则任何人可凭空造令牌；
 * 2. **必经校验**：控制器入口签名必须要求令牌，且在**序列化之前**用
 *    `ExportConfirmationPolicy.ticketMatches` 校验（校验后于序列化即形同虚设）；
 * 3. **单一签发点**：`IssuedExportTicket(...)` 构造只能出现在 `ExportConfirmationPolicy.confirm` 内，
 *    保证「令牌 ⇒ 已走过确认决策」这一蕴含关系。
 *
 * 断言前剔除注释——整改说明自身会写出被断言的字面量。
 */
class ExportTicketSinkGuardTest {

    @Test
    fun `令牌唯一实现类必须文件私有且全仓无第二实现`() {
        val source = stripComments(readSource(CONTROLLER_SOURCE))

        assertTrue(
            "[$CONTROLLER_SOURCE] 令牌唯一实现必须是 private class（文件外不可见）",
            PRIVATE_IMPL.containsMatchIn(source)
        )

        val implementors = mainSourceFiles()
            .filter { IMPLEMENTS_TICKET.containsMatchIn(stripComments(it.readText())) }
            .map { it.name }

        assertEquals(
            "全仓 app/src/main 只允许 $CONTROLLER_SOURCE 持有 ExportTicket 的实现（否则令牌可被伪造）：$implementors",
            listOf(File(CONTROLLER_SOURCE).name),
            implementors
        )
    }

    @Test
    fun `令牌构造只能出现在策略签发函数内`() {
        val source = stripComments(readSource(CONTROLLER_SOURCE))

        val constructions = CONSTRUCT_IMPL.findAll(source).count()
        assertEquals(
            "IssuedExportTicket 只允许出现两处：private class 声明 + confirm 内的唯一签发点" +
                "（多出即存在绕过策略的签发路径）",
            2,
            constructions
        )

        // confirm 为表达式体函数（无花括号），故按「相邻成员」切分其定义区间
        val confirmRegion = source.substringAfter("fun confirm(").substringBefore("fun ticketMatches(")
        assertTrue(
            "令牌构造必须位于 ExportConfirmationPolicy.confirm 内（另一处仅应为 class 声明）",
            CONSTRUCT_IMPL.containsMatchIn(confirmRegion)
        )
    }

    @Test
    fun `明文导出入口必须要求令牌且校验先于序列化`() {
        val source = stripComments(readSource(CONTROLLER_SOURCE))

        listOf(
            "fun exportVaultXmlTo(" to """fun exportVaultXmlTo(targetUri: Uri, ticket: ExportTicket)""",
            "fun exportVaultCsvTo(" to """fun exportVaultCsvTo(targetUri: Uri, ticket: ExportTicket)""",
            // ISSUE-P3-128：密钥文件同属 PLAINTEXT 风险等级，必须一并受令牌门控
            "fun exportKeyFileTo(" to """fun exportKeyFileTo(targetUri: Uri, ticket: ExportTicket)"""
        ).forEach { (signature, expected) ->
            assertTrue(
                "[$CONTROLLER_SOURCE] 入口签名必须要求令牌（缺令牌应无法编译）：期望 $expected",
                source.contains(expected)
            )
            assertTrue("入口不存在：$signature", source.contains(signature))
        }

        val gate = functionBody(source, "private fun exportPlaintextTo(")
        val checkIndex = gate.indexOf("ExportConfirmationPolicy.ticketMatches")
        val serializeIndex = gate.indexOf("exportAndWrite(")

        assertTrue("明文导出统一入口必须做令牌校验", checkIndex >= 0)
        assertTrue("明文导出统一入口必须调用序列化管线", serializeIndex >= 0)
        assertTrue(
            "令牌校验必须先于序列化（否则明文已产出，校验形同虚设）",
            checkIndex < serializeIndex
        )
    }

    /**
     * ISSUE-P3-128：密钥文件导出在 **UI 侧**也必须先经二次确认——
     * 否则控制器虽要求令牌，UI 仍可在 SAF 回调里凭空签发一个（等于没确认）。
     */
    @Test
    fun `密钥文件导出必须先经二次确认弹窗签发令牌`() {
        val code = stripComments(readSource(SCREEN_SOURCE))

        assertTrue(
            "[$SCREEN_SOURCE] SAF 回调须把目标落到「待确认」状态并弹出确认框",
            code.contains("pendingKeyFileUri = uri") && code.contains("showKeyFileExportConfirm = true")
        )
        assertFalse(
            "[$SCREEN_SOURCE] SAF 结果不得直接触发导出（会跳过二次确认）",
            DIRECT_KEYFILE_EXPORT.containsMatchIn(code)
        )
        assertTrue(
            "[$SCREEN_SOURCE] 确认分支必须经 ExportConfirmationPolicy 签发 KEY_FILE 令牌",
            KEY_FILE_TICKET.containsMatchIn(code)
        )
    }

    /** 控制器/策略所在源文件的全部 `main` 源码文件 */
    private fun mainSourceFiles(): List<File> =
        File(repositoryRoot, "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
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
        const val CONTROLLER_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExportController.kt"
        const val SCREEN_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/DatabaseSettingsScreen.kt"

        /** 令牌唯一实现必须是 private class（防止文件外构造） */
        val PRIVATE_IMPL = Regex("""private\s+class\s+IssuedExportTicket\s*\(""")

        /**
         * 实现令牌的类声明形态：构造参数列表的右括号后紧接 `: ExportTicket`。
         * （不能用裸 `: ExportTicket`——`ticket: ExportTicket` 这类**参数**写法会误命中。）
         */
        val IMPLEMENTS_TICKET = Regex("""\)\s*:\s*ExportTicket\b""")

        /** 令牌实现的构造调用（签发点，须唯一且位于 confirm 内） */
        val CONSTRUCT_IMPL = Regex("""IssuedExportTicket\s*\(""")

        /** 非法形态：SAF 结果直接触发密钥文件导出（跳过二次确认） */
        val DIRECT_KEYFILE_EXPORT = Regex("""uri\?\.let\(onExportKeyFile\)""")

        /** 密钥文件确认分支的令牌签发形态 */
        val KEY_FILE_TICKET = Regex(
            """ExportConfirmationPolicy\.confirm\(\s*kind\s*=\s*ExportArtifactKind\.KEY_FILE\s*,"""
        )

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

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
