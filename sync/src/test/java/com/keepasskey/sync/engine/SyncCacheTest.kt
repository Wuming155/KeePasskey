package com.keepasskey.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

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

    @Test
    fun `clear 销毁指定远端路径的全部缓存文件`() {
        // ISSUE-P1-07：.cache（工作副本）与 .basecache（三方合并基准）同为完整 KDBX 密文，
        // 清理时必须连同版本与元数据一起销毁，只删其一等于留下一份密文快照
        val cache = newCache()
        cache.writeCache("remote/vault.kdbx", "payload".toByteArray())
        cache.writeBaseContent("remote/vault.kdbx", "base".toByteArray())
        cache.updateBase("remote/vault.kdbx", baseVersion = "v1", etag = "\"etag-1\"")

        cache.clear("remote/vault.kdbx")

        assertNull(cache.readCache("remote/vault.kdbx"))
        assertNull(cache.readBaseContent("remote/vault.kdbx"))
        assertNull(cache.getState("remote/vault.kdbx"))
    }

    @Test
    fun `clearAll 清空全部远端路径的缓存且目录为空`() {
        val cache = newCache()
        cache.writeCache("remote/a.kdbx", "a".toByteArray())
        cache.writeBaseContent("remote/a.kdbx", "base-a".toByteArray())
        cache.writeCache("remote/b.kdbx", "b".toByteArray())
        cache.updateBase("remote/b.kdbx", baseVersion = "vb", etag = "\"etag-b\"")

        assertTrue(cache.clearAll())

        val remaining = tmpFolder.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals("锁定后缓存目录必须为空: $remaining", emptyList<File>(), remaining)
        assertNull(cache.readCache("remote/a.kdbx"))
        assertNull(cache.readCache("remote/b.kdbx"))
    }

    @Test
    fun `clear 与 clearAll 均不删除防回滚状态文件`() {
        // F-23 回归锁：`.rollback` 是**跨会话安全状态**，不是缓存产物。
        // 即便它出现在缓存目录（目录误配 / 升级前的历史残留），缓存清理也不得删除它——
        // 整改前 clear() 把它列入删除清单，而 clear() 由锁库 / 凭据清空触发，
        // 导致「用户锁定一次即状态归零」，云侧随即可以重放旧库。
        val dir = tmpFolder.newFolder("rollback-state-cache")
        val cache = SyncCache(dir)
        val remotePath = "remote/vault.kdbx"
        cache.writeCache(remotePath, "payload".toByteArray())
        cache.writeBaseContent(remotePath, "base".toByteArray())

        val key = SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8))
        val stateFile = File(dir, "$key${SyncRollbackGuard.SUFFIX_STATE}")
        stateFile.writeBytes("current=deadbeef\n".toByteArray(Charsets.UTF_8))

        cache.clear(remotePath)

        assertTrue("SyncCache.clear 不得删除防回滚状态: ${stateFile.name}", stateFile.isFile)
        assertNull(cache.readCache(remotePath))

        cache.writeCache(remotePath, "payload-2".toByteArray())
        assertTrue(cache.clearAll())

        assertTrue("SyncCache.clearAll 不得删除防回滚状态: ${stateFile.name}", stateFile.isFile)
        assertEquals(
            "除防回滚状态外不得残留其他缓存文件",
            listOf(stateFile.name),
            dir.listFiles()?.map { it.name }?.sorted().orEmpty()
        )
    }

    @Test
    fun `缓存文件权限收敛为仅属主可读写`() {
        // ISSUE-P1-07 验收标准 2：密文快照自落盘第一刻起即 0600，绝不依赖默认 umask
        Assume.assumeTrue(
            "当前文件系统不支持 POSIX 权限视图（Windows/FAT），跳过精确权限断言",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        )

        val dir = tmpFolder.newFolder("perm-cache")
        val cache = SyncCache(dir)
        cache.writeCache("remote/vault.kdbx", "payload".toByteArray())
        cache.writeBaseContent("remote/vault.kdbx", "base".toByteArray())
        cache.updateBase("remote/vault.kdbx", baseVersion = "v1", etag = "\"etag-1\"")

        val ownerReadWrite = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )
        assertEquals(
            "缓存目录必须为 0700",
            ownerReadWrite + PosixFilePermission.OWNER_EXECUTE,
            Files.getPosixFilePermissions(dir.toPath())
        )

        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("应有缓存文件落盘", files.size >= 4)
        files.forEach {
            assertEquals(
                "缓存文件必须为 0600: ${it.name}",
                ownerReadWrite,
                Files.getPosixFilePermissions(it.toPath())
            )
        }
    }
}
