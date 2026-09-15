package com.keepasskey.app.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 解锁节流持久化的「删键复位」判定单元测试（ISSUE-P2-45）。
 *
 * 被测缺陷：整改前 [SharedPrefsUnlockThrottleStore.read] 把「三个键全缺」直接等同于全新安装，
 * 于是任何具备节流 prefs 文件写权限的攻击者只要删掉计数 / 锁定截止 / MAC 三个键，
 * 失败计数即归零——MAC 校验对此无能为力（无记录即无 MAC 可校验）。
 *
 * 测试策略：`SharedPreferences` 以 `java.lang.reflect.Proxy` 内存实现模拟（体例同
 * [BiometricCredentialStorageTest]，不引入 Robolectric）；存在性标记由
 * [FakeUnlockThrottleIntegrity] 以内存集合模拟 AndroidKeyStore 条目。**两者互不隶属**，
 * 正是「删除 prefs 键抹不掉标记」这一被测语义得以成立的前提。
 */
class SharedPrefsUnlockThrottleStoreTest {

    private val dbId = "db_personal"

    /** 节流 prefs 文件的内存替身（键 → 值） */
    private val memoryStorage = mutableMapOf<String, Any?>()

    private val integrity = FakeUnlockThrottleIntegrity()

    private val fakeEditor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putInt", "putLong", "putString" -> {
                memoryStorage[args[0] as String] = args[1]
                proxy
            }
            "remove" -> {
                memoryStorage.remove(args[0] as String)
                proxy
            }
            "clear" -> {
                memoryStorage.clear()
                proxy
            }
            "commit" -> true
            else -> proxy
        }
    } as SharedPreferences.Editor

    private val fakePrefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getInt" -> (memoryStorage[args[0] as String] as? Int) ?: (args[1] as Int)
            "getLong" -> (memoryStorage[args[0] as String] as? Long) ?: (args[1] as Long)
            "getString" -> (memoryStorage[args[0] as String] as? String) ?: (args[1] as? String)
            "contains" -> memoryStorage.containsKey(args[0] as String)
            "getAll" -> memoryStorage.toMap()
            "edit" -> fakeEditor
            else -> null
        }
    } as SharedPreferences

    private val fakeContext: Context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
    }

    private lateinit var store: SharedPrefsUnlockThrottleStore

    @Before
    fun setUp() {
        memoryStorage.clear()
        store = SharedPrefsUnlockThrottleStore(fakeContext, integrity)
    }

    /** 攻击面模拟：文件级删除节流 prefs（计数 / 锁定截止 / MAC 三键一并消失） */
    private fun deleteThrottleKeys() = memoryStorage.clear()

    /** 按「键名后缀」篡改整型值，避免在断言里硬编码生产键名前缀 */
    private fun tamperInt(suffix: String, value: Int) {
        val key = memoryStorage.keys.single { it.endsWith(suffix) }
        memoryStorage[key] = value
    }

    private fun removeKeysEndingWith(suffix: String) {
        memoryStorage.keys.filter { it.endsWith(suffix) }.forEach { memoryStorage.remove(it) }
    }

    // ── 「删键复位」旁路（ISSUE-P2-45 的直接整改对象） ──────────────────

    @Test
    fun `三键全缺且无标记时视为全新安装`() {
        val record = store.read(dbId)

        assertTrue("从未写入过的库必须放行", record.integrityIntact)
        assertEquals(0, record.failureCount)
        assertEquals(0L, record.lockoutUntilEpochMs)
    }

    @Test
    fun `写入记录后删除三个键被判定为篡改而非复位`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 4))
        assertTrue("写入必须留下存在性标记", integrity.existenceMarkerPresent(dbId))

        deleteThrottleKeys()

        assertFalse(
            "删键后必须 fail-closed；判为『全新安装』即等于放行在线爆破",
            store.read(dbId).integrityIntact
        )
    }

    @Test
    fun `成功重置后再次删键同样被判定为篡改`() {
        repeat(3) { store.write(dbId, UnlockThrottleRecord(failureCount = it + 1)) }
        store.reset(dbId)
        assertTrue("重置本身是合法操作，记录应保持完整", store.read(dbId).integrityIntact)

        deleteThrottleKeys()

        assertFalse("成功解锁不得成为『清除曾在案证据』的途径", store.read(dbId).integrityIntact)
    }

    @Test
    fun `存在性标记按库独立不影响其他库`() {
        store.write("db_a", UnlockThrottleRecord(failureCount = 1))

        assertTrue(integrity.existenceMarkerPresent("db_a"))
        assertFalse(integrity.existenceMarkerPresent("db_b"))

        // 另一库从未写入：三键全缺仍是全新安装，不得被 db_a 的标记牵连
        assertTrue(store.read("db_b").integrityIntact)
    }

    // ── reset 语义：写零值记录而非删键 ────────────────────────────────

    @Test
    fun `重置写入零值记录且三个键全部在案`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 6, lockoutUntilEpochMs = 42L))
        store.reset(dbId)

        assertEquals("重置必须留下计数 / 锁定截止 / MAC 三个键", 3, memoryStorage.size)

        val record = store.read(dbId)
        assertTrue(record.integrityIntact)
        assertEquals(0, record.failureCount)
        assertEquals(0L, record.lockoutUntilEpochMs)
    }

    @Test
    fun `重置后计数归零且可再次累加`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 5))
        store.reset(dbId)
        store.write(dbId, UnlockThrottleRecord(failureCount = 1))

        assertEquals(1, store.read(dbId).failureCount)
    }

    // ── 升级兼容：老版本安装不得被误判 ───────────────────────────────

    @Test
    fun `老版本安装无标记且三键已删时不误判为篡改`() {
        // 老版本 reset() 会删键；升级后该库既无三键、也无本版新引入的标记条目
        integrity.clearMarker(dbId)
        deleteThrottleKeys()

        assertTrue(
            "无标记即无『曾在案』证据，必须按全新安装放行（否则升级即被误锁）",
            store.read(dbId).integrityIntact
        )
    }

    @Test
    fun `Keystore不可用时标记查询异常按篡改failClosed`() {
        integrity.keystoreBroken = true

        assertFalse(
            "标记查询异常不得被吞成『标记不存在』——那会把 Keystore 故障变成复位旁路",
            store.read(dbId).integrityIntact
        )
    }

    // ── 既有 MAC 语义回归 ────────────────────────────────────────────

    @Test
    fun `记录计数被篡改时MAC校验失败`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 2))
        tamperInt("_unlock_fail_count", 99)

        val record = store.read(dbId)
        assertFalse(record.integrityIntact)
        assertEquals(99, record.failureCount)
    }

    @Test
    fun `MAC键被删除但计数键仍在时校验失败`() {
        store.write(dbId, UnlockThrottleRecord(failureCount = 2))
        removeKeysEndingWith("_unlock_mac")

        assertFalse(store.read(dbId).integrityIntact)
    }
}
