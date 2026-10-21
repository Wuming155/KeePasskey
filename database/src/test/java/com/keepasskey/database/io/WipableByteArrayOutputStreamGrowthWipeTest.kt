package com.keepasskey.database.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * `ISSUE-P3-646`：可擦输出流的**扩容代次**必须逐代擦除。
 *
 * ## 缺陷形态
 *
 * `ByteArrayOutputStream` 的所有 `write` 变体（`write(Int)` / `write(byte[])` /
 * `write(byte[],off,len)`）在容量不足时都会经内部的 `grow`/`ensureCapacity` **直接替换**
 * `buf`——旧数组被静默遗弃，`wipe()` 只清得到**最后一任**缓冲。整改前本项目只覆盖了
 * 继承来的那几个入口里的当前数组，扩容前的整份序列化字节（可能是整库明文导出或密文序列化
 * 产物）会一直留到 GC。
 *
 * ## 判据
 *
 * 用 `observeBuffer` 保留**每一代**缓冲的引用（首个 + 每次扩容），逐代断言：
 * 1. 扩容前的旧数组在扩容发生时就被清零，且**不复用**（新一代是新数组）；
 * 2. `wipe()` 后最后一任缓冲也逐字节为 0；
 * 3. **负例（所有权）**：`toByteArray()` 交出的副本与 `bufferedBytesSnapshot()` 产出的快照
 *    不受擦除影响——前者是 R2 的「转移」，后者本身就是副本。
 *
 * 非扩容路径（不触发 grow 的写入）由既有 `WipableByteArrayOutputStreamTest` 锁定。
 */
class WipableByteArrayOutputStreamGrowthWipeTest {

    private val generations = mutableListOf<ByteArray>()
    private val snapshotAtGeneration = mutableListOf<ByteArray>()

    private val observer: (ByteArray) -> Unit = { buffer ->
        generations += buffer
        snapshotAtGeneration += buffer.copyOf()
    }

    private fun newStream(initialSize: Int = 32) =
        WipableByteArrayOutputStream(initialSize, observer)

    @Test
    fun `write整批跨越多次扩容时每一代旧缓冲都在扩容时清零`() {
        val stream = newStream(initialSize = 32)
        // 分批写入（每批 100B）：每批都可能触发一次扩容，覆盖多个容量代次
        val chunks = List(10) { index -> ByteArray(100) { ((index * 100 + it) % 251).toByte() } }

        chunks.forEach { stream.write(it, 0, it.size) }

        assertTrue("必须实际发生多次扩容（否则未覆盖扩容路径）", generations.size >= 3)
        assertTrue(
            "每代都应是新数组（父类扩容不复用旧缓冲）",
            generations.map { System.identityHashCode(it) }.distinct().size == generations.size
        )

        // 非最后一任：扩容瞬间即应清零；最后一任由 wipe() 收口
        generations.dropLast(1).forEachIndexed { index, buffer ->
            assertTrue(
                "第 ${index + 1} 代旧缓冲必须在扩容时清零，实际残留 ${buffer.count { it != 0.toByte() }} 字节",
                buffer.all { it == 0.toByte() }
            )
        }
        assertTrue(
            "前置：至少有一代缓冲在扩容前承载过非零内容（否则断言空转）",
            snapshotAtGeneration.any { it.any { b -> b != 0.toByte() } }
        )

        val expected = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        val copy = stream.toByteArray()
        try {
            assertArrayEquals("扩容实现不得改变输出字节", expected, copy)
        } finally {
            copy.fill(0)
            expected.fill(0)
        }

        stream.wipe()
        assertTrue("wipe 必须清零最后一任缓冲", stream.bufferedBytesSnapshot().all { it == 0.toByte() })
        assertTrue(
            "wipe 后所有代次（含最后一任）都应为 0",
            generations.all { buffer -> buffer.all { it == 0.toByte() } }
        )
    }

    @Test
    fun `write单字节跨越多次扩容时每一代旧缓冲都在扩容时清零`() {
        val stream = newStream(initialSize = 16)
        repeat(500) { stream.write((it * 13 + 5) and 0xFF) }

        assertTrue("必须实际发生多次扩容", generations.size >= 3)
        generations.dropLast(1).forEachIndexed { index, buffer ->
            assertTrue(
                "第 ${index + 1} 代（单字节写入路径）必须在扩容时清零",
                buffer.all { it == 0.toByte() }
            )
        }

        stream.wipe()
        assertTrue("wipe 后最后一任缓冲必须清零", generations.last().all { it == 0.toByte() })
    }

    @Test
    fun `混合写入路径（整批加单字节）的代次同样逐代清零`() {
        val stream = newStream(initialSize = 32)
        stream.write(ByteArray(20) { 1 })
        stream.write(0x7F)
        stream.write(ByteArray(200) { 2 }, 0, 200)
        stream.write(0x3C)
        stream.write(ByteArray(50) { 3 })

        assertTrue("混合路径必须发生扩容", generations.size >= 2)
        generations.dropLast(1).forEach { buffer ->
            assertTrue("扩容前的代次必须已清零", buffer.all { it == 0.toByte() })
        }

        val expected = ByteArray(20) { 1 } + byteArrayOf(0x7F) + ByteArray(200) { 2 } +
            byteArrayOf(0x3C) + ByteArray(50) { 3 }
        val copy = stream.toByteArray()
        try {
            assertArrayEquals("混合写入内容必须逐字节正确", expected, copy)
        } finally {
            copy.fill(0)
            expected.fill(0)
        }
        stream.wipe()
        assertTrue("wipe 后最后一任缓冲必须清零", generations.last().all { it == 0.toByte() })
    }

    @Test
    fun `交付副本与快照不受wipe影响（所有权负例）`() {
        val stream = newStream(initialSize = 16)
        stream.write(ByteArray(100) { (it + 1).toByte() })
        val delivered = stream.toByteArray()
        val snapshot = stream.bufferedBytesSnapshot()

        stream.wipe()

        try {
            assertNotSame("交付物必须是副本，不是内部缓冲本身", delivered, generations.first())
            assertTrue("toByteArray 交出的副本是 R2 转移，wipe 不得清零它", delivered.any { it != 0.toByte() })
            assertTrue("快照本身是副本，不受 wipe 影响", snapshot.any { it != 0.toByte() })
            assertTrue("内部缓冲已清零（负例对照）", generations.last().all { it == 0.toByte() })
        } finally {
            delivered.fill(0)
            snapshot.fill(0)
        }
    }

    @Test
    fun `wipe后继续写入仍可正常工作且旧代次保持清零`() {
        val stream = newStream(initialSize = 16)
        stream.write(ByteArray(64) { 9 })
        stream.wipe()
        // wipe() 不清空容量，最后一任缓冲会被后续写入复用——它是活动缓冲，不算「旧代次」
        val retiredGenerations = generations.dropLast(1)

        stream.write(ByteArray(64) { 4 })
        assertEquals("wipe 后应可继续写入", 64, stream.size())
        retiredGenerations.forEachIndexed { index, buffer ->
            assertTrue(
                "首次 wipe 之前已退役的第 ${index + 1} 代缓冲必须保持清零（容量复用不得复活旧代次）",
                buffer.all { it == 0.toByte() }
            )
        }
        stream.wipe()
        assertTrue("二次 wipe 后当前缓冲清零", stream.bufferedBytesSnapshot().all { it == 0.toByte() })
    }

    @Test
    fun `未扩容时wipe只影响当前缓冲且输出语义不变`() {
        val stream = newStream(initialSize = 1024)
        val payload = ByteArray(100) { (it + 3).toByte() }
        stream.write(payload)
        assertEquals("小载荷不应扩容", 1, generations.size)

        val copy = stream.toByteArray()
        try {
            assertArrayEquals(payload, copy)
        } finally {
            copy.fill(0)
        }
        stream.wipe()
        assertTrue(generations.single().all { it == 0.toByte() })
        payload.fill(0)
    }

    @Test
    fun `越界写入参数必须被拒绝且不污染缓冲`() {
        val stream = newStream(initialSize = 32)
        stream.write(ByteArray(8) { 5 })

        assertThrows(IndexOutOfBoundsException::class.java) {
            stream.write(ByteArray(4), 2, 3)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            stream.write(ByteArray(4), -1, 2)
        }
        assertEquals("越界请求不得改变已写内容", 8, stream.size())
        stream.wipe()
    }

    @Test
    fun `与父类ByteArrayOutputStream输出逐字节一致`() {
        val payload = ByteArray(777) { ((it * 3 + 11) and 0xFF).toByte() }
        val expected = ByteArrayOutputStream().also { it.write(payload, 0, payload.size) }.toByteArray()
        val stream = newStream(initialSize = 8)
        stream.write(payload, 0, payload.size)
        val actual = stream.toByteArray()
        try {
            assertArrayEquals("扩容路径不得改变字节语义", expected, actual)
        } finally {
            expected.fill(0)
            actual.fill(0)
            payload.fill(0)
        }
        stream.wipe()
    }
}
