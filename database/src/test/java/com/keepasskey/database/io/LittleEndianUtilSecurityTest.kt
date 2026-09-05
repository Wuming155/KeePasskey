package com.keepasskey.database.io

import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

/**
 * P0-5 整改回归：readBytes 长度安全边界。
 * 恶意长度（负数 / 0x7FFFFFFF 超大值 / 超过安全上限）必须以类型化
 * [KdbxCorruptFileException] 在分配前拒绝，严禁演化为
 * NegativeArraySizeException 或 OutOfMemoryError。
 */
class LittleEndianUtilSecurityTest {

    @Test
    fun `负数长度被类型化异常拒绝而非 NegativeArraySizeException`() {
        val stream = ByteArrayInputStream(ByteArray(16))
        // 0xFFFFFFFF 读取为小端 Int 即 -1（符号扩展攻击值）
        val negativeLength = 0xFFFFFFFF.toInt()
        assertThrows(KdbxCorruptFileException::class.java) {
            LittleEndianUtil.readBytes(stream, negativeLength)
        }
    }

    @Test
    fun `超大长度 0x7FFFFFFF 被类型化异常拒绝而非 OOM`() {
        val stream = ByteArrayInputStream(ByteArray(16))
        assertThrows(KdbxCorruptFileException::class.java) {
            LittleEndianUtil.readBytes(stream, 0x7FFFFFFF)
        }
    }

    @Test
    fun `超过默认上限 16MiB 的长度被拒绝`() {
        val stream = ByteArrayInputStream(ByteArray(16))
        assertThrows(KdbxCorruptFileException::class.java) {
            LittleEndianUtil.readBytes(stream, LittleEndianUtil.DEFAULT_MAX_READ_BYTES + 1)
        }
    }

    @Test
    fun `自定义上限生效且边界值恰好可读`() {
        val data = ByteArray(64) { it.toByte() }
        // 边界值 = maxLength：合法读取
        assertArrayEquals(
            data,
            LittleEndianUtil.readBytes(ByteArrayInputStream(data), 64, maxLength = 64)
        )
        // 超出自定义上限：拒绝
        assertThrows(KdbxCorruptFileException::class.java) {
            LittleEndianUtil.readBytes(ByteArrayInputStream(data), 65, maxLength = 64)
        }
    }

    @Test
    fun `零长度读取返回空数组不抛异常`() {
        val result = LittleEndianUtil.readBytes(ByteArrayInputStream(ByteArray(8)), 0)
        assertEquals(0, result.size)
    }

    @Test
    fun `数据不足时仍抛 EOFException 语义不变`() {
        assertThrows(EOFException::class.java) {
            LittleEndianUtil.readBytes(ByteArrayInputStream(ByteArray(5)), 10, maxLength = 16)
        }
    }
}
