package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「退路面」加固接线守护（ISSUE-P3-106 排除域穷举 + ISSUE-P3-112 SAF 删除归属判定）。
 *
 * 两处的失效形态都是**静默**的，故以静态守卫锁定：
 * 1. **备份 / 迁移排除域**：`res/xml/data_extraction_rules.xml` 若漏掉某个 domain，
 *    该域会**静默**进入云备份或设备迁移面——本仓数据面只有密码库与设置，
 *    不含任何「换机需自动恢复」的义务，故必须**逐域穷举**而非按当前 API 使用情况取舍
 *    （当前 `external` / `device_*` 域暴露为零，仅因外部存储 API 全仓零使用）。
 * 2. **SAF 清理归属判定**：`SafDocumentCleanup.deleteCreatedDocument` 的调用契约是
 *    「只删本流程 `CreateDocument` 创建的文档」；若归属判定（`DocumentsContract.isDocumentUri`）
 *    被删或后移到删除调用之后，任意 URI 都会被无条件删除（用户既有文件可被误删）。
 *
 * 断言前剔除注释——整改说明自身会写出被断言的字面量。
 */
class BackupAndSafHardeningTest {

    @Test
    fun `清单必须引用数据提取规则文件`() {
        val manifest = readSource(MANIFEST_SOURCE)

        assertTrue(
            "[$MANIFEST_SOURCE] 未引用 data-extraction-rules：备份 / 迁移排除规则不会生效",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
        )
    }

    @Test
    fun `云备份与设备迁移必须逐域排除`() {
        val xml = readSource(RULES_SOURCE).replace(XML_COMMENT, "")

        val missing = mutableListOf<String>()
        EXCLUDED_DOMAINS.forEach { domain ->
            val marker = "<exclude domain=\"$domain\" path=\".\" />"
            val found = xml.windowedCountOf(marker)
            // 云备份块与设备迁移块各需一处（两者是**独立**面，不可只覆盖其一）
            if (found < 2) missing += "$domain（实际 $found 处，要求 ≥2）"
        }

        assertTrue(
            "[$RULES_SOURCE] 以下 domain 未被两个块同时排除：$missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `SAF 清理必须在删除前做文档 URI 归属判定`() {
        val code = stripComments(readSource(SAF_CLEANUP_SOURCE))

        val guardIndex = code.indexOf("DocumentsContract.isDocumentUri(context, targetUri)")
        val deleteIndex =
            code.indexOf("DocumentsContract.deleteDocument(context.contentResolver, targetUri)")

        assertTrue(
            "[$SAF_CLEANUP_SOURCE] 缺少文档 URI 归属判定：任意 URI 都会被无条件删除（ISSUE-P3-112）",
            guardIndex >= 0
        )
        assertTrue("[$SAF_CLEANUP_SOURCE] 删除调用形态已变更", deleteIndex >= 0)
        assertTrue(
            "[$SAF_CLEANUP_SOURCE] 归属判定必须**先于**删除调用",
            guardIndex < deleteIndex
        )
        assertTrue(
            "[$SAF_CLEANUP_SOURCE] 非文档 URI 必须早退，不得落到删除调用",
            GUARD_EARLY_RETURN.containsMatchIn(code)
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量 */
    private fun stripComments(source: String): String = stripCommentsOnly(source)
    private fun String.windowedCountOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    private companion object {
        const val MANIFEST_SOURCE = "app/src/main/AndroidManifest.xml"
        const val RULES_SOURCE = "app/src/main/res/xml/data_extraction_rules.xml"
        const val SAF_CLEANUP_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SafDocumentCleanup.kt"

        /**
         * 必须被排空的 domain 全集（官方 `<include>` / `<exclude>` 允许值）：
         * 常规四域 + 应用外部存储 + device-protected 存储四域。
         */
        val EXCLUDED_DOMAINS = listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )

        val XML_COMMENT = Regex("""<!--[\s\S]*?-->""")
        val GUARD_EARLY_RETURN =
            Regex("""if\s*\(!DocumentsContract\.isDocumentUri\(context, targetUri\)\)\s*return""")

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
