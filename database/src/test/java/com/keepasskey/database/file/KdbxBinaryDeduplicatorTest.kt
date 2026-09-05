package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
