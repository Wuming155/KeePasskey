package com.keepasskey.database.file

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-471`（Meta 级自定义图标池擦除）的 database 侧契约用例。
 *
 * 锁定三件事，缺一不可（判据逐条对齐 `KdbxBinaryPoolErasureTest`）：
 *
 * 1. **确实擦到了**（非空跑）：`clearSensitiveData()` 必须把 `customIcons` 的 PNG 字节就地清零
 *    ——把图标池擦除从实现中移除时，本文件第一条用例即失败（反向反校锚点）；
 * 2. **身份集合判定**（P0 护栏）：`clearCustomIconPool(liveIcons)` 只擦**不被存活侧以同一实例**
 *    引用的图标。`KdbxMerger.mergeCustomIcons` 对单侧独有 / 胜出的图标**复用原 `CustomIcon` 实例**
 *    ⇒ 合并采用后的活动库与待丢弃树同时可达同一实例；退化为裸
 *    `customIcons.forEach { it.data.fill(0) }` 会静默清空活动库的图标字节。且判定必须是**实例身份**
 *    而非内容相等（`CustomIcon.equals` 含 uuid / name / 时间 / 字节内容，用错即漏擦）；
 * 3. **收口点接线**：会话终止三事件的 `lock()` 与 `updateDatabaseMeta` / `adoptDatabaseIfUnchanged`
 *    两个换库形态都必须经该判定把图标池擦掉 / 保住。
 *
 * 图标非凭据材料，本条属**清理纪律一致性**补齐（用户 2026-10-03 拍板），不改变任何功能行为。
 */
class KdbxCustomIconPoolErasureTest {

    private fun mkIcon(bytes: Byte, uuid: KdbxUuid = KdbxUuid.random()) =
        CustomIcon(uuid = uuid, data = byteArrayOf(bytes))

    private fun dbWithIcons(vararg icons: CustomIcon): KdbxDatabase =
        KdbxDatabase(
            header = KdbxHeader(
                kdfParameters = com.keepasskey.crypto.kdf.KdfParameters.Aes(
                    seed = ByteArray(16),
                    rounds = 1L
                )
            ),
            rootGroup = KdbxGroup(name = "Root"),
            customIcons = icons.toList()
        )

    private fun zerosCleared(icon: CustomIcon): Boolean = icon.data.all { it == 0.toByte() }

    // ------------------------------------------------------------------ 原语层

    @Test
    fun `clearSensitiveData 清零图标池内字节`() {
        val icon = mkIcon(0x5A)
        val db = dbWithIcons(icon)

        db.clearSensitiveData()

        assertTrue("会话终止收口必须清零图标池字节（移除图标池擦除即红）", zerosCleared(icon))
    }

    @Test
    fun `clearCustomIconPool 按实例身份跳过存活侧共享图标、清零同内容异实例与独有图标`() {
        val shared = mkIcon(0x11)
        val live = dbWithIcons(shared)

        // 与 shared **内容相同（含 uuid）但实例不同**的图标：判定必须走实例身份
        // （CustomIcon.equals 是内容相等，误用 equals 会把它错判为存活而漏擦——本断言即该退化的红灯）
        val sameContentDistinctInstance = CustomIcon(uuid = shared.uuid, data = byteArrayOf(0x11))
        val own = mkIcon(0x22)
        val discarded = dbWithIcons(shared, sameContentDistinctInstance, own)

        discarded.clearCustomIconPool(live.customIcons)

        assertArrayEquals(
            "被存活侧以同一实例引用的图标绝不可被清零（裸 forEach 即红）",
            byteArrayOf(0x11),
            shared.data
        )
        assertTrue("同内容但异实例的图标不在存活身份集内，必须清零", zerosCleared(sameContentDistinctInstance))
        assertTrue("独有图标必须清零", zerosCleared(own))
        assertArrayEquals("存活侧自身的图标不受影响", byteArrayOf(0x11), live.customIcons.single().data)
    }

    @Test
    fun `clearCustomIconPool 存活侧为空时全量清零（会话终止形态）`() {
        val a = mkIcon(0x33)
        val b = mkIcon(0x44)
        val db = dbWithIcons(a, b)

        db.clearCustomIconPool(emptyList())

        assertTrue("无存活别名 ⇒ 图标池全量清零", zerosCleared(a))
        assertTrue("无存活别名 ⇒ 图标池全量清零（逐条）", zerosCleared(b))
    }

    @Test
    fun `copy 共享同一 CustomIcon 实例时 clearSensitiveData 清零即同步反映于共享列表`() {
        // `localDb.copy()` 形态：两个库实例共享同一图标池列表与其中实例。
        // 会话终止路径对该实例的擦除是**有意为之**（共享者随同一事件一并终结）——
        // 本用例锁定「擦了就是真清零」，防实现退化为 no-op 假绿。
        val icon = mkIcon(0x0F)
        val a = dbWithIcons(icon)
        val b = a.copy()
        assertEquals(a.customIcons.single(), b.customIcons.single())

        b.clearSensitiveData()

        assertTrue("共享实例经会话终止收口必须真清零", zerosCleared(icon))
        assertTrue("两个库实例看到同一被清零实例", zerosCleared(a.customIcons.single()))
    }

    // ------------------------------------------------------------------ 收口点接线

    @Test
    fun `lock 会话终止后活动库图标池已被清零`() = runBlocking {
        val icon = mkIcon(0x77)
        val session = com.keepasskey.database.session.DatabaseSession()
        session.setDatabaseForTesting(dbWithIcons(icon))

        session.lock()

        assertTrue("lock() 经 clearSensitiveData 收口，图标池字节必须已清零", zerosCleared(icon))
    }

    @Test
    fun `updateDatabaseMeta 共享图标池全部跳过、不相交池全量清零`() = runBlocking {
        val session = com.keepasskey.database.session.DatabaseSession()

        // 形态①：copy-on-write / 合并——新旧库共享同一图标列表（或含同一实例）
        val shared = mkIcon(0x11)
        val activity = dbWithIcons(shared)
        session.setDatabaseForTesting(activity)
        session.updateDatabaseMeta { it.copy(rootGroup = KdbxGroup(name = "Root2")) }
        assertArrayEquals(
            "copy 形态共享的图标是存活侧本身，绝不可被换库收口清零",
            byteArrayOf(0x11),
            shared.data
        )

        // 形态②：远端库整体接管——新库图标池与旧库池不相交 ⇒ 旧池就地清零（不再滞留 GC）
        val oldIcon = mkIcon(0x22)
        val newOwn = mkIcon(0x33)
        session.setDatabaseForTesting(dbWithIcons(oldIcon))
        session.updateDatabaseMeta { dbWithIcons(newOwn) }

        assertTrue("不相交图标池的换下库必须在 updateDatabaseMeta 收口点被清零", zerosCleared(oldIcon))
        assertArrayEquals("上线新库的图标池不得受影响", byteArrayOf(0x33), newOwn.data)
        assertEquals(1, session.databaseFlow.value!!.customIcons.size)
    }

    @Test
    fun `adoptDatabaseIfUnchanged 采用后下线图标池按身份集合擦除`() = runBlocking {
        val session = com.keepasskey.database.session.DatabaseSession()

        val shared = mkIcon(0x11)
        val oldOnly = mkIcon(0x22)
        val expected = dbWithIcons(shared, oldOnly)
        session.setDatabaseForTesting(expected)

        val newOnly = mkIcon(0x33)
        val adopted = session.adoptDatabaseIfUnchanged(
            expectedAtCycleStart = expected,
            replacement = expected.copy(customIcons = listOf(shared, newOnly))
        )

        assertTrue("前提：采用必须成功", adopted)
        assertArrayEquals("共享图标实例被新库继续引用，绝不可清零", byteArrayOf(0x11), shared.data)
        assertTrue("下线（新库不再引用）的图标必须清零", zerosCleared(oldOnly))
        assertArrayEquals("新库新增图标不受影响", byteArrayOf(0x33), newOnly.data)
    }
}
