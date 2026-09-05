package com.keepasskey.database.crypto

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * P0-5 / P2-6 整改回归：VariantDictionary 长度安全边界与类型宽容语义。
 * keyLen（1..256）与 valLen（0..1MiB）在分配前以类型化 [KdbxCorruptFileException] 拒绝；
 * getter 对类型不符项统一抛类型化异常或宽容自适应，杜绝 ClassCastException。
 */
class VariantDictionarySecurityTest {

    /** 构造变体字典字节流：version + entries + TYPE_NONE 终止符 */
    private fun dictBytes(entries: List<ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        LittleEndianUtil.writeShort(bos, VariantDictionary.VERSION)
        for (entry in entries) bos.write(entry)
        bos.write(VariantDictionary.TYPE_NONE.toInt())
        return bos.toByteArray()
    }

    /** 标准项：type + keyLen + key + valLen + value */
    private fun entry(type: Byte, key: String, value: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        bos.write(type.toInt())
        LittleEndianUtil.writeInt(bos, keyBytes.size)
        bos.write(keyBytes)
        LittleEndianUtil.writeInt(bos, value.size)
        bos.write(value)
        return bos.toByteArray()
    }

    /** 畸形项：声明长度可与实际数据脱钩，用于模拟恶意构造 */
    private fun rawEntry(
        type: Byte,
        keyLen: Int,
        keyBytes: ByteArray,
        valLen: Int,
        value: ByteArray
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(type.toInt())
        LittleEndianUtil.writeInt(bos, keyLen)
        bos.write(keyBytes)
        LittleEndianUtil.writeInt(bos, valLen)
        bos.write(value)
        return bos.toByteArray()
    }

    private fun deserialize(bytes: ByteArray) =
        VariantDictionary.deserialize(ByteArrayInputStream(bytes))

    // ================= P0-5：keyLen / valLen 边界 =================

    @Test
    fun `keyLen 为负数被类型化异常拒绝而非 NegativeArraySizeException`() {
        val bytes = dictBytes(
            listOf(rawEntry(VariantDictionary.TYPE_UINT32, -1, ByteArray(0), 4, ByteArray(4)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `keyLen 为零被拒绝`() {
        val bytes = dictBytes(
            listOf(rawEntry(VariantDictionary.TYPE_STRING, 0, ByteArray(0), 1, byteArrayOf(0x41)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `keyLen 超大值 0x7FFFFFFF 被拒绝而非 OOM`() {
        val bytes = dictBytes(
            listOf(rawEntry(VariantDictionary.TYPE_STRING, 0x7FFFFFFF, ByteArray(0), 1, byteArrayOf(0x41)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `keyLen 超过 256 上限被拒绝`() {
        val longKey = "k".repeat(257)
        val bytes = dictBytes(
            listOf(rawEntry(VariantDictionary.TYPE_STRING, 257, longKey.toByteArray(), 1, byteArrayOf(0x41)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `valLen 为负数被类型化异常拒绝`() {
        val bytes = dictBytes(
            listOf(rawEntry(VariantDictionary.TYPE_STRING, 1, byteArrayOf(0x53), -1, ByteArray(0)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `valLen 超大值 0x7FFFFFFF 被拒绝而非 OOM`() {
        val bytes = dictBytes(
            listOf(rawEntry(VariantDictionary.TYPE_STRING, 1, byteArrayOf(0x53), 0x7FFFFFFF, ByteArray(0)))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `valLen 超过 1MiB 上限被拒绝`() {
        val bytes = dictBytes(
            listOf(
                rawEntry(
                    VariantDictionary.TYPE_STRING, 1, byteArrayOf(0x53),
                    VariantDictionary.MAX_VALUE_LENGTH + 1, ByteArray(0)
                )
            )
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    // ================= 定长类型值长度校验 =================

    @Test
    fun `UInt32 值长度非 4 字节被拒绝而非越界崩溃`() {
        val bytes = dictBytes(listOf(entry(VariantDictionary.TYPE_UINT32, "P", ByteArray(8))))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `UInt64 值长度非 8 字节被拒绝`() {
        val bytes = dictBytes(listOf(entry(VariantDictionary.TYPE_UINT64, "M", ByteArray(4))))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `Bool 值长度非 1 字节被拒绝`() {
        val bytes = dictBytes(listOf(entry(VariantDictionary.TYPE_BOOL, "B", ByteArray(0))))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `Int32 值长度非 4 字节被拒绝`() {
        val bytes = dictBytes(listOf(entry(VariantDictionary.TYPE_INT32, "I", ByteArray(2))))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `Int64 值长度非 8 字节被拒绝`() {
        val bytes = dictBytes(listOf(entry(VariantDictionary.TYPE_INT64, "L", ByteArray(16))))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    // ================= 边界合法值与前向兼容 =================

    @Test
    fun `256 字节 key 与零长度 value 的边界合法`() {
        val longKey = "k".repeat(256)
        val bytes = dictBytes(listOf(entry(VariantDictionary.TYPE_STRING, longKey, ByteArray(0))))
        val dict = deserialize(bytes)
        assertEquals("", dict.getString(longKey))
    }

    @Test
    fun `未知类型项被消费跳过且不阻断解析`() {
        val unknownType: Byte = 0x66
        val bytes = dictBytes(
            listOf(
                entry(unknownType, "X", ByteArray(16)),
                entry(VariantDictionary.TYPE_UINT32, "P", LittleEndianUtil.intTo4Bytes(2))
            )
        )
        val dict = deserialize(bytes)
        assertEquals(2L, dict.getUInt32("P"))
        assertNull(dict.getString("X"))
    }

    @Test
    fun `合法字典序列化往返不受影响`() {
        val dict = VariantDictionary()
        dict.setUInt32("P", 4L)
        dict.setUInt64("M", 65536L)
        dict.setByteArray("S", ByteArray(32) { it.toByte() })
        val restored = VariantDictionary.deserialize(dict.toByteArray())
        assertEquals(4L, restored.getUInt32("P"))
        assertEquals(65536L, restored.getUInt64("M"))
        assertArrayEquals(ByteArray(32) { it.toByte() }, restored.getByteArray("S"))
    }

    // ================= P2-6：类型宽容与类型化异常 =================

    @Test
    fun `数值项宽容读取为 Bool 而非 ClassCastException`() {
        val dict = VariantDictionary()
        dict.setUInt32("flag", 1L)
        dict.setUInt64("off", 0L)
        assertTrue(dict.getBool("flag") == true)
        assertFalse(dict.getBool("off") == true)
    }

    @Test
    fun `字符串项经 getUInt32 抛类型化异常而非 ClassCastException`() {
        val dict = VariantDictionary()
        dict.setString("P", "2")
        assertThrows(KdbxCorruptFileException::class.java) { dict.getUInt32("P") }
    }

    @Test
    fun `字节数组项经 getString 抛类型化异常`() {
        val dict = VariantDictionary()
        dict.setByteArray("S", ByteArray(4))
        assertThrows(KdbxCorruptFileException::class.java) { dict.getString("S") }
    }

    @Test
    fun `字符串项经 getByteArray 抛类型化异常`() {
        val dict = VariantDictionary()
        dict.setString("S", "value")
        assertThrows(KdbxCorruptFileException::class.java) { dict.getByteArray("S") }
    }

    @Test
    fun `布尔项经 getByteArray 抛类型化异常`() {
        val dict = VariantDictionary()
        dict.setBool("B", true)
        assertThrows(KdbxCorruptFileException::class.java) { dict.getByteArray("B") }
    }
}
