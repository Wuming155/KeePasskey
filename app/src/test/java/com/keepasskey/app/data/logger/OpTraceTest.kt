package com.keepasskey.app.data.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 链路标识只含操作类型与序号，耗时非负。 */
class OpTraceTest {

    @Test
    fun `操作标识含类型且互不重复`() {
        val first = OpTrace.start("sync")
        val second = OpTrace.start("sync")

        assertTrue("id 须可直接按操作类型检索：${first.id}", first.id.startsWith("sync-"))
        assertEquals("sync", first.kind)
        assertNotEquals("同一进程内并发操作不得共用 id", first.id, second.id)
        assertTrue("耗时不得为负", first.elapsedMs() >= 0)
    }
}
