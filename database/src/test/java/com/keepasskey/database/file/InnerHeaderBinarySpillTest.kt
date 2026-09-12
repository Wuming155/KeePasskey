package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * ISSUE-P2-24 大附件落盘回归。
 *
 * 覆盖验收标准：
 * ① 超过阈值的附件在读取 / 写回时走磁盘缓存，不整份常驻内存；
 * ④ 不改动 KDBX 字节语义（往返逐字节等价、去重与引用池一致性保持、别名隔离保持）。
 * （② 缓存清理与 ③ 文件权限由 app 侧 `FileBinaryStore` + `SyncCache` 基线承载，
 * 见 SyncCacheStreamingTest 与 DatabaseModule 的锁库观察者接线。）
 */
class InnerHeaderBinarySpillTest {

    /** 进程内假 store：验证落盘/读回/复用语义，不触碰真实文件系统。 */
    private class InMemoryBinaryStore : BinaryStore {
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
            entries[key]?.copyOf() ?: throw IllegalStateException("store 中不存在 key=$key")

        override fun openStream(key: String): InputStream = ByteArrayInputStream(load(key))

        override fun sizeOf(key: String): Long = (entries[key]?.size ?: 0).toLong()

        override fun clear() {
            entries.values.forEach { it.fill(0) }
            entries.clear()
        }
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `超过阈值的池条目在解析期落盘且字节等价`() {
        val data = payload(4096)
        val serialized = ByteArrayOutputStream()
            .also { InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, data))).serialize(it) }
            .toByteArray()

        val store = InMemoryBinaryStore()
        val restored = InnerHeader.deserialize(
            ByteArrayInputStream(serialized),
            store,
            spillThresholdBytes = 1024
        )

        val item = restored.binaries.single()
        assertTrue("超过阈值的条目必须落盘", item.isSpilled)
        assertEquals(data.size.toLong(), item.size)
        assertArrayEquals(data, item.data)
        assertEquals(1, store.entries.size)
    }

    @Test
    fun `等于阈值的池条目仍驻留内存`() {
        val data = payload(1024)
        val serialized = ByteArrayOutputStream()
            .also { InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, data))).serialize(it) }
            .toByteArray()

        val store = InMemoryBinaryStore()
        val restored = InnerHeader.deserialize(
            ByteArrayInputStream(serialized),
            store,
            spillThresholdBytes = 1024
        )

        val item = restored.binaries.single()
        assertFalse("等于阈值不应落盘", item.isSpilled)
        assertEquals(0, store.entries.size)
        assertArrayEquals(data, item.data)
    }

    @Test
    fun `无 store 时解析行为与既往一致（全部驻留内存）`() {
        val data = payload(4096)
        val serialized = ByteArrayOutputStream()
            .also { InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, data))).serialize(it) }
            .toByteArray()

        val restored = InnerHeader.deserialize(ByteArrayInputStream(serialized), null)

        val item = restored.binaries.single()
        assertFalse(item.isSpilled)
        assertArrayEquals(data, item.data)
    }

    @Test
    fun `落盘条目每次读取返回独立副本且改写不外溢`() {
        val data = payload(4096)
        val serialized = ByteArrayOutputStream()
            .also { InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, data))).serialize(it) }
            .toByteArray()

        val store = InMemoryBinaryStore()
        val item = InnerHeader.deserialize(
            ByteArrayInputStream(serialized),
            store,
            spillThresholdBytes = 1024
        ).binaries.single()

        val first = item.data
        val second = item.data
        assertFalse("两次读取不得共享同一数组实例", first === second)

        first[0] = 99
        assertArrayEquals("改写返回值不得影响后续读取", data, item.data)
    }

    @Test
    fun `落盘条目与内存条目内容哈希一致（去重指纹可互通）`() {
        val data = payload(4096)
        val serialized = ByteArrayOutputStream()
            .also { InnerHeader(binaries = listOf(InnerHeader.BinaryItem(0, data))).serialize(it) }
            .toByteArray()

        val store = InMemoryBinaryStore()
        val spilled = InnerHeader.deserialize(
            ByteArrayInputStream(serialized),
            store,
            spillThresholdBytes = 1024
        ).binaries.single()
        val inMemory = InnerHeader.BinaryItem(0, data)

        assertEquals(inMemory.contentHash(), spilled.contentHash())
        assertEquals(inMemory, spilled)
    }

    @Test
    fun `KdbxFile 往返：超过默认阈值的附件落盘且再保存复用同一 store 条目`() {
        val big = payload(1_200_000)
        val password = "SpillRoundTrip#2026".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E1", isProtected = false)),
                        attachments = listOf(KdbxAttachment("big.bin", data = big))
                    )
                )
            )
        )
        val store = InMemoryBinaryStore()

        val saved = ByteArrayOutputStream()
            .also { KdbxFile.save(it, db, password) }
            .toByteArray()
        val loaded = KdbxFile.load(ByteArrayInputStream(saved), password, null, store)

        val pooledItem = loaded.binaries.single()
        assertTrue("超过默认阈值的附件必须在解析期落盘", pooledItem.isSpilled)
        assertEquals(1, store.entries.size)

        val attachment = loaded.rootGroup.entries[0].attachments.single()
        assertArrayEquals("落盘往返必须逐字节等价", big, attachment.data)
        assertEquals(big.size.toLong(), attachment.size)
        assertFalse("附件不得别名池条目数组", attachment.data === pooledItem.data)

        // 再保存：去重应复用同一 store 条目，不重复落盘
        val resaved = ByteArrayOutputStream()
            .also { KdbxFile.save(it, loaded, password) }
            .toByteArray()
        assertEquals("再保存不得重复落盘同一附件", 1, store.entries.size)

        val reloaded = KdbxFile.load(ByteArrayInputStream(resaved), password, null, store)
        assertArrayEquals(
            "二次往返必须仍逐字节等价",
            big,
            reloaded.rootGroup.entries[0].attachments.single().data
        )
    }

    @Test
    fun `KdbxFile 未提供 store 时附件仍驻留内存（默认行为不变）`() {
        val big = payload(1_200_000)
        val password = "SpillAbsent#2026".toCharArray()
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E1", isProtected = false)),
                        attachments = listOf(KdbxAttachment("big.bin", data = big))
                    )
                )
            )
        )

        val saved = ByteArrayOutputStream()
            .also { KdbxFile.save(it, db, password) }
            .toByteArray()
        val loaded = KdbxFile.load(ByteArrayInputStream(saved), password)

        assertFalse(loaded.binaries.single().isSpilled)
        assertArrayEquals(big, loaded.rootGroup.entries[0].attachments.single().data)
    }
}
