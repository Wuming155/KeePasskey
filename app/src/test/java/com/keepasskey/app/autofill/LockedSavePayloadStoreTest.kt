package com.keepasskey.app.autofill

import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LockedSavePayloadStore] 的行为单测（`ENG-20261028-21`）。
 *
 * 以 null 上下文构造 ⇒ 走进程内存回退，覆盖载体自身的生命周期契约：单次消费、AAD 绑定、
 * 过期清理、取消丢弃与容量上限。**真实 AndroidKeyStore 封印、私有目录落盘内容与进程死亡
 * 恢复**由设备侧用例（`LockedSaveRequest*DeviceTest`）证明——宿主 JVM 没有 Keystore，
 * 用假加解密只能验证代码路径，不能替代那部分结论。
 *
 * 假加解密以 AAD 冒充 IV：解封时 AAD 与 IV 必须一致，从而把「令牌绑定」这条真实属性
 * 也纳入宿主可测范围。
 */
class LockedSavePayloadStoreTest {

    private val password = "Synthetic-Password!".toCharArray()
    private val username = "synthetic-user"

    private fun newStore(clock: () -> Long = { 1_000L }): LockedSavePayloadStore =
        LockedSavePayloadStore(context = null).apply {
            sealHook = { aad, plaintext -> Pair(aad.copyOf(), plaintext.copyOf()) }
            openHook = { aad, iv, ciphertext ->
                if (!iv.contentEquals(aad)) throw javax.crypto.AEADBadTagException("aad mismatch")
                ciphertext.copyOf()
            }
            clockHook = clock
        }

    @Test
    fun `封存后按令牌取回用户名与口令且记录随即消失`() {
        val store = newStore()
        val token = "11111111-1111-4111-8111-111111111111"

        assertTrue(store.seal(token, username, password))
        assertTrue(store.hasPayload(token))
        assertEquals(1, store.pendingCount())

        val payload = store.take(token)

        assertEquals(username, payload?.username)
        assertEquals(String(password), payload?.passwordChars?.let { String(it) })
        assertFalse("取回即单次消费，记录必须同时消失", store.hasPayload(token))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `同一令牌不可重复解封`() {
        val store = newStore()
        val token = "22222222-2222-4222-8222-222222222222"
        store.seal(token, username, password)

        assertTrue(store.take(token) != null)
        assertNull("第二次解封必须为空，防一条载荷被两次落库", store.take(token))
    }

    @Test
    fun `令牌不匹配的解封失败且原记录仍保留`() {
        val store = newStore()
        val tokenA = "33333333-3333-4333-8333-333333333333"
        val tokenB = "44444444-4444-4444-8444-444444444444"
        store.seal(tokenA, username, password)

        assertNull("AAD 失配不得解出属于另一个请求的口令", store.take(tokenB))
        assertTrue("解封失败不得顺手销毁记录（瞬时故障不应丢用户新凭据）", store.hasPayload(tokenA))
    }

    @Test
    fun `过期后不可取且记录被清理`() {
        var now = 1_000L
        val store = newStore { now }
        val token = "55555555-5555-4555-8555-555555555555"
        store.seal(token, username, password)
        assertTrue(store.hasPayload(token))

        now += LockedSavePayloadStore.TTL_MS

        assertFalse("超时请求不得再交付口令", store.hasPayload(token))
        assertEquals(0, store.pendingCount())
        assertNull(store.take(token))
    }

    @Test
    fun `取消丢弃后记录即刻消失`() {
        val store = newStore()
        val token = "66666666-6666-4666-8666-666666666666"
        store.seal(token, username, password)

        store.discard(token)
        store.discard(token)

        assertFalse(store.hasPayload(token))
        assertNull(store.take(token))
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `超过容量上限时丢弃最旧记录且最新记录仍可取`() {
        val store = newStore()
        val tokens = (1..LockedSavePayloadStore.MAX_RECORDS + 1).map {
            "77777777-7777-4777-8777-7777777777%02d".format(it)
        }
        var now = 1_000L
        tokens.forEach { token ->
            store.clockHook = { now }
            store.seal(token, username, password)
            now += 1L
        }

        assertEquals(LockedSavePayloadStore.MAX_RECORDS, store.pendingCount())
        assertNull("最旧请求被丢弃后只应 fail-closed", store.take(tokens.first()))
        assertTrue(store.take(tokens.last()) != null)
    }

    @Test
    fun `空白令牌或空口令不封存`() {
        val store = newStore()

        assertFalse(store.seal("", username, password))
        assertFalse(store.seal("88888888-8888-4888-8888-888888888888", username, CharArray(0)))
        assertEquals(0, store.pendingCount())
    }

    private fun newPrefsStore(
        prefs: InMemorySharedPreferences,
        clock: () -> Long = { 1_000L }
    ): LockedSavePayloadStore =
        LockedSavePayloadStore(context = prefs.context()).apply {
            sealHook = { aad, plaintext -> Pair(aad.copyOf(), plaintext.copyOf()) }
            openHook = { aad, iv, ciphertext ->
                if (!iv.contentEquals(aad)) throw javax.crypto.AEADBadTagException("aad mismatch")
                ciphertext.copyOf()
            }
            clockHook = clock
        }

    @Test
    fun `基于SharedPreferences持久层封存取回并验证实际存储键被彻底清除`() {
        val prefs = InMemorySharedPreferences()
        val store = newPrefsStore(prefs)
        val token = "88888888-8888-4888-8888-888888888888"

        assertTrue(store.seal(token, username, password))
        assertEquals(3, prefs.storage.size)
        assertTrue(prefs.storage.keys.any { it.endsWith(LockedSavePayloadStore.TIME_SUFFIX) })
        assertTrue(prefs.storage.keys.any { it.endsWith(LockedSavePayloadStore.IV_SUFFIX) })
        assertTrue(prefs.storage.keys.any { it.endsWith(LockedSavePayloadStore.CIPHER_SUFFIX) })

        val payload = store.take(token)
        assertEquals(username, payload?.username)
        assertEquals(String(password), payload?.passwordChars?.let { String(it) })
        assertTrue("取回后持久层键必须被完全清除", prefs.storage.isEmpty())
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `基于SharedPreferences持久层过期自动就地清除对应前缀键`() {
        var now = 1_000L
        val prefs = InMemorySharedPreferences()
        val store = newPrefsStore(prefs) { now }
        val token = "99999999-9999-4999-8999-999999999999"

        assertTrue(store.seal(token, username, password))
        assertEquals(3, prefs.storage.size)

        now += LockedSavePayloadStore.TTL_MS
        assertFalse("超时请求 hasPayload 应返回 false", store.hasPayload(token))
        assertTrue("pruneExpired 必须真正把持久层记录键清除，不留残留键", prefs.storage.isEmpty())
        assertNull(store.take(token))
    }

    @Test
    fun `基于SharedPreferences持久层discardAll完全清空存储`() {
        val prefs = InMemorySharedPreferences()
        val store = newPrefsStore(prefs)
        val token1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val token2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

        store.seal(token1, username, password)
        store.seal(token2, username, password)
        assertEquals(6, prefs.storage.size)
        assertEquals(2, store.pendingCount())

        store.discardAll()
        assertTrue("discardAll 必须把持久层所有键完全清空", prefs.storage.isEmpty())
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `基于SharedPreferences持久层超容量剔除最旧记录的全部键`() {
        val prefs = InMemorySharedPreferences()
        val store = newPrefsStore(prefs)
        val tokens = (1..LockedSavePayloadStore.MAX_RECORDS + 1).map {
            "cccccccc-cccc-4ccc-8ccc-cccccccc%04d".format(it)
        }
        var now = 1_000L
        tokens.forEach { token ->
            store.clockHook = { now }
            store.seal(token, username, password)
            now += 1L
        }

        assertEquals(LockedSavePayloadStore.MAX_RECORDS, store.pendingCount())
        assertEquals(LockedSavePayloadStore.MAX_RECORDS * 3, prefs.storage.size)
        assertNull("最旧记录应被从持久层彻底丢弃", store.take(tokens.first()))
        assertTrue(store.take(tokens.last()) != null)
    }
}