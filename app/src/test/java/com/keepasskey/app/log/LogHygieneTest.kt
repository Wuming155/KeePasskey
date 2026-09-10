package com.keepasskey.app.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P1-10 (ZT-10) 静态日志卫生检查（对齐验收标准 3「单测/静态检查确认 release 产物
 * 无敏感标识日志」）：
 * 1. 除统一日志包装器 [com.keepasskey.core.log.AppLog] 外，任何模块生产源码不得直接
 *    import / 调用 `android.util.Log`；
 * 2. 任何日志调用行不得外传敏感标识（调用包名 / rpId / userName / entryId 等
 *    字符串模板插值）与裸异常 message（`t.message` / `result.message` 等）。
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

    /** 抽取所有日志调用行（Log.x / AppLog.x） */
    private fun logCallLines(files: List<File>): List<String> = files.flatMap { file ->
        file.useLines { lines ->
            lines.filter { line -> Regex("""\b(Log|AppLog)\.[edviw]\(""").containsMatchIn(line) }
                .toList()
        }
    }
}
