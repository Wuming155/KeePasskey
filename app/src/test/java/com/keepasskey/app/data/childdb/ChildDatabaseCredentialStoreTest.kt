package com.keepasskey.app.data.childdb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子库凭据独立通道单测（设计要点 3：凭据隔离与独立清零路径）。
 *
 * 覆盖：克隆语义（调用方数组不被本类擦除、本类只保存副本）、
 * `useCredentials` 交出的副本在闭包返回后**自动清零**（含异常路径）、
 * 单槽位 / 全量清零、空凭据不留空壳槽位、无槽位时如实交空。
 */
class ChildDatabaseCredentialStoreTest {

    private companion object {
        const val REF_ID = "cred-ref-1"
        const val OTHER_REF_ID = "cred-ref-2"
        val PASSWORD = "child-master-pw".toCharArray()
        val KEY_FILE = byteArrayOf(1, 2, 3, 4)
    }

    @Test
    fun `store 只保存克隆副本且不清零调用方数组`() {
        val store = ChildDatabaseCredentialStore()
        val password = PASSWORD.copyOf()
        val keyFile = KEY_FILE.copyOf()

        store.store(REF_ID, password, keyFile)

        // 借用语义：调用方数组原样保留，由调用方自行清零
        assertArrayEquals(PASSWORD, password)
        assertArrayEquals(KEY_FILE, keyFile)
        assertTrue(store.hasCredentials(REF_ID))
        assertEquals(1, store.trackedSlotCount())
    }

    @Test
    fun `useCredentials 交出的副本在闭包返回后自动清零`() = runTest {
        val store = ChildDatabaseCredentialStore()
        store.store(REF_ID, PASSWORD, KEY_FILE)
        var handedPassword: CharArray? = null
        var handedKeyFile: ByteArray? = null

        val observed = store.useCredentials(REF_ID) { password, keyFile ->
            handedPassword = password
            handedKeyFile = keyFile
            password?.concatToString()
        }

        assertEquals("child-master-pw", observed)
        // 闭包内可用，闭包返回后由通道负责清零——比调用方自行清零更强的保证
        assertArrayEquals(CharArray(PASSWORD.size) { '0' }, handedPassword)
        assertArrayEquals(ByteArray(KEY_FILE.size), handedKeyFile)
        assertTrue("槽位本身不受影响", store.hasCredentials(REF_ID))
    }

    @Test
    fun `useCredentials 在闭包抛异常时同样清零副本`() = runTest {
        val store = ChildDatabaseCredentialStore()
        store.store(REF_ID, PASSWORD, null)
        var handedPassword: CharArray? = null

        val thrown = runCatching {
            store.useCredentials(REF_ID) { password, _ ->
                handedPassword = password
                throw IllegalStateException("模拟解密失败")
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertArrayEquals(CharArray(PASSWORD.size) { '0' }, handedPassword)
    }

    @Test
    fun `无槽位时如实交出空凭据而不是造出空密码`() = runTest {
        val store = ChildDatabaseCredentialStore()

        var called = false
        store.useCredentials("不存在的槽位") { password, keyFile ->
            called = true
            assertNull(password)
            assertNull(keyFile)
        }

        assertTrue("闭包仍应被调用，由调用方判定凭据缺失", called)
        assertFalse(store.hasCredentials("不存在的槽位"))
    }

    @Test
    fun `空凭据不留空壳槽位`() {
        val store = ChildDatabaseCredentialStore()
        store.store(REF_ID, PASSWORD, KEY_FILE)
        assertEquals(1, store.trackedSlotCount())

        store.store(REF_ID, null, null)
        assertEquals(0, store.trackedSlotCount())
        assertFalse(store.hasCredentials(REF_ID))

        store.store(REF_ID, CharArray(0), ByteArray(0))
        assertEquals("空数组同样视为无凭据", 0, store.trackedSlotCount())
    }

    @Test
    fun `clear 只清目标槽位而 clearAll 清空全部`() {
        val store = ChildDatabaseCredentialStore()
        store.store(REF_ID, PASSWORD, KEY_FILE)
        store.store(OTHER_REF_ID, PASSWORD, null)

        store.clear(REF_ID)
        assertFalse(store.hasCredentials(REF_ID))
        assertTrue(store.hasCredentials(OTHER_REF_ID))

        store.clearAll()
        assertEquals(0, store.trackedSlotCount())
        assertFalse(store.hasCredentials(OTHER_REF_ID))
    }

    @Test
    fun `重复 store 会先擦除旧值再写入新值`() = runTest {
        val store = ChildDatabaseCredentialStore()
        val oldPassword = "old-pw".toCharArray()
        store.store(REF_ID, oldPassword, null)

        store.store(REF_ID, "new-pw".toCharArray(), null)

        val observed = store.useCredentials(REF_ID) { password, _ -> password?.concatToString() }
        assertEquals("new-pw", observed)
        assertEquals("槽位数不因替换而增长", 1, store.trackedSlotCount())
    }
}
