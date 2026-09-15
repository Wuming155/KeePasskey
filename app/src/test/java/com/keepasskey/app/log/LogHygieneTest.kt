package com.keepasskey.app.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P1-10 (ZT-10) 静态日志卫生检查（对齐验收标准 3「单测/静态检查确认 release 产物
 * 无敏感标识日志」）：
 * 1. 除统一日志包装器 [com.keepasskey.core.log.AppLog] 外，任何模块生产源码不得直接
 *    import / 调用 `android.util.Log`；
 * 2. **任何**日志调用不得外传敏感标识（调用包名 / rpId / userName / entryId 等
 *    字符串模板插值）与裸异常 message（`t.message` / `result.message` 等）。
 *
 * ISSUE-P2-69 整改：抽样口径由逐行正则 `(Log|AppLog)\.[edviw]\(` 改为「按日志调用特征
 * 抽取完整调用体（跨行）」——注入型通道 `debugLog` / `debugLogBuffer` 与委托型
 * `preferences.verbose(` 此前完全不在覆盖内。
 */
class LogHygieneTest {

    /** 模块源码根（相对仓库根）；app 模块测试工作目录为 app/，向上回溯定位仓库根 */
    private val sourceRoots: List<File> by lazy {
        val moduleRoots = listOf(
            "app/src/main/java",
            "core/src/main/java",
            "database/src/main/java",
            "crypto/src/main/java",
            "sync/src/main/java"
        )
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val candidate = dir ?: return@repeat
            if (File(candidate, "app/src/main/java").isDirectory &&
                File(candidate, "core/src/main/java").isDirectory
            ) {
                return@lazy moduleRoots.map { File(candidate, it) }
            }
            dir = candidate.parentFile
        }
        error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
    }

    private val kotlinFiles: List<File> by lazy {
        sourceRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }

    @Test
    fun `除 AppLog 包装器外禁止直接引用 android util Log`() {
        val violations = kotlinFiles
            .filter { it.name != "AppLog.kt" }
            .filter { file -> file.useLines { lines -> lines.any { it.contains("android.util.Log") } } }
        assertTrue(
            "以下文件绕过统一日志包装器直接引用 android.util.Log: ${violations.map { it.path }}",
            violations.isEmpty()
        )
    }

    @Test
    fun `日志调用行禁止外传敏感标识插值`() {
        // 调用包名（暴露用户安装应用清单）、rpId / userName（暴露注册站点与账号）、
        // entryId（暴露凭据条目标识）
        val sensitiveIdentifier = Regex(
            """\$(callingPackage|callingPkg|rpId|userName|userDisplayName|entryId|expectedPackage)\b"""
        )
        val violations = logCallLines(kotlinFiles)
            .filter { sensitiveIdentifier.containsMatchIn(it) }
        assertTrue(
            "日志调用携带敏感标识插值: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `日志调用行禁止透传裸异常 message`() {
        val rawExceptionMessage = Regex(
            """\b(t|e|it|c|result|error|ex)\.message\b"""
        )
        val violations = logCallLines(kotlinFiles)
            .filter { rawExceptionMessage.containsMatchIn(it) }
        assertTrue(
            "日志调用透传裸异常 message: $violations",
            violations.isEmpty()
        )
    }

    /**
     * 抽取所有日志调用片段（ISSUE-P2-69）。
     *
     * 抽取口径统一按「日志调用」特征：**接收者标识含 `log`/`Log` 段**（覆盖统一包装器
     * [com.keepasskey.core.log.AppLog] 与 @Inject 注入的日志字段 `debugLog` / `debugLogBuffer`
     * / 未来新增的 `*Log` 字段）或委托型详细日志通道 `preferences.verbose(`，
     * 后接日志级别方法（e/w/i/d/v/verbose/audit/wtf）与左括号；
     * 并按括号配对**跨行**取完整调用体——此前只逐行匹配 `Log.x(` / `AppLog.x(`，
     * 导致 `debugLog.warn(` 通道与换行书写的插值点完全逃逸。
     */
    private val logCallPattern = Regex(
        """(?:\w*(?:[Ll]og|LOG)\w*|preferences)\s*\??\s*\.\s*""" +
            """(?:error|warn|info|debug|verbose|audit|wtf|e|w|i|d|v)\s*\("""
    )

    /**
     * ISSUE-P2-69 防空跑护栏：抽样口径必须真实命中 @Inject 注入的日志通道，
     * 且跨行书写的调用体须被完整抽取——否则上面两条断言会在「抽样恒为空」时伪绿。
     */
    @Test
    fun `日志抽样须覆盖注入通道与跨行调用体`() {
        val samples = logCallLines(kotlinFiles)
        assertTrue(
            "抽样未命中注入型日志通道（debugLog / debugLogBuffer）",
            samples.any { it.contains("debugLog") }
        )
        val synthetic = """
            debugLogBuffer.warn(
                TAG,
                "落盘失败: ${'$'}{saved.error.message}"
            )
        """.trimIndent()
        val detected = findLogCalls(synthetic)
        assertEquals("跨行日志调用未被抽取: $detected", 1, detected.size)
        assertTrue(
            "跨行调用体被截断，续行插值点逃逸: $detected",
            detected.single().contains("saved.error.message")
        )
    }

    /** 抽取所有日志调用片段（携带所在文件，便于定位） */
    private fun logCallLines(files: List<File>): List<String> = files.flatMap { file ->
        findLogCalls(file.readText()).map { "${file.name}: $it" }
    }

    /** 从源码文本抽取全部日志调用片段（跨行取完整调用体）。 */
    private fun findLogCalls(text: String): List<String> = logCallPattern.findAll(text).map { match ->
        text.substring(match.range.first, callEnd(text, match.range.last + 1))
    }.toList()

    /**
     * 自左括号之后推进到配对右括号，返回调用体结束下标。
     * 跳过字符串字面量内的括号（日志消息含半角括号时不会误判配对）。
     */
    private fun callEnd(text: String, fromIndex: Int): Int {
        var depth = 1
        var inString = false
        var index = fromIndex
        while (index < text.length && depth > 0) {
            val ch = text[index]
            when {
                inString -> when (ch) {
                    '\\' -> index++
                    '"' -> inString = false
                }
                ch == '"' -> inString = true
                ch == '(' -> depth++
                ch == ')' -> depth--
            }
            index++
        }
        return index.coerceAtMost(text.length)
    }
}
