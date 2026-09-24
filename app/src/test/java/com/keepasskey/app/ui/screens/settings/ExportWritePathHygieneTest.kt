package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导出写盘路径的卫生守卫（ISSUE-P3-86 整库缓冲清零 + ISSUE-P3-89 审计标记熵）。
 *
 * 两条缺陷都出在**同一条写盘管线**上：
 * - **P3-86（审计 F-02，MEDIUM）**：`exportAndWrite` 把整库序列化结果（明文 XML / CSV 尤甚，
 *   该数组是整库全部字段值的明文副本）写盘后**不清零**，随局部变量出栈静默留存至 GC；
 * - **P3-89（审计 F-07）**：审计标记的短摘要每字节只取低 4 bit，使 8 个 hex 字符中
 *   4 个恒为 `'0'`，有效熵被削到 ≤16 bit。
 *
 * 前者以**源码形态守卫**锁定（该路径需要真实 `android.net.Uri` + `ContentResolver`，
 * JVM 单测下 `Uri` 方法即抛 `Stub!`，无法构造行为用例——该缺口在 §64.3 如实登记）；
 * 后者为**行为级**断言（纯函数，可直接测）。
 */
class ExportWritePathHygieneTest {

    @Test
    fun `写盘管线必须在写出后清零整库序列化缓冲`() {
        val code = stripComments(readSource(CONTROLLER_SOURCE))
        val body = functionBody(code, "private suspend fun exportAndWrite(")

        val writeIndex = body.indexOf("os.write(bytes)")
        val wipeIndex = body.indexOf("bytes?.fill(0)")

        assertTrue("[$CONTROLLER_SOURCE] 写盘管线缺失，无法判定清零时机", writeIndex >= 0)
        assertTrue(
            "[$CONTROLLER_SOURCE] 整库序列化缓冲未清零：明文 XML / CSV 副本会随局部变量出栈留存至 GC",
            wipeIndex >= 0
        )
        assertTrue(
            "[$CONTROLLER_SOURCE] 清零必须**晚于**写出（写前清零会导出全零内容）",
            writeIndex < wipeIndex
        )
        assertTrue(
            "[$CONTROLLER_SOURCE] 清零必须置于 finally 中，覆盖写盘成功 / 失败 / 异常三态",
            FINALLY_WIPE.containsMatchIn(body)
        )
    }

    @Test
    fun `审计标记短摘要不得退化为每字节仅取低四位`() {
        // 取足够多的不同目标：旧实现下 8 个 hex 字符中位于高半字节的 4 位**恒为 '0'**，
        // 故「高半字节集合」将恒等于 {'0'}；修正后必然出现非 '0' 字符。
        val highNibbles = TARGETS
            .map { ExportAuditSanitizer.targetMarker(it).substringAfter('#') }
            .map { digest -> digest.filterIndexed { index, _ -> index % 2 == 0 } }
            .flatMap { it.toList() }
            .toSet()

        assertEquals("短摘要取 SHA-256 前 4 字节 ⇒ 8 个 hex 字符", 8, lenOfFirstDigest())
        assertTrue(
            "高半字节恒为 '0' 说明实现仍只取低 4 bit（有效熵 ≤16 bit）：实际集合=$highNibbles",
            highNibbles.any { it != '0' }
        )
    }

    @Test
    fun `审计标记仍保持脱敏与稳定性`() {
        val marker = ExportAuditSanitizer.targetMarker(SENSITIVE_TARGET)

        assertTrue(marker.startsWith("content://com.android.providers.downloads.documents#"))
        assertFalse("脱敏标记不得包含文件名", marker.contains("MySecretVault"))
        assertEquals(
            "同一目标必须稳定（否则审计无法关联同一目的地事件）",
            marker,
            ExportAuditSanitizer.targetMarker(SENSITIVE_TARGET)
        )
    }

    private fun lenOfFirstDigest(): Int =
        ExportAuditSanitizer.targetMarker(TARGETS.first()).substringAfter('#').length

    /**
     * `ISSUE-P3-303` AC④：**导出器不得再物化不可擦 `String`**。
     *
     * 缺陷背景：两个导出器原以 `ProtectedString.readString()` 取受保护字段（口令 / 自定义字段值），
     * 物化出的 `String` 无法清零、驻留至 GC（契约 §4 #19 的「不可擦残留」面）。
     * 整改后三处改走 `useChars` + 新增的 `CharArray` 写出通道 ⇒ 本用例把该形态**锁死**：
     * 任一导出器再出现 `readString()` 即红。
     *
     * 反空转正控制：同一次扫描必须在 `KdbxXmlWriteUtil` 中看到 `CharArray` 重载，
     * 否则说明扫描面已失效（例如路径漂移），「零命中」将不再有判别力。
     */
    @Test
    fun `导出器不得再物化不可擦 String`() {
        listOf("CSV 导出器" to CSV_EXPORTER, "XML 导出器" to XML_EXPORTER).forEach { (label, path) ->
            val code = stripComments(readSource(path))
            assertFalse(
                "[$path] $label 仍出现 readString()——受保护字段须走 useChars + CharArray 写出通道" +
                    "（ISSUE-P3-303）",
                code.contains("readString()")
            )
            assertTrue(
                "[$path] $label 必须确实经 useChars 取受保护字段",
                code.contains("useChars")
            )
        }

        // 正控制：写出通道的 CharArray 重载必须存在且可被同一次扫描看见
        assertTrue(
            "正控制失守：KdbxXmlWriteUtil 未见 CharArray 的 textElement 重载",
            readSource(XML_WRITE_UTIL).contains("text: CharArray")
        )
        assertTrue(
            "正控制失守：KdbxXmlStreamWriter 未见 CharArray 的 text 重载",
            readSource(XML_STREAM_WRITER).contains("value: CharArray")
        )
    }

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
    private fun stripComments(source: String): String = stripCommentsOnly(source)
    private companion object {
        const val CONTROLLER_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExportController.kt"
        const val CSV_EXPORTER =
            "database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt"
        const val XML_EXPORTER =
            "database/src/main/java/com/keepasskey/database/xml/KeePassXmlExporter.kt"
        const val XML_WRITE_UTIL =
            "database/src/main/java/com/keepasskey/database/xml/KdbxXmlWriteUtil.kt"
        const val XML_STREAM_WRITER =
            "database/src/main/java/com/keepasskey/database/xml/KdbxXmlStreamWriter.kt"
        const val SENSITIVE_TARGET =
            "content://com.android.providers.downloads.documents/document/primary%3ADownload%2FMySecretVault.csv"

        /** 用于观测「高半字节是否恒为 0」的目标样本（非敏感常量） */
        val TARGETS = (1..16).map { "content://provider.example/document/target-$it" }

        /** 写出口的 finally 清零形态（`try { … } finally { bytes?.fill(0) }`） */
        val FINALLY_WIPE = Regex("""finally\s*\{\s*bytes\?\.fill\(0\)\s*\}""")


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
