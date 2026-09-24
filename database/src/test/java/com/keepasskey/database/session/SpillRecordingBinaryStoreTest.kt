package com.keepasskey.database.session

import com.keepasskey.core.security.BinaryStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * ISSUE-P3-311 项 1：解析期落盘键记录器——失败解析会话的附件残留回滚。
 *
 * 缺陷形态：解析失败（口令错误 / XML 损坏等）时 finally 只擦凭据克隆，本次解析
 * 已经落盘的附件明文滞留 cacheDir（不锁库时每失败一轮残留 ≤ 池体积，锁定才归零）。
 * 整改：解析包一层记录器，失败路径 `purge()` 逐一删除本次新落盘条目。
 */
class SpillRecordingBinaryStoreTest {

    /** 可观测删除动作的假 store（记录 delete 调用与现存内容） */
    private class FakeStore : BinaryStore {
        val entries = LinkedHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()
        private var counter = 0

        override fun store(bytes: ByteArray): String =
            "k${counter++}".also { entries[it] = bytes.copyOf() }

        override fun storeFromStream(input: java.io.InputStream, size: Long): String =
            store(ByteArray(size.toInt()).also { input.read(it) })

        override fun load(key: String): ByteArray = entries[key] ?: ByteArray(0)

        override fun openStream(key: String): java.io.InputStream =
            ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.clear()
        }

        override fun delete(key: String) {
            deleted.add(key)
            entries.remove(key)
        }
    }

    @Test
    fun `记录器登记新落盘键且 purge 逐一删除`() {
        val delegate = FakeStore()
        val recorder = SpillRecordingBinaryStore(delegate)

        val k1 = recorder.store(byteArrayOf(1, 2, 3))
        val k2 = recorder.storeFromStream(ByteArrayInputStream(byteArrayOf(4, 5)), 2)

        assertEquals(2, delegate.entries.size)
        recorder.purge()

        assertEquals("两个新落盘键都必须被删除", listOf(k1, k2), delegate.deleted)
        assertTrue("删除后不得有残留条目", delegate.entries.isEmpty())
    }

    @Test
    fun `purge 幂等且读路径透传委托`() {
        val delegate = FakeStore()
        val recorder = SpillRecordingBinaryStore(delegate)
        val key = recorder.store(byteArrayOf(9))
        recorder.purge()
        recorder.purge()
        assertEquals("重复 purge 不得重复删除", 1, delegate.deleted.size)
        // 读路径透传：load / sizeOf / openStream 走 delegate
        delegate.entries[key] = byteArrayOf(9)
        assertEquals(1L, recorder.sizeOf(key))
        assertArrayEquals(byteArrayOf(9), recorder.load(key))
        assertFalse(recorder.openStream(key).read() == -1)
    }

    @Test
    fun `解析成功路径不触发 purge（键归解析产物所有）`() {
        val delegate = FakeStore()
        val recorder = SpillRecordingBinaryStore(delegate)
        recorder.store(byteArrayOf(1))
        // 成功路径 = 不调 purge；此处仅锁定「记录器自身不越权清理」的边界
        assertEquals(1, delegate.entries.size)
        assertEquals(0, delegate.deleted.size)
    }
}
