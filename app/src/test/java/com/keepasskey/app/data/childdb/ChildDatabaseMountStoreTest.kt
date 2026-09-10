package com.keepasskey.app.data.childdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子库挂载注册表单测（设计要点 1 的落地点与设计要点 6 的计数数据源）。
 *
 * 覆盖：空态 → 登记 → 计数/快照同步 → 跨实例往返（真实编码解码）→ 摘除回落，
 * 以及解码健壮性（损坏记录整条丢弃、只读标记强制回落、非法别名/来源拒载）。
 */
class ChildDatabaseMountStoreTest {

    private val factory = RecordingContextFactory()

    private fun newStore(): ChildDatabaseMountStore = ChildDatabaseMountStore(factory.context)

    private fun mount(
        id: String,
        alias: String = "子库-$id",
        sourceUri: String = validLocalSource("$id.kdbx"),
        credentialRefId: String = "cred-$id",
        mountedAtEpochMillis: Long = 1L
    ): ChildDatabaseMount = ChildDatabaseMount(
        id = id,
        alias = alias,
        sourceUri = sourceUri,
        sourceKind = ChildDatabaseSourceKind.LOCAL_FILE,
        credentialRefId = credentialRefId,
        mountedAtEpochMillis = mountedAtEpochMillis
    )

    @Test
    fun `初始注册表为空且计数为零`() {
        val store = newStore()

        assertTrue(store.mounts.value.isEmpty())
        assertEquals(0, store.mountedCount.value)
        assertNull(store.findById("任意"))
        assertNull(store.findBySource(validLocalSource()))
    }

    @Test
    fun `登记后计数与快照同步更新并只写自身偏好文件`() {
        val store = newStore()
        val record = mount("m1")

        assertTrue(store.register(record))

        assertEquals(listOf(record), store.mounts.value)
        assertEquals(1, store.mountedCount.value)
        assertEquals(record, store.findById("m1"))
        assertEquals(record, store.findBySource(record.sourceUri))
        // 存储隔离：只访问子库注册表偏好文件，绝不碰库列表偏好（详见同步隔离用例）
        assertEquals(listOf(ChildDatabaseMountStore.PREFS_NAME), factory.accessedNames)
        assertNotNull(factory.prefsOf(ChildDatabaseMountStore.PREFS_NAME)?.rawValue(ChildDatabaseMountStore.K_MOUNTS))
    }

    @Test
    fun `登记记录可跨实例往返且在持久化中不含任何密钥材料`() {
        val first = newStore()
        val record = mount("m1", alias = "工作子库", mountedAtEpochMillis = 42L)
        assertTrue(first.register(record))

        val second = newStore()

        assertEquals(listOf(record.copy(readOnly = true)), second.mounts.value)
        assertEquals(1, second.mountedCount.value)
        // 注册表只承载「别名 + 来源 + 凭据引用标识」等非敏感元数据
        val persisted = factory.prefsOf(ChildDatabaseMountStore.PREFS_NAME)
            ?.rawValue(ChildDatabaseMountStore.K_MOUNTS)
        assertNotNull("注册表必须真实落盘", persisted)
        val encoded = persisted as String
        assertTrue(encoded.contains("工作子库"))
        assertTrue(encoded.contains(record.sourceUri))
        assertFalse("不得持久化任何主密码字段", encoded.contains("password", ignoreCase = true))
    }

    @Test
    fun `相同身份或相同来源的重复登记被拒绝`() {
        val store = newStore()
        assertTrue(store.register(mount("m1", sourceUri = validLocalSource("shared.kdbx"))))

        assertFalse("同身份重复登记应被拒", store.register(mount("m1", sourceUri = validLocalSource("other.kdbx"))))
        assertFalse("同来源重复登记应被拒", store.register(mount("m2", sourceUri = validLocalSource("shared.kdbx"))))
        assertEquals(1, store.mountedCount.value)
    }

    @Test
    fun `摘除后计数回落且持久化同步`() {
        val store = newStore()
        store.register(mount("m1"))
        store.register(mount("m2"))

        assertTrue(store.remove("m1"))

        assertEquals(1, store.mountedCount.value)
        assertEquals(listOf("m2"), store.mounts.value.map { it.id })
        assertFalse("重复摘除应返回 false", store.remove("m1"))
        assertEquals(listOf("m2"), newStore().mounts.value.map { it.id })
    }

    @Test
    fun `解码时损坏记录整条丢弃而不影响其余记录`() {
        val store = newStore()
        store.register(mount("m1"))
        val persisted = factory.prefsOf(ChildDatabaseMountStore.PREFS_NAME)
            ?.rawValue(ChildDatabaseMountStore.K_MOUNTS)
        assertNotNull("登记后必须已落盘", persisted)
        val raw = persisted as String

        // 追加三条坏记录：枚举未知、字段数不足、时间为非数字
        val badTimeRecord = listOf(
            "bad-time", "别名", validLocalSource("bad-time.kdbx"), "LOCAL_FILE", "ref", "true"
        ).joinToString(FIELD_SEPARATOR) + FIELD_SEPARATOR + "not-a-number"
        val broken = raw + RECORD_SEPARATOR +
            rawRecord("bad-kind", "别名", validLocalSource("bad-kind.kdbx"), "NOT_A_KIND", "ref", true, 9L) +
            RECORD_SEPARATOR + "只有两个\u0001字段" +
            RECORD_SEPARATOR + badTimeRecord
        factory.seedPrefs(ChildDatabaseMountStore.PREFS_NAME)
            .edit().putString(ChildDatabaseMountStore.K_MOUNTS, broken).apply()

        val reloaded = newStore()

        assertEquals("合法记录必须保留，坏记录逐条丢弃", listOf("m1"), reloaded.mounts.value.map { it.id })
        assertEquals(1, reloaded.mountedCount.value)
    }

    @Test
    fun `解码时非法别名与非法来源的记录被丢弃`() {
        val good = rawRecord(
            id = "good",
            alias = "合法别名",
            sourceUri = validLocalSource("good.kdbx"),
            kindName = ChildDatabaseSourceKind.LOCAL_FILE.name,
            credentialRefId = "ref-good",
            readOnly = true,
            mountedAt = 5L
        )
        val illegalAlias = rawRecord(
            id = "bad-alias",
            // 控制字符 \u0007（BEL）：字段数仍为 7，专测别名校验而非分隔符拆错
            alias = "含控制字符\u0007的别名",
            sourceUri = validLocalSource("bad-alias.kdbx"),
            kindName = ChildDatabaseSourceKind.LOCAL_FILE.name,
            credentialRefId = "ref-bad",
            readOnly = true,
            mountedAt = 6L
        )
        val illegalSource = rawRecord(
            id = "bad-source",
            alias = "别名",
            sourceUri = "relative/not-a-kdbx.txt",
            kindName = ChildDatabaseSourceKind.LOCAL_FILE.name,
            credentialRefId = "ref-bad2",
            readOnly = true,
            mountedAt = 7L
        )
        // 预置「上次运行留下的注册表原文」（未构造 store 即写入），再验证解码时的过滤语义
        factory.seedPrefs(ChildDatabaseMountStore.PREFS_NAME)
            .edit()
            .putString(
                ChildDatabaseMountStore.K_MOUNTS,
                listOf(good, illegalAlias, illegalSource).joinToString(RECORD_SEPARATOR)
            )
            .apply()

        val reloaded = newStore()

        assertEquals(listOf("good"), reloaded.mounts.value.map { it.id })
    }

    @Test
    fun `持久化被改写为可写也一律按只读装载`() {
        // 预置一条被改写为 readOnly=false 的持久化记录：装载时必须强制回落为只读
        factory.seedPrefs(ChildDatabaseMountStore.PREFS_NAME)
            .edit()
            .putString(
                ChildDatabaseMountStore.K_MOUNTS,
                rawRecord(
                    id = "m1",
                    alias = "别名",
                    sourceUri = validLocalSource("m1.kdbx"),
                    kindName = ChildDatabaseSourceKind.LOCAL_FILE.name,
                    credentialRefId = "ref-1",
                    readOnly = false,
                    mountedAt = 3L
                )
            )
            .apply()

        val store = newStore()

        assertEquals(1, store.mountedCount.value)
        assertTrue("只读标记不得由持久化数据开启可写", store.mounts.value.single().readOnly)
    }

    private fun rawRecord(
        id: String,
        alias: String,
        sourceUri: String,
        kindName: String,
        credentialRefId: String,
        readOnly: Boolean,
        mountedAt: Long
    ): String = listOf(
        id, alias, sourceUri, kindName, credentialRefId, readOnly.toString(), mountedAt.toString()
    ).joinToString(FIELD_SEPARATOR)

    private companion object {
        /** 与 ChildDatabaseMountStore 存储格式一致的分隔符（用例显式构造持久化原文） */
        const val RECORD_SEPARATOR = "\u0002"
        const val FIELD_SEPARATOR = "\u0001"
    }
}
