package com.keepasskey.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class CbcDecryptTailWipeTest {

    private fun stream() = CbcDecryptingInputStream(
        source = ByteArrayInputStream(ByteArray(0)),
        key = ByteArray(16) { 1 },
        iv = ByteArray(16) { 2 },
        transform = { _, _, input -> input.copyOf() },
        chunkSize = 64
    )

    @Test
    fun `非空head合并后工作数组清零且交付结果有效`() {
        val head = byteArrayOf(1, 2, 3)
        val stripped = byteArrayOf(4, 5)
        val result = stream().use { it.mergeHeapTail(head, stripped) }

        assertArrayEquals(ByteArray(head.size), head)
        assertArrayEquals(ByteArray(stripped.size), stripped)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
    }

    @Test
    fun `空head时stripped所有权转移且不得擦除输出`() {
        val head = ByteArray(0)
        val stripped = byteArrayOf(7, 8, 9)
        val result = stream().use { it.mergeHeapTail(head, stripped) }

        assertSame("末块独占结果应直接转移给流输出", stripped, result)
        assertArrayEquals(byteArrayOf(7, 8, 9), result)
    }

    @Test
    fun `末块变换提前失败仍清零已生成的head`() {
        var calls = 0
        var head: ByteArray? = null
        val stream = CbcDecryptingInputStream(
            source = ByteArrayInputStream(ByteArray(32) { 0x41 }),
            key = ByteArray(16) { 1 },
            iv = ByteArray(16) { 2 },
            transform = { _, _, input ->
                calls++
                if (calls == 1) input.copyOf().also { head = it }
                else throw IOException("合成末块变换失败")
            },
            chunkSize = 64
        )

        try {
            assertThrows(IOException::class.java) { stream.read() }
            assertArrayEquals("提前异常路径必须擦除已生成的头段副本", ByteArray(16), head!!)
        } finally {
            stream.close()
        }
    }
}
