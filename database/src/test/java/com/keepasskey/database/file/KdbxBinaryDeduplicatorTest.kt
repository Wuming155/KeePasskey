package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * KdbxBinaryDeduplicator 附件字节数组隔离回归测试（审计 P1-9）。
 *
 * 旧缺陷：入池 [InnerHeader.BinaryItem] 与新建 [KdbxAttachment] 直接别名共享
 * dataBytes（可能进一步别名旧池 existingPool 的内部数组），外部调用
 * attachment.clear()/close()（Closeable 契约）时会把内层 Header 二进制池中的
 * 数据一并清零，造成附件数据静默损坏。
 */
class KdbxBinaryDeduplicatorTest {

    private fun entryOf(vararg attachments: KdbxAttachment): KdbxEntry =
        KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E", isProtected = false)),
            attachments = attachments.toList()
        )

    @Test
    fun testAttachmentClearDoesNotZeroBinaryPool() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val root = KdbxGroup(name = "Root", entries = listOf(entryOf(KdbxAttachment("a.bin", data = data))))

        val (newRoot, pool) = KdbxBinaryDeduplicator.deduplicate(root, existingPool = emptyList())

        assertEquals(1, pool.size)
        val att = newRoot.entries[0].attachments[0]
        assertArrayEquals(data, att.data)

        // 外部按 Closeable 契约清零附件：池内数据必须完好
        att.clear()
        assertArrayEquals(data, pool[0].data)
    }

    @Test
    fun testPoolDataIsolatedFromExistingPoolAndOldAttachment() {
        val poolData = byteArrayOf(9, 8, 7, 6)
        val existingPool = listOf(InnerHeader.BinaryItem(0, poolData))
        // 旧附件 data 直接别名旧池内部数组（读取路径 BinaryNode 即此形态）
        val oldAtt = KdbxAttachment("b.bin", refIndex = 0, data = poolData)
        val root = KdbxGroup(name = "Root", entries = listOf(entryOf(oldAtt)))

        val (newRoot, newPool) = KdbxBinaryDeduplicator.deduplicate(root, existingPool)

        assertEquals(1, newPool.size)
        // 新池条目内容正确，且不再是旧池数组的别名
        assertArrayEquals(poolData, newPool[0].data)
        assertTrue(newPool[0].data !== poolData)

        // 清零旧附件（其 data 别名旧池数组）：新池与新附件副本均不受污染
        oldAtt.clear()
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), newPool[0].data)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), newRoot.entries[0].attachments[0].data)
    }

    @Test
    fun testDuplicateContentStillDeduplicatedAndIsolated() {
        val shared = byteArrayOf(1, 1, 2, 2)
        val root = KdbxGroup(
            name = "Root",
            entries = listOf(
                entryOf(KdbxAttachment("x.bin", data = shared)),
                entryOf(KdbxAttachment("y.bin", data = shared.clone()))
            )
        )

        val (newRoot, pool) = KdbxBinaryDeduplicator.deduplicate(root, existingPool = emptyList())

        // 相同内容仍去重为同一池索引
        assertEquals(1, pool.size)
        assertEquals(
            newRoot.entries[0].attachments[0].refIndex,
            newRoot.entries[1].attachments[0].refIndex
        )
        // 清零其中一个附件副本不影响另一个附件与池
        newRoot.entries[0].attachments[0].clear()
        assertArrayEquals(shared, newRoot.entries[1].attachments[0].data)
        assertArrayEquals(shared, pool[0].data)
    }

    /**
     * ISSUE-P3-150 回归：落盘附件**不得在每次保存时被重新读回**。
     *
     * 缺陷背景（原条目正文的前提经复核后更正——见 §115 批次）：
     * `BinaryItem.contentHash` 是按**实例** `lazy` 缓存的，而落盘条目的首次计算必须
     * 流式读回整份附件；`candidateItem` 原实现恒以 `withFlags(...)` 新建实例 ⇒ 哈希缓存
     * 每次保存都作废 ⇒ **每次保存把全部落盘附件重新读盘一遍**（百 MB 级附件库即秒级 I/O）。
     *
     * 判别手法：假 store 计数读回次数——第二次归池（= 第二次保存）的读回次数不得增长。
     */
    @Test
    fun testSpilledAttachmentNotReReadOnRepeatSave() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val store = RecordingStore()
        val key = store.store(payload)
        val spilled = InnerHeader.BinaryItem(0, store, key, payload.size.toLong())
        val root = KdbxGroup(
            name = "Root",
            entries = listOf(entryOf(KdbxAttachment("big.bin", refIndex = 0, source = spilled)))
        )

        val (root1, pool1) = KdbxBinaryDeduplicator.deduplicate(root, existingPool = listOf(spilled))
        assertEquals("首次归池需读回一次以计算内容哈希", 1, store.readCount)
        assertEquals(0, root1.entries[0].attachments[0].refIndex)

        // 第二次保存：同一附件不得再被读回
        val (root2, pool2) = KdbxBinaryDeduplicator.deduplicate(root1, existingPool = pool1)
        assertEquals("第二次保存不得再次读盘", 1, store.readCount)
        assertEquals(0, root2.entries[0].attachments[0].refIndex)
        assertEquals(1, pool2.size)

        // 第三次：仍不得增长（缓存跨保存保留）
        KdbxBinaryDeduplicator.deduplicate(root2, existingPool = pool2)
        assertEquals(1, store.readCount)
    }

    /** 计数型假 store：只关心「读回了几次」。 */
    private class RecordingStore : BinaryStore {

        private val entries = LinkedHashMap<String, ByteArray>()
        private var counter = 0

        var readCount = 0
            private set

        override fun store(bytes: ByteArray): String {
            val key = "k${counter++}"
            entries[key] = bytes.copyOf()
            return key
        }

        override fun storeFromStream(input: InputStream, size: Long): String {
            val bytes = ByteArray(size.toInt())
            java.io.DataInputStream(input).readFully(bytes)
            val key = "k${counter++}"
            entries[key] = bytes
            return key
        }

        override fun load(key: String): ByteArray {
            readCount++
            return entries[key]?.copyOf() ?: throw IllegalStateException("store 中不存在 key=$key")
        }

        override fun openStream(key: String): InputStream {
            readCount++
            return java.io.ByteArrayInputStream(
                entries[key] ?: throw IllegalStateException("store 中不存在 key=$key")
            )
        }

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.values.forEach { it.fill(0) }
            entries.clear()
        }
    }
}
