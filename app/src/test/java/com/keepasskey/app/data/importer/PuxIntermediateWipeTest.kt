package com.keepasskey.app.data.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PuxIntermediateWipeTest {
    @Test
    fun `扩容和擦除清零原缓冲且已交付副本保持有效`() {
        val sink = ErasableByteSink(4)
        val payload = ByteArray(4) { 0x5A }
        sink.write(payload, 0, payload.size)
        val beforeGrowth = sink.buffer
        val delivered = sink.copyOut()

        sink.write(ByteArray(8) { 0x33 }, 0, 8)
        assertArrayEquals(ByteArray(4), beforeGrowth)
        assertEquals(12, sink.size)
        assertEquals(12, sink.copyOut().size)
        val beforeWipe = sink.buffer

        sink.wipe()
        sink.wipe()
        assertArrayEquals(ByteArray(beforeWipe.size), beforeWipe)
        assertEquals(0, sink.size)
        assertArrayEquals(payload, delivered)
    }
}
