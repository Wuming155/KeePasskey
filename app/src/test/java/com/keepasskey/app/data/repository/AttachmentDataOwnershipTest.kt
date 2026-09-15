package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinarySource
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 附件字节交付侧的所有权口径（ISSUE-P3-105）。
 *
 * 缺陷背景：`VaultEntrySecretReader.getAttachmentData` 原先一律 `attachment.data` 后再
 * `.copyOf()`。由于 [KdbxAttachment.data] 对**落盘附件**已经返回 `source.load()` 的独立副本
 * （见 ISSUE-P3-104 口径），第二份 `.copyOf()` 纯属冗余，且**第一份副本无人持有、无人清零**，
 * 随 GC 静默留存解密后的附件明文。
 *
 * 本用例按来源分别锁死（两侧断言互为「非空跑」证据——旧实现下第 1 例必红）：
 * 1. 落盘来源：只读回一次，且**交出该副本本身**（再次 `copyOf` 即失败）；
 * 2. 内存来源：`data` 是库内数组的借用视图，必须复制后交出（否则调用方清零会污染库内字节）；
 * 3. 下游写出侧（`EntryDetailAttachmentExporter`）：用毕必须清零（静态接线守卫）。
 */
class AttachmentDataOwnershipTest {

    /** 记录型落盘来源：每次 `load()` 返回新的独立副本，并留存供实例身份断言 */
    private class RecordingBinarySource(private val payload: ByteArray) : BinarySource {

        val loadedCopies = mutableListOf<ByteArray>()

        override val size: Long get() = payload.size.toLong()

        override fun load(): ByteArray {
            val copy = payload.copyOf()
            loadedCopies += copy
            return copy
        }

        override fun openStream(): InputStream = ByteArrayInputStream(payload)

        override fun contentHash(): Int = payload.contentHashCode()
    }

    private fun readerFor(entry: KdbxEntry): Pair<VaultEntrySecretReader, String> {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            KdbxDatabase(
                header = KdbxHeader.createDefault(useArgon2 = false),
                rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
            )
        )
        return VaultEntrySecretReader(session, VaultEntryMapper(StringsProvider { _, _ -> "" })) to
            entry.id.toHexString()
    }

    @Test
    fun `落盘附件只读回一次且直接交出该副本`() = runTest {
        val payload = "DISK-ATTACHMENT-BYTES".toByteArray()
        val source = RecordingBinarySource(payload)
        val entry = KdbxEntry(
            attachments = listOf(KdbxAttachment(name = "disk.bin", refIndex = 0, source = source))
        )
        val (reader, entryId) = readerFor(entry)

        val returned = reader.getAttachmentData(entryId, "disk.bin")

        assertNotNull(returned)
        assertArrayEquals(payload, returned)
        assertEquals("落盘路径只应读回一次", 1, source.loadedCopies.size)
        assertSame(
            "落盘路径不得在 source.load() 之后再做 copyOf——第一份副本将无人清零（ISSUE-P3-105）",
            source.loadedCopies.single(),
            returned
        )
    }

    @Test
    fun `内存附件交付调用方独占副本而不外流库内数组`() = runTest {
        val payload = "MEM-ATTACHMENT-BYTES".toByteArray()
        val attachment = KdbxAttachment(name = "mem.bin", refIndex = 0, data = payload)
        val entry = KdbxEntry(attachments = listOf(attachment))
        val (reader, entryId) = readerFor(entry)

        val returned = reader.getAttachmentData(entryId, "mem.bin")

        assertNotNull(returned)
        assertArrayEquals(payload, returned)
        assertNotSame(
            "内存来源的 data 是借用视图，不得直接外流（否则调用方清零即污染库内附件）",
            payload,
            returned
        )

        // 借用契约的负向验证：清零交付副本后库内字节必须完好
        returned!!.fill(0)
        assertArrayEquals("清零交付副本不得污染库内附件字节", payload, attachment.data)
    }

    @Test
    fun `附件缺失或名称不匹配时返回 null`() = runTest {
        val entry = KdbxEntry(
            attachments = listOf(
                KdbxAttachment(name = "mem.bin", refIndex = 0, data = "X".toByteArray()),
                KdbxAttachment(name = "empty.bin", refIndex = 0, data = ByteArray(0))
            )
        )
        val (reader, entryId) = readerFor(entry)

        assertNull("名称不匹配必须返回 null", reader.getAttachmentData(entryId, "missing.bin"))
        assertNull("空字节附件按既有语义返回 null", reader.getAttachmentData(entryId, "empty.bin"))
        assertNull("uuid 非法必须返回 null", reader.getAttachmentData("not-a-uuid", "mem.bin"))
    }

    // ===== 下游写出侧：交付副本用毕必须清零 =====

    @Test
    fun `附件导出写出后必须清零交付副本且清零点晚于写出`() {
        val code = stripComments(readSource(EXPORTER_SOURCE))

        val writeIndex = code.indexOf("os.write(bytes)")
        val wipeIndex = code.indexOf("bytes.fill(0)")

        assertTrue(
            "[$EXPORTER_SOURCE] 缺少 `bytes.fill(0)`：交付给导出路径的解密附件明文将随局部变量" +
                "出栈静默留存至 GC",
            wipeIndex >= 0
        )
        assertTrue(
            "[$EXPORTER_SOURCE] `os.write(bytes)` 缺失，无法判定清零时机",
            writeIndex >= 0
        )
        assertTrue(
            "[$EXPORTER_SOURCE] 清零必须**晚于**写出（写前清零会导出全零附件）",
            writeIndex < wipeIndex
        )
        assertTrue(
            "[$EXPORTER_SOURCE] 清零必须置于 finally 中，覆盖三条早退分支与写出异常路径",
            FINALLY_WIPE.containsMatchIn(code)
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 剔除块注释与行注释——整改说明本身会写出关键字，不剔除即会「注释里的假接线」也通过 */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        const val EXPORTER_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailAttachmentExporter.kt"

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** 写出口的 finally 清零形态（`} finally { bytes.fill(0) }`） */
        val FINALLY_WIPE = Regex("""finally\s*\{\s*bytes\.fill\(0\)\s*\}""")

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
