package com.keepasskey.sync.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * SyncCache 流式读写回归（ISSUE-P2-24）：
 * 大附件落盘走 `writeCacheStreaming` / `openCacheStream` / `cacheSize`，
 * 全程固定缓冲搬运，不整份物化；`clearAll` 供会话锁定清理。
 */
class SyncCacheStreamingTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newCache(): SyncCache = SyncCache(tmpFolder.newFolder("cache"))

    @Test
    fun `流式写入后读回逐字节一致且大小正确`() {
        val cache = newCache()
        val data = ByteArray(300_000) { (it % 251).toByte() }

        val sha = cache.writeCacheStreaming("attachments/k1", ByteArrayInputStream(data), data.size.toLong())

        assertEquals(64, sha.length)
        assertEquals(data.size.toLong(), cache.cacheSize("attachments/k1"))
        assertArrayEquals(data, cache.readCache("attachments/k1"))
    }

    @Test
    fun `流式打开返回内容一致`() {
        val cache = newCache()
        val data = ByteArray(64) { it.toByte() }
        cache.writeCacheStreaming("attachments/k2", ByteArrayInputStream(data), data.size.toLong())

        val stream = cache.openCacheStream("attachments/k2")
        assertTrue(stream != null)
        assertArrayEquals(data, stream!!.readBytes())
    }

    @Test
    fun `未写入的键大小为零且流为null`() {
        val cache = newCache()
        assertEquals(0L, cache.cacheSize("attachments/missing"))
        assertNull(cache.openCacheStream("attachments/missing"))
    }

    @Test
    fun `clearAll 清空全部流式写入内容`() {
        val cache = newCache()
        val data = ByteArray(1024) { it.toByte() }
        cache.writeCacheStreaming("attachments/a", ByteArrayInputStream(data), data.size.toLong())
        cache.writeCacheStreaming("attachments/b", ByteArrayInputStream(data), data.size.toLong())

        assertTrue(cache.clearAll())
        assertEquals(0L, cache.cacheSize("attachments/a"))
        assertNull(cache.openCacheStream("attachments/b"))
    }
}
