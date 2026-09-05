package com.keepasskey.database.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VariantDictionary 单元测试：数值类型宽容访问语义。
 * 回归场景：KeePass 2.x 官方 KDBX4 将 Argon2 parallelism (P) / version (V) 以 UInt32 写出，
 * 读取侧以 getUInt64 访问——历史上的 `as Long` 硬转换直接 ClassCastException，
 * 导致真实 KeePass 2.x 库（Argon2d）无法解锁。
 */
class VariantDictionaryTest {

    @Test
    fun `uint32 item readable as uint64 - official KeePass 2x Argon2 P field`() {
        val dict = VariantDictionary()
        // 模拟 deserialize 路径：TYPE_UINT32 值以 Int 形态驻留
        dict.setUInt32("P", 2L)
        assertEquals(2L, dict.getUInt64("P"))
        assertEquals(2L, dict.getUInt32("P"))
    }

    @Test
    fun `uint32 item above int-max sign boundary reads unsigned`() {
        val dict = VariantDictionary()
        val bigValue = 0xFFFF_FFFFL
        dict.setUInt32("M", bigValue)
        assertEquals(bigValue, dict.getUInt32("M"))
        assertEquals(bigValue, dict.getUInt64("M"))
    }

    @Test
    fun `uint64 item readable as uint64 - M and I fields`() {
        val dict = VariantDictionary()
        dict.setUInt64("M", 64L * 1024 * 1024)
        dict.setUInt64("I", 2L)
        assertEquals(64L * 1024 * 1024, dict.getUInt64("M"))
        assertEquals(2L, dict.getUInt64("I"))
    }

    @Test
    fun `roundtrip serialize deserialize preserves mixed uint widths`() {
        val dict = VariantDictionary()
        dict.setUInt64("I", 10L)
        dict.setUInt32("P", 4L)
        dict.setUInt64("M", 128L * 1024 * 1024)
        dict.setUInt32("V", 0x13L)
        dict.setString("\$UUID", "EF63")

        val restored = VariantDictionary.deserialize(dict.toByteArray())
        assertEquals(10L, restored.getUInt64("I"))
        assertEquals(4L, restored.getUInt64("P"))
        assertEquals(128L * 1024 * 1024, restored.getUInt64("M"))
        assertEquals(0x13L, restored.getUInt32("V"))
        assertEquals("EF63", restored.getString("\$UUID"))
    }

    @Test
    fun `missing key returns null across width getters`() {
        val dict = VariantDictionary()
        assertEquals(null, dict.getUInt32("absent"))
        assertEquals(null, dict.getUInt64("absent"))
    }
}
