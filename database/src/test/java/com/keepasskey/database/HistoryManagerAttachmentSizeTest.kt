package com.keepasskey.database.history

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.BinarySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class HistoryManagerAttachmentSizeTest {

    @Test
    fun `附件尺寸只读元数据且估算为固定开销加名称与尺寸`() {
        val source = MetadataOnlyBinarySource(20L)
        val latest = entryWithAttachment("a", source)

        val result = HistoryManager.recordHistorySnapshot(
            currentEntry = latest,
            newEntry = emptyEntry(),
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = 33L
        )

        assertEquals(1, result.history.size)
        assertSame(latest.attachments.single(), result.history.single().attachments.single())
        assertEquals(1, source.sizeReads)
        source.assertNeverRead()
    }

    @Test
    fun `预算恰容纳一项时仅保留最新历史`() {
        val newest = entryWithAttachment("a", MetadataOnlyBinarySource(20L))
        val older = entryWithAttachment("a", MetadataOnlyBinarySource(20L))

        val result = HistoryManager.recordHistorySnapshot(
            currentEntry = newest.copy(history = listOf(older)),
            newEntry = emptyEntry(),
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = 33L
        )

        assertEquals(listOf(newest.id), result.history.map { it.id })
    }

    @Test
    fun `超出Int范围的附件按Long估算`() {
        val entry = entryWithAttachment("", MetadataOnlyBinarySource(Int.MAX_VALUE.toLong() + 1L))

        val result = HistoryManager.recordHistorySnapshot(
            entry,
            emptyEntry(),
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = Int.MAX_VALUE.toLong() + 1L + 12L
        )

        assertEquals(1, result.history.size)
    }

    @Test
    fun `Long边界预算不溢出且小一字节即裁剪`() {
        fun resultFor(budget: Long) = HistoryManager.recordHistorySnapshot(
            entryWithAttachment("a", MetadataOnlyBinarySource(Long.MAX_VALUE - 13L)),
            emptyEntry(),
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = budget
        )

        assertEquals(1, resultFor(Long.MAX_VALUE).history.size)
        assertTrue(resultFor(Long.MAX_VALUE - 1L).history.isEmpty())
    }

    @Test
    fun `两项接近Long上限时只保留最新且不溢出`() {
        val newest = entryWithAttachment("a", MetadataOnlyBinarySource(Long.MAX_VALUE / 2L))
        val older = entryWithAttachment("a", MetadataOnlyBinarySource(Long.MAX_VALUE / 2L))

        val result = HistoryManager.recordHistorySnapshot(
            newest.copy(history = listOf(older)),
            emptyEntry(),
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = Long.MAX_VALUE
        )

        assertEquals(listOf(newest.id), result.history.map { it.id })
    }

    @Test
    fun `负尺寸单项溢出与同项累计溢出稳定拒绝`() {
        val cases = listOf(
            entryWithAttachment("", MetadataOnlyBinarySource(-1L)),
            entryWithAttachment("", MetadataOnlyBinarySource(Long.MAX_VALUE)),
            KdbxEntry(
                attachments = listOf(
                    KdbxAttachment("", source = MetadataOnlyBinarySource(Long.MAX_VALUE / 2L)),
                    KdbxAttachment("", source = MetadataOnlyBinarySource(Long.MAX_VALUE / 2L))
                )
            )
        )

        cases.forEach { entry ->
            val failure = try {
                HistoryManager.recordHistorySnapshot(
                    entry,
                    emptyEntry(),
                    maxHistoryItems = HistoryManager.UNLIMITED,
                    maxHistorySize = Long.MAX_VALUE
                )
                null
            } catch (error: IllegalArgumentException) {
                error
            }
            assertEquals("历史尺寸元数据无效或超出 Long 范围", failure?.message)
        }
    }

    @Test
    fun `数量先裁且尺寸预算只读取入选前三项`() {
        val sources = (1..4).map { MetadataOnlyBinarySource(20L) }
        val newest = entryWithAttachment("a", sources[0]).copy(
            history = listOf(entryWithAttachment("a", sources[1]), entryWithAttachment("a", sources[2]), entryWithAttachment("a", sources[3]))
        )

        val result = HistoryManager.recordHistorySnapshot(
            newest,
            emptyEntry(),
            maxHistoryItems = 3,
            maxHistorySize = 66L
        )

        assertEquals(2, result.history.size)
        assertEquals(listOf(1, 1, 1, 0), sources.map { it.sizeReads })
        sources.forEach { it.assertNeverRead() }
    }

    @Test
    fun `零与负上限保留原有禁用及清空语义`() {
        val noCountRead = MetadataOnlyBinarySource(20L)
        val entry = entryWithAttachment("a", noCountRead)

        assertTrue(
            HistoryManager.recordHistorySnapshot(entry, emptyEntry(), 0, HistoryManager.UNLIMITED_SIZE).history.isEmpty()
        )
        assertEquals(0, noCountRead.sizeReads)
        assertEquals(
            1,
            HistoryManager.recordHistorySnapshot(entry, emptyEntry(), -2, HistoryManager.UNLIMITED_SIZE).history.size
        )
        assertEquals(
            1,
            HistoryManager.recordHistorySnapshot(entry, emptyEntry(), HistoryManager.UNLIMITED, -2L).history.size
        )
        assertTrue(
            HistoryManager.recordHistorySnapshot(entry, emptyEntry(), HistoryManager.UNLIMITED, 0L).history.isEmpty()
        )
        noCountRead.assertNeverRead()
    }

    @Test
    fun `保留历史附件身份与字节不被清零`() {
        val expectedBytes = listOf(byteArrayOf(4), byteArrayOf(5), byteArrayOf(6))
        val byteArrays = expectedBytes.map { it.copyOf() }
        val attachments = byteArrays.mapIndexed { index, bytes -> KdbxAttachment("$index", data = bytes) }
        val newest = KdbxEntry(attachments = listOf(attachments[0]), history = listOf(
            KdbxEntry(attachments = listOf(attachments[1])),
            KdbxEntry(attachments = listOf(attachments[2]))
        ))

        val result = HistoryManager.recordHistorySnapshot(
            newest,
            emptyEntry(),
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = Long.MAX_VALUE
        )

        assertEquals(3, result.history.size)
        result.history.forEachIndexed { index, snapshot ->
            assertSame(attachments[index], snapshot.attachments.single())
            assertArrayEquals(expectedBytes[index], snapshot.attachments.single().data)
        }

        val rolledBack = HistoryManager.rollbackToSnapshot(
            result,
            historySnapshotIndex = 1,
            maxHistoryItems = HistoryManager.UNLIMITED,
            maxHistorySize = Long.MAX_VALUE
        )
        assertSame(attachments[1], rolledBack.attachments.single())
        assertArrayEquals(expectedBytes[1], rolledBack.attachments.single().data)
        assertSame(attachments[0], rolledBack.history[1].attachments.single())
        assertSame(attachments[2], rolledBack.history[2].attachments.single())
        assertArrayEquals(expectedBytes[0], rolledBack.history[1].attachments.single().data)
        assertArrayEquals(expectedBytes[2], rolledBack.history[2].attachments.single().data)
    }

    private fun emptyEntry(): KdbxEntry = KdbxEntry()

    private fun entryWithAttachment(name: String, source: BinarySource): KdbxEntry =
        KdbxEntry(attachments = listOf(KdbxAttachment(name = name, source = source)))

    private class MetadataOnlyBinarySource(private val declaredSize: Long) : BinarySource {
        override val size: Long
            get() {
                sizeReads++
                return declaredSize
            }

        var sizeReads: Int = 0
            private set

        var loadCalls: Int = 0
            private set
        var openStreamCalls: Int = 0
            private set
        var contentHashCalls: Int = 0
            private set

        override fun load(): ByteArray {
            loadCalls++
            error("load must not be called")
        }

        override fun openStream(): InputStream {
            openStreamCalls++
            error("openStream must not be called")
        }

        override fun contentHash(): Int {
            contentHashCalls++
            error("contentHash must not be called")
        }

        fun assertNeverRead() {
            assertEquals(0, loadCalls)
            assertEquals(0, openStreamCalls)
            assertEquals(0, contentHashCalls)
        }

    }
}
