package com.keepasskey.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [KeyFileVaultCopyStore] 单元测试（§411 / ISSUE-P3-448）。
 *
 * JVM 无 Keystore：经 [KeyFileVaultCopyStore.encryptHook] / [decryptHook] 注入
 * 可逆的假封印（与 SyncCredentialSealer 单测同一范式），重点覆盖：
 * 落盘 / 回读闭环、显示名随载荷往返、损坏处置（就地删除）、清除幂等、
 * 密文不含明文与显示名（加密存储口径）、原子写（无 .tmp 残留）。
 */
class KeyFileVaultCopyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可逆假封印：异或固定模式 + iv 为前缀计数（足够让「密文 ≠ 明文」与往返成立） */
    private fun installFakeCrypto(store: KeyFileVaultCopyStore) {
        store.encryptHook = { plaintext ->
            val iv = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
            iv to plaintext.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
        }
        store.decryptHook = { iv, ciphertext ->
            require(iv.size == 12)
            ciphertext.mapIndexed { i, b -> (b.toInt() xor (i and 0xFF)).toByte() }.toByteArray()
        }
    }

    private lateinit var storeDir: File

    private fun newStore(): KeyFileVaultCopyStore =
        KeyFileVaultCopyStore(
            context = null,
            keystoreManager = null
        ).also {
            installFakeCrypto(it)
            storeDir = tmp.newFolder()
            it.baseDirOverride = storeDir
        }

    @Test
    fun `save 后 load 能往返字节与显示名`() {
        val store = newStore()
        val bytes = byteArrayOf(1, 2, 3, 4, 5, -1, -128, 0)
        assertTrue(store.save("db-1", bytes, "my.keyx"))

        val loaded = store.load("db-1")!!
        assertArrayEquals(bytes, loaded.bytes)
        assertEquals("my.keyx", loaded.displayName)
        loaded.bytes.fill(0)
    }

    @Test
    fun `密文不含明文字节与显示名（加密存储口径）`() {
        val store = newStore()
        val bytes = "SECRET-KEY-FILE-CONTENT".toByteArray()
        assertTrue(store.save("db-1", bytes, "display-name.keyx"))

        val raw = File(storeDir, "db-1.kfc").readBytes()
        assertFalse(
            "密文中不得出现完整明文字节序列",
            raw.toList().windowed(bytes.size).any { it.toByteArray().contentEquals(bytes) }
        )
        assertFalse(raw.toString(Charsets.ISO_8859_1).contains("display-name"))
        assertNotEquals(0, raw.size)
    }

    @Test
    fun `损坏副本就地删除并返回 null`() {
        val store = newStore()
        assertTrue(store.save("db-1", byteArrayOf(9, 9), "a.keyx"))
        // 破坏：截到只剩 iv
        val target = File(storeDir, "db-1.kfc")
        target.writeBytes(target.readBytes().copyOf(12))

        assertNull(store.load("db-1"))
        assertFalse(target.exists())
    }

    @Test
    fun `clear 后 load 为 null 且幂等`() {
        val store = newStore()
        store.save("db-1", byteArrayOf(1), "a.keyx")
        store.clear("db-1")
        assertNull(store.load("db-1"))
        // 幂等：对不存在的键重复清除不抛出
        store.clear("db-1")
        store.clear("nonexistent")
    }

    @Test
    fun `clearAll 清空全部副本`() {
        val store = newStore()
        store.save("db-1", byteArrayOf(1), "a.keyx")
        store.save("db-2", byteArrayOf(2), "b.keyx")
        store.clearAll()
        assertNull(store.load("db-1"))
        assertNull(store.load("db-2"))
    }

    @Test
    fun `空字节与空键拒绝保存`() {
        val store = newStore()
        assertFalse(store.save("db-1", ByteArray(0), "a.keyx"))
        assertFalse(store.save("", byteArrayOf(1), "a.keyx"))
        assertNull(store.load(""))
    }

    @Test
    fun `不同库的副本相互隔离`() {
        val store = newStore()
        store.save("db-1", byteArrayOf(1, 1, 1), "one.keyx")
        store.save("db-2", byteArrayOf(2, 2, 2), "two.keyx")

        val one = store.load("db-1")!!
        val two = store.load("db-2")!!
        assertArrayEquals(byteArrayOf(1, 1, 1), one.bytes)
        assertArrayEquals(byteArrayOf(2, 2, 2), two.bytes)
        assertEquals("one.keyx", one.displayName)
        assertEquals("two.keyx", two.displayName)
        one.bytes.fill(0)
        two.bytes.fill(0)
    }

    @Test
    fun `原子写不残留 tmp 文件`() {
        val store = newStore()
        store.save("db-1", byteArrayOf(7), "a.keyx")
        assertTrue(storeDir.listFiles()!!.all { !it.name.endsWith(".tmp") })
    }
}
