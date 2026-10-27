package com.keepasskey.database.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WipableByteArrayOutputStreamTest {
    @Test
    fun `擦除必须清零缓冲且不影响已交付的副本`() {
        val stream = WipableByteArrayOutputStream()
        val payload = ByteArray(64) { (it + 1).toByte() }
        stream.write(payload)
        val delivered = stream.toByteArray()

        stream.wipe()

        assertEquals(0, stream.size())
        assertTrue(stream.toByteArray().isEmpty())
        assertTrue(stream.bufferedBytesSnapshot().all { it == 0.toByte() })
        assertArrayEquals(payload, delivered)
    }

    @Test
    fun `擦除幂等且之后仍可写入`() {
        val stream = WipableByteArrayOutputStream()
        stream.write(ByteArray(16) { 9 })
        stream.wipe()
        stream.wipe()
        assertTrue(stream.bufferedBytesSnapshot().all { it == 0.toByte() })

        stream.write(byteArrayOf(1, 2, 3))
        assertEquals(3, stream.size())
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.toByteArray())
    }
}
