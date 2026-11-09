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
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 附件字节交付侧的所有权口径（ISSUE-P3-105）。
 *
 * `ISSUE-P3-295`：寻址参数由**文件名**改为**附件下标 `refIndex`**（同名附件按名字取会导出错内容），
 * 本用例的相应断言随之改为下标口径——**只改寻址参数，所有权 / 清零语义的断言原样保留**。
 *
 * 缺陷背景：`VaultEntrySecretReader.getAttachmentData` 原先一律 `attachment.data` 后再
 * `.copyOf()`。由于 [KdbxAttachment.data] 对**落盘附件**已经返回 `source.load()` 的独立副本
 * （见 ISSUE-P3-104 口径），第二份 `.copyOf()` 纯属冗余，且**第一份副本无人持有、无人清零**，
 * 随 GC 静默留存解密后的附件明文。
 *
 * 本用例按来源分别锁死（两侧断言互为「非空跑」证据——旧实现下第 1 例必红）：
 * 1. 落盘来源：只读回一次，且**交出该副本本身**（再次 `copyOf` 即失败）；
 * 2. 内存来源：`data` 是库内数组的借用视图，必须复制后交出（否则调用方清零会污染库内字节）。
 *
 * ENG-20261028-12 整改：原第 3 例以源码文本（`os.write(bytes)` 出现在 `bytes.fill(0)` 之前、
 * 且清零在 `finally` 内）守卫下游 `EntryDetailAttachmentExporter` 的「用毕清零」。该断言只锁
 * 写法，重构（例如把写出与清零收进一个私有 `writeAndWipe` 辅助函数）即误红，却仍不能证明
 * 真实写出的字节被清零。已由设备用例
 * `app/src/androidTest/.../EntryDetailAttachmentExportDeviceTest` 以真实写出 + 缓冲归零替代。
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

        val returned = reader.getAttachmentData(entryId, 0)

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

        val returned = reader.getAttachmentData(entryId, 0)

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

        assertNull("下标越界必须返回 null", reader.getAttachmentData(entryId, 99))
        assertNull("空字节附件按既有语义返回 null", reader.getAttachmentData(entryId, 1))
        assertNull("uuid 非法必须返回 null", reader.getAttachmentData("not-a-uuid", 0))
    }

    // ===== 下游写出侧（`EntryDetailAttachmentExporter`）用毕清零：见 androidTest 同名设备用例 =====
}
