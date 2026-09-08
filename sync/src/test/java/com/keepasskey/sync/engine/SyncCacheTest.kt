package com.keepasskey.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SyncCache 单元测试（TASK-40 补强之一 + TASK-37 回归锁）：
 * 覆盖缓存写入/读取往返、updateBase 两文件合并原子写后的一致性。
 */
class SyncCacheTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newCache(): SyncCache = SyncCache(tmpFolder.newFolder("cache"))

    @Test
    fun `缓存写入与读取往返一致`() {
        val cache = newCache()
        val data = ByteArray(256) { (it % 251).toByte() }

        val sha = cache.writeCache("remote/vault.kdbx", data)

        assertTrue(cache.isCached("remote/vault.kdbx"))
        assertTrue(data.contentEquals(cache.readCache("remote/vault.kdbx")!!))
        assertEquals(64, sha.length)
    }

    @Test
    fun `未缓存路径读取返回null`() {
        val cache = newCache()
        assertNull(cache.readCache("not-exist.kdbx"))
        assertNull(cache.getState("not-exist.kdbx"))
    }

    @Test
    fun `updateBase 两文件合并更新后状态一致`() {
        // TASK-37 回归锁：baseversion 与 meta 必须在同一次调用内背靠背交付，
        // getState 读到的 baseVersion 与 etag 属同一轮同步快照
        val cache = newCache()
        val sha = cache.writeCache("remote/vault.kdbx", "payload".toByteArray())

        cache.updateBase("remote/vault.kdbx", baseVersion = sha, etag = "\"etag-abc\"")

        val state = cache.getState("remote/vault.kdbx")!!
        assertEquals(sha, state.baseVersion)
        assertEquals("etag-abc", state.etag)
        assertTrue(state.lastSyncMillis > 0)
        assertEquals("remote/vault.kdbx", state.remotePath)
    }

    @Test
    fun `updateBase 不传etag时保留既有etag`() {
        val cache = newCache()
        cache.writeCache("remote/vault.kdbx", "payload".toByteArray())
        cache.updateBase("remote/vault.kdbx", baseVersion = "v1", etag = "\"keep-me\"")

        cache.updateBase("remote/vault.kdbx", baseVersion = "v2")

        val state = cache.getState("remote/vault.kdbx")!!
        assertEquals("v2", state.baseVersion)
        assertEquals("keep-me", state.etag)
    }

    @Test
    fun `updateBase 后无残留tmp文件`() {
        val cache = newCache()
        cache.writeCache("remote/vault.kdbx", "payload".toByteArray())
        cache.updateBase("remote/vault.kdbx", baseVersion = "v1")

        val leftovers = tmpFolder.root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .toList()
        assertEquals("不应残留 tmp 文件: $leftovers", emptyList<File>(), leftovers)
    }
}
