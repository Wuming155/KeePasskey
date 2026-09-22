package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-258`（池内擦除 / 契约 Step 4）的 database 侧契约用例。
 *
 * 锁定三件事，缺一不可（`docs/architecture/敏感缓冲所有权契约.md` §6.2 准入② / §7 Step 4）：
 *
 * 1. **确实擦到了**（非空跑）：`clearSensitiveData()` 必须把 ≤ 落盘阈值的池内内联字节
 *    就地清零——把池擦除从实现中移除时，本文件第一条用例即失败（反向反校 #1 的锚点）；
 * 2. **身份集合判定**（P0 护栏）：`clearBinaryPool(liveBinaries)` 只擦**不被存活侧以同一实例**
 *    引用的条目。`KdbxDatabase.copy()` 会共享同一池列表，退化为裸
 *    `binaries.forEach { it.clear() }` 会静默清空存活库的附件；且判定必须是**实例身份**
 *    而非内容相等（`BinaryItem.equals` 按内容比较，用错即漏擦）；
 * 3. **收口点接线**：会话终止三事件（`lock()` / `close()` / 换库前置）与
 *    `updateDatabaseMeta` 的换库形态都必须经该判定把池擦掉 / 保住。
 *
 * 落盘条目（> 落盘阈值）的内联数组恒为空，清零为安全 no-op——其字节归 `BinaryStore`，
 * 由会话终止观察者统一收口（不在本用例面）。
 */
class KdbxBinaryPoolErasureTest {

    private fun dbWithPool(vararg items: InnerHeader.BinaryItem): KdbxDatabase =
        KdbxDatabase(
            header = KdbxHeader(kdfParameters = com.keepasskey.crypto.kdf.KdfParameters.Aes(seed = ByteArray(16), rounds = 1L)),
            rootGroup = KdbxGroup(name = "Root"),
            binaries = items.toList()
        )

    private fun zerosCleared(item: InnerHeader.BinaryItem): Boolean = item.data.all { it == 0.toByte() }

    // ------------------------------------------------------------------ 原语层

    @Test
    fun `clearSensitiveData 清零二进制池内联条目`() {
        val item = InnerHeader.BinaryItem(0, ByteArray(8) { 0x5A })
        val db = dbWithPool(item)

        db.clearSensitiveData()

        assertTrue("会话终止收口必须清零池内内联附件明文（移除池擦除即红）", zerosCleared(item))
    }

    @Test
    fun `clearBinaryPool 按实例身份跳过存活侧共享条目、清零独有与同内容异实例条目`() {
        val shared = InnerHeader.BinaryItem(0, ByteArray(4) { 0x11 })
        val live = dbWithPool(shared)

        // 与 shared **内容相同但实例不同**的条目：判定必须走实例身份（BinaryItem.equals 是内容相等，
        // 误用 equals 会把它错判为存活而漏擦——本断言即该退化的红灯）
        val sameContentDistinctInstance = InnerHeader.BinaryItem(0, ByteArray(4) { 0x11 })
        val own = InnerHeader.BinaryItem(0, ByteArray(4) { 0x22 })
        val discarded = dbWithPool(shared, sameContentDistinctInstance, own)

        discarded.clearBinaryPool(live.binaries)

        assertArrayEquals(
            "被存活侧以同一实例引用的池条目绝不可被清零（裸 forEach 即红）",
            ByteArray(4) { 0x11 },
            shared.data
        )
        assertTrue("同内容但异实例的条目不在存活身份集内，必须清零", zerosCleared(sameContentDistinctInstance))
        assertTrue("独有池条目必须清零", zerosCleared(own))
        assertArrayEquals(
            "存活侧自身的池不受影响",
            ByteArray(4) { 0x11 },
            live.binaries.single().data
        )
    }

    @Test
    fun `clearBinaryPool 存活侧为空时全量清零（会话终止形态）`() {
        val a = InnerHeader.BinaryItem(0, ByteArray(4) { 0x33 })
        val b = InnerHeader.BinaryItem(0, ByteArray(6) { 0x44 })
        val db = dbWithPool(a, b)

        db.clearBinaryPool(emptyList())

        assertTrue("无存活别名 ⇒ 池全量清零", zerosCleared(a))
        assertTrue("无存活别名 ⇒ 池全量清零（逐条）", zerosCleared(b))
    }

    @Test
    fun `落盘条目清零为安全 no-op：内联数组本就为空、size 保持`() {
        val store = object : com.keepasskey.core.security.BinaryStore {
            private var keyCounter = 0
            private val entries = LinkedHashMap<String, ByteArray>()
            override fun store(bytes: ByteArray): String {
                val key = "k${keyCounter++}"
                entries[key] = bytes.copyOf()
                return key
            }

            override fun storeFromStream(input: java.io.InputStream, size: Long): String {
                val bytes = ByteArray(size.toInt())
                java.io.DataInputStream(input).readFully(bytes)
                return store(bytes)
            }

            override fun load(key: String): ByteArray =
                entries[key]?.copyOf() ?: throw IllegalStateException("missing $key")

            override fun openStream(key: String): java.io.InputStream =
                java.io.ByteArrayInputStream(load(key))

            override fun sizeOf(key: String): Long = entries[key]?.size?.toLong() ?: 0L
            override fun clear() = entries.clear()
        }
        val payload = ByteArray(16) { 0x66 }
        val spilled = InnerHeader.BinaryItem(0, store, store.store(payload), payload.size.toLong())
        val db = dbWithPool(spilled)

        db.clearBinaryPool(emptyList())

        assertEquals("落盘条目的 size 语义不受池清零影响", payload.size.toLong(), spilled.size)
        assertArrayEquals("落盘字节归 BinaryStore 所有，池清零不得波及", payload, store.load("k0"))
    }

    // ------------------------------------------------------------------ 收口点接线

    @Test
    fun `lock 会话终止后活动库二进制池已被清零`() = runBlocking {
        val item = InnerHeader.BinaryItem(0, ByteArray(8) { 0x77 })
        val session = com.keepasskey.database.session.DatabaseSession()
        session.setDatabaseForTesting(dbWithPool(item))

        session.lock()

        assertTrue("lock() 经 clearSensitiveData 收口，池内明文必须已清零", zerosCleared(item))
    }

    @Test
    fun `updateDatabaseMeta 共享池（copy 形态）全部跳过、不相交池全量清零`() = runBlocking {
        val session = com.keepasskey.database.session.DatabaseSession()

        // 形态①：copy-on-write / 合并——新旧库共享同一池列表（或含同一实例）
        val shared = InnerHeader.BinaryItem(0, ByteArray(4) { 0x11 })
        val activity = dbWithPool(shared)
        session.setDatabaseForTesting(activity)
        session.updateDatabaseMeta { it.copy(rootGroup = KdbxGroup(name = "Root2")) }
        assertArrayEquals(
            "copy 形态共享的池条目是存活侧本身，绝不可被换库收口清零",
            ByteArray(4) { 0x11 },
            shared.data
        )

        // 形态②：远端库整体接管——新库池与旧库池不相交 ⇒ 旧池就地清零（不再滞留 GC）
        val oldItem = InnerHeader.BinaryItem(0, ByteArray(4) { 0x22 })
        val newOwn = InnerHeader.BinaryItem(0, ByteArray(4) { 0x33 })
        session.setDatabaseForTesting(dbWithPool(oldItem))
        session.updateDatabaseMeta { dbWithPool(newOwn) }

        assertTrue("不相交池的换下库必须在 updateDatabaseMeta 收口点被清零", zerosCleared(oldItem))
        assertArrayEquals(
            "上线新库的池不得受影响",
            ByteArray(4) { 0x33 },
            newOwn.data
        )
        assertEquals(1, session.databaseFlow.value!!.binaries.size)
    }

    @Test
    fun `copy 共享同一 BinaryItem 实例时 clearSensitiveData 清零即同步反映于共享列表`() {
        // `localDb.copy()` 形态：两个库实例共享同一池列表与其中实例。
        // 会话终止路径对该实例的擦除是**有意为之**（共享者随同一事件一并终结）——
        // 本用例锁定「擦了就是真清零」，防实现退化为 no-op 假绿。
        val item = InnerHeader.BinaryItem(0, ByteArray(4) { 0x0F })
        val a = dbWithPool(item)
        val b = a.copy()
        assertEquals(a.binaries.single(), b.binaries.single())

        b.clearSensitiveData()

        assertTrue("共享实例经会话终止收口必须真清零", zerosCleared(item))
        assertTrue("两个库实例看到同一被清零实例", zerosCleared(a.binaries.single()))
    }
}
