package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.database.io.LittleEndianUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `BlockHmac` 与「接线前手工拼装写法」的**逐字节等价**用例（ISSUE-P3-37）。
 *
 * 本条目的核心主张是「把五处手工拼装收敛为一份实现且**块格式零变更**」，因此必须有一条
 * 直接对照旧写法的用例来锁定，而不是只依赖既有 KDBX 往返用例（后者通过不代表字节完全一致）。
 */
class BlockHmacTest {

    private val hmacKey64 = ByteArray(64) { (it * 11 + 3).toByte() }

    /** 接线前的写法：SHA-512(LE64(index) ‖ hmacKey64) 作密钥，HMAC(LE64(index) ‖ LE32(size) ‖ data)。 */
    private fun legacyCompute(index: Long, size: Int, data: ByteArray): ByteArray {
        val blockKey = HmacBlockStream.computeBlockKey(index, hmacKey64)
        val indexBytes = LittleEndianUtil.longTo8Bytes(index)
        val sizeBytes = LittleEndianUtil.intTo4Bytes(size)
        val dataBytes = data.copyOfRange(0, size)
        return HashUtil.hmacSha256(blockKey, indexBytes, sizeBytes, dataBytes)
    }

    @Test
    fun `compute 与接线前的拼装写法逐字节一致`() {
        val hmacer = BlockHmac(hmacKey64)
        try {
            for (index in listOf(0L, 1L, 2L, 1023L, Long.MAX_VALUE)) {
                for (size in listOf(0, 1, 16, 1024)) {
                    val data = ByteArray(size) { ((it * 7 + index).toByte()) }
                    assertArrayEquals(
                        "index=$index size=$size",
                        legacyCompute(index, size, data),
                        hmacer.compute(index, size, data, 0, size)
                    )
                }
            }
        } finally {
            hmacer.wipe()
        }
    }

    /// 带偏移的连续缓冲：`offset/length` 必须等价于先切片再计算（供逐块复用缓冲的场景）。
    @Test
    fun `compute 的偏移切片与切片副本等价`() {
        val hmacer = BlockHmac(hmacKey64)
        try {
            val buffer = ByteArray(1024) { (it * 13 + 5).toByte() }
            for ((offset, length) in listOf(0 to 16, 16 to 32, 512 to 256, 1008 to 16)) {
                assertArrayEquals(
                    "offset=$offset length=$length",
                    hmacer.compute(3L, length, buffer.copyOfRange(offset, offset + length), 0, length),
                    hmacer.compute(3L, length, buffer, offset, length)
                )
            }
        } finally {
            hmacer.wipe()
        }
    }

    /// `wipe` 幂等且确实清零内部派生中间量（通过「清零后再计算仍与旧写法一致」间接证明
    /// 辅助缓冲被正确重写，而非残留脏值）。
    @Test
    fun `wipe 幂等且之后仍可正确计算`() {
        val hmacer = BlockHmac(hmacKey64)
        val data = ByteArray(64) { it.toByte() }
        val first = hmacer.compute(7L, 64, data, 0, 64)
        hmacer.wipe()
        hmacer.wipe()
        val second = hmacer.compute(7L, 64, data, 0, 64)
        assertArrayEquals(first, second)
        assertArrayEquals(legacyCompute(7L, 64, data), second)
    }

    @Test
    fun `密钥长度非法时构造失败（fail-closed）`() {
        for (bad in listOf(0, 32, 63, 65)) {
            val failure = runCatching { BlockHmac(ByteArray(bad)) }.exceptionOrNull()
            assertEquals("长度 $bad 应构造失败", true, failure is IllegalArgumentException)
        }
    }

    /// 新写入接口与既有分配式接口必须同源等价（同一数值不得有两份编码实现）。
    @Test
    fun `小端写入接口与分配式接口等价`() {
        for (value in listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x12345678)) {
            val buffer = ByteArray(8)
            LittleEndianUtil.writeIntTo4Bytes(buffer, 2, value)
            val expected = LittleEndianUtil.intTo4Bytes(value)
            assertArrayEquals(expected, buffer.copyOfRange(2, 6))
        }
        for (value in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0123456789ABCDEFL)) {
            val buffer = ByteArray(16)
            LittleEndianUtil.writeLongTo8Bytes(buffer, 4, value)
            assertArrayEquals(LittleEndianUtil.longTo8Bytes(value), buffer.copyOfRange(4, 12))
        }
    }
}
