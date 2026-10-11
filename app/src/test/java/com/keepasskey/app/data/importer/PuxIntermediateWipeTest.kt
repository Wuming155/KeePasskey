package com.keepasskey.app.data.importer

import com.keepasskey.app.testutil.stripStringsAndComments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1PUX 导入解析中间副本的清零守卫（`ISSUE-P3-581`）。
 *
 * 缺陷背景：`parseOrThrow` 从 `PuxArchiveReader.readEntry` 取得的 `export.data`——**整份含全部明文口令
 * 的 JSON 字节**——解码解析后失去引用却全程无 `fill(0)`；`readBounded` 的 `ByteArrayOutputStream`
 * 内部缓冲同样承载解压明文、**对调用方不可达**，与原始 ZIP 容器（由 `VaultImportController.runImport`
 * 的 `finally` 清零）分属三个互不覆盖的副本。
 *
 * 本类两层锁死：
 * 1. **行为断言**：[ErasableByteSink] 的两个清零点（容量增长 / [ErasableByteSink.wipe]）可外部观测，
 *    直接断言内部缓冲被就地清零且已取出的副本独立不受影响；
 * 2. **源码接线断言**：`readBounded` / `readEntry` / `parseOrThrow` 三处的擦除点在**方法内部**、
 *    效果无法由黑盒用例观测——故按本仓既有的「源码接线守卫」范式（见 `CredentialIntermediateWipeTest`）
 *    锁定 finally 形态与「不再使用不可擦缓冲」。
 *
 * 断言前一律剔除字符串与注释（整改说明自身会提及 `ByteArrayOutputStream` 等字样）。
 */
class PuxIntermediateWipeTest {

    // ===== 行为断言：可擦缓冲的两个清零点 =====

    @Test
    fun `擦除内部缓冲后已取出的副本不受影响`() {
        val sink = ErasableByteSink(8)
        val payload = ByteArray(8) { (it + 1).toByte() }
        sink.write(payload, 0, payload.size)
        val copy = sink.copyOut()
        assertArrayEquals(payload, copy)

        sink.wipe()

        assertArrayEquals("擦除不得波及已取出的独立副本（否则调用方持有的明文会被静默清零）", payload, copy)
        assertEquals("擦除后计数归零", 0, sink.size)
    }

    @Test
    fun `擦除后内部缓冲全零`() {
        val sink = ErasableByteSink(8)
        sink.write(ByteArray(8) { 0x5A }, 0, 8)

        sink.wipe()

        assertArrayEquals(
            "wipe 必须就地清零内部缓冲——否则明文副本驻留堆上至 GC（ISSUE-P3-581）",
            ByteArray(8),
            sink.buffer
        )
    }

    @Test
    fun `容量增长时旧缓冲被就地清零`() {
        val sink = ErasableByteSink(4)
        sink.write(ByteArray(4) { 0x5A }, 0, 4)
        val preGrowth = sink.buffer
        assertArrayEquals("增长前旧缓冲应持有已写入明文", ByteArray(4) { 0x5A }, preGrowth)

        sink.write(ByteArray(8) { 0x33 }, 0, 8)

        assertArrayEquals("旧缓冲曾承载明文，切换引用前必须清零", ByteArray(4), preGrowth)
        assertEquals("增长后容量应容纳全部已写入字节", 12, sink.buffer.size)
        assertEquals("增长后取出的副本长度与写入总量一致", 12, sink.copyOut().size)
    }

    // ===== 源码接线断言：不可外部观测的擦除点 =====

    @Test
    fun `readBounded 不再使用不可擦的缓冲且擦除位于 finally`() {
        val code = stripStringsAndComments(readSource(READER_SOURCE))

        assertFalse(
            "PuxArchiveReader 不得再使用 ByteArrayOutputStream——其内部缓冲调用方不可达、无法清零（ISSUE-P3-581）",
            code.contains("ByteArrayOutputStream")
        )
        assertTrue(
            "readBounded 必须在 finally 中调用可擦缓冲的 wipe()，覆盖成功返回与超限抛异常两路径",
            Regex("""finally\s*\{\s*sink\.wipe\(\)\s*\}""").containsMatchIn(code)
        )
    }

    @Test
    fun `readEntry 的解压分片读缓冲在收尾擦除`() {
        val body = functionBody(stripStringsAndComments(readSource(READER_SOURCE)), "fun readEntry(")

        assertTrue(
            "readEntry 的分片读缓冲同样逐段承载解压明文，必须在 finally 中 fill(0)",
            Regex("""finally\s*\{\s*buffer\.fill\(0\)\s*\}""").containsMatchIn(body)
        )
    }

    @Test
    fun `parseOrThrow 的 exportData 在成功与失败路径均清零`() {
        val body = functionBody(stripStringsAndComments(readSource(IMPORTER_SOURCE)), "private fun parseOrThrow(")

        assertTrue(
            "被清对象必须是 PuxArchiveReader.readEntry 返回的 export.data 明文副本",
            body.contains("PuxArchiveReader.readEntry(")
        )
        assertTrue(
            "exportData 必须在 finally 中 fill(0)——覆盖成功 return 与解析失败两条路径",
            Regex("""finally\s*\{\s*exportData\.fill\(0\)\s*\}""").containsMatchIn(body)
        )
        assertTrue(
            "清零必须晚于解码解析（写前清零会把明文喂成全零字节）",
            body.indexOf("exportData.fill(0)") > body.indexOf("decodeStrictUtf8(exportData)")
        )
    }

    /** 抽取指定函数的函数体（自函数签名起，按花括号配对截取）；入参须已剔除字符串与注释。 */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("未找到函数：$signature", start >= 0)
        val bodyStart = code.indexOf('{', start)
        var depth = 0
        var index = bodyStart
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(bodyStart, index + 1)
                }
            }
            index++
        }
        error("函数体括号未闭合：$signature")
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val IMPORTER_SOURCE =
            "app/src/main/java/com/keepasskey/app/data/importer/OnePasswordPuxImporter.kt"
        const val READER_SOURCE =
            "app/src/main/java/com/keepasskey/app/data/importer/PuxArchiveReader.kt"

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 $ROOT_SEARCH_DEPTH 层）")
        }

        const val ROOT_SEARCH_DEPTH = 6
    }
}
