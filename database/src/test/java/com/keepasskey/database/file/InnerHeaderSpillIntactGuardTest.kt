package com.keepasskey.database.file

import com.keepasskey.core.security.BinaryStore
import com.keepasskey.database.exception.KdbxAttachmentSpillMissingException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * ISSUE-P2-310：落盘附件被回收后的**保存 fail-closed** 守卫。
 *
 * 缺陷形态：附件 `cacheDir` 落盘缓存被系统回收后，`BinaryStore` 按 fail-open 契约返回
 * 空流，而 `serialize` 仍按解析期登记的 `spilledSize` 写字段头 ⇒ 写出 0 字节但头部声明
 * `spilledSize + 1` ⇒ 内层头长度自相矛盾 ⇒ 下次打开整库判损坏（不是丢一个附件）。
 * 修复：`serialize` 写每个落盘条目前校验 `sizeOf(key) == spilledSize`，不等即抛
 * [KdbxAttachmentSpillMissingException] 终止本次保存（读侧 fail-open 契约不动）。
 */
class InnerHeaderSpillIntactGuardTest {

    /** 进程内假 store：可模拟「字节被系统回收」——`evict` 后读侧返空、sizeOf 返 0。 */
    private class EvictableBinaryStore : BinaryStore {
        val entries = LinkedHashMap<String, ByteArray>()
        private var counter = 0

        override fun store(bytes: ByteArray): String {
            val key = "k${counter++}"
            entries[key] = bytes.copyOf()
            return key
        }

        override fun storeFromStream(input: InputStream, size: Long): String {
            val bytes = ByteArray(size.toInt())
            DataInputStream(input).readFully(bytes)
            val key = "k${counter++}"
            entries[key] = bytes
            return key
        }

        override fun load(key: String): ByteArray =
            entries[key]?.copyOf() ?: ByteArray(0)

        override fun openStream(key: String): InputStream =
            ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.clear()
        }

        /** 模拟系统回收：仅删字节（保持实例与 key 有效），语义对齐 fail-open 读契约。 */
        fun evict(key: String) {
            entries.remove(key)
        }
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    /** 解析期落盘一个条目并返回（池内仅持 key）。 */
    private fun spilledItem(store: EvictableBinaryStore, size: Int): InnerHeader.BinaryItem {
        val serialized = ByteArrayOutputStream()
            .also { InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, payload(size)))).serialize(it) }
            .toByteArray()
        return InnerHeader.deserialize(
            ByteArrayInputStream(serialized),
            store,
            spillThresholdBytes = 1024
        ).binaries.single()
    }

    @Test
    fun `落盘附件被回收后 serialize 抛类型化异常而非产出矛盾内层头`() {
        val store = EvictableBinaryStore()
        val item = spilledItem(store, size = 4096)
        assertTrue(item.isSpilled)

        // 模拟系统回收 cacheDir 附件（fail-open 读契约原样：load 返空、sizeOf 返 0）
        store.evict(store.entries.keys.first())

        val out = ByteArrayOutputStream()
        val thrown = assertThrows(KdbxAttachmentSpillMissingException::class.java) {
            InnerHeader(binaries = listOf(item)).serialize(out)
        }
        assertTrue(
            "异常必须声明声明量与实际量: ${thrown.message}",
            thrown.message!!.contains("声明=4096") && thrown.message!!.contains("实际=0")
        )
        assertTrue(
            "保存必须在附件字段写出前整体失败——矛盾的字段头与附件内容均不得出现" +
                "（输出仅含此前的流 id / 流密钥两个非附件字段）",
            out.size() < 100
        )
    }

    @Test
    fun `落盘附件部分截断（字节数不符）同样被拦截`() {
        val store = EvictableBinaryStore()
        val item = spilledItem(store, size = 4096)
        // 模拟截断：字节仍在但比声明短
        val key = store.entries.keys.first()
        store.entries[key] = ByteArray(1024)

        assertThrows(KdbxAttachmentSpillMissingException::class.java) {
            InnerHeader(binaries = listOf(item)).serialize(ByteArrayOutputStream())
        }
    }

    @Test
    fun `落盘附件字节完好时 serialize 正常且往返逐字节等价（既有语义不变）`() {
        val store = EvictableBinaryStore()
        val original = payload(4096)
        val item = spilledItem(store, size = 4096)

        val out = ByteArrayOutputStream()
        InnerHeader(binaries = listOf(item)).serialize(out)

        val restored = InnerHeader.deserialize(
            ByteArrayInputStream(out.toByteArray()),
            store,
            spillThresholdBytes = 1024
        ).binaries.single()
        assertTrue(restored.isSpilled)
        assertArrayEquals(original, restored.data)
    }

    @Test
    fun `内存条目（未落盘）不受守卫影响`() {
        val data = payload(512)
        val out = ByteArrayOutputStream()
        InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, data))).serialize(out)

        val restored = InnerHeader.deserialize(ByteArrayInputStream(out.toByteArray())).binaries.single()
        assertArrayEquals(data, restored.data)
    }
}
