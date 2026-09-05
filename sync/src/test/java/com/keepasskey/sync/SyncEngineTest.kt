package com.keepasskey.sync

import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SyncEngine 三哈希状态机全路径单元测试。
 */
class SyncEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var syncCache: SyncCache
    private lateinit var fakeProvider: FakeSyncProvider
    private lateinit var engine: SyncEngine

    private val remotePath = "vault.kdbx"

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("sync_cache")
        syncCache = SyncCache(cacheDir)
        fakeProvider = FakeSyncProvider()
        engine = SyncEngine(fakeProvider, syncCache)
    }

    @After
    fun tearDown() {
        syncCache.clear(remotePath)
    }

    @Test
    fun `测试未缓存首次下载状态机`() = runTest {
        val remoteData = "remote-content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(remoteData, etag = "etag-1")

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.RemoteSynced)
        assertArrayEquals(remoteData, (result as SyncOpenResult.RemoteSynced).remoteBytes)
        assertEquals("etag-1", result.etag)

        // 验证缓存建立与基线记录
        assertTrue(syncCache.isCached(remotePath))
        assertFalse(syncCache.hasLocalChanges(remotePath))
        assertEquals("etag-1", syncCache.getState(remotePath)?.etag)
    }

    @Test
    fun `测试已缓存无本地修改且远端有更新时刷新缓存`() = runTest {
        // 先首次同步建立缓存
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 远端更新为 v2
        val v2 = "content-v2".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v2, etag = "etag-2")

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.RemoteSynced)
        assertArrayEquals(v2, (result as SyncOpenResult.RemoteSynced).remoteBytes)
        assertEquals("etag-2", result.etag)
        assertEquals("etag-2", syncCache.getState(remotePath)?.etag)
    }

    @Test
    fun `测试已缓存且两端无修改时直接加载一致`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 远端未变
        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.RemoteSynced)
        assertArrayEquals(v1, (result as SyncOpenResult.RemoteSynced).remoteBytes)
    }

    @Test
    fun `测试本地赢场景自动上传与基线前移`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 本地写入新数据
        val localNew = "content-local-edited".toByteArray()
        syncCache.writeCache(remotePath, localNew)
        assertTrue(syncCache.hasLocalChanges(remotePath))

        // 远端依然是 etag-1 (未修改)，触发本地赢自动上传
        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.LocalWinAutoUploaded)
        val newEtag = (result as SyncOpenResult.LocalWinAutoUploaded).etag
        assertTrue(newEtag.isNotEmpty())

        // 远端文件已被更新为本地数据
        assertArrayEquals(localNew, fakeProvider.remoteFiles[remotePath]?.data)
        assertFalse(syncCache.hasLocalChanges(remotePath))
    }

    @Test
    fun `测试双方修改触发冲突检测`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 本地修改
        val localNew = "content-local-edited".toByteArray()
        syncCache.writeCache(remotePath, localNew)

        // 远端也修改（ETag 变为 etag-2）
        val remoteNew = "content-remote-edited".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(remoteNew, etag = "etag-2")

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.ConflictDetected)
        val conflict = result as SyncOpenResult.ConflictDetected
        assertArrayEquals(localNew, conflict.localBytes)
        assertArrayEquals(remoteNew, conflict.remoteBytes)
        assertEquals("etag-2", conflict.remoteEtag)
    }

    @Test
    fun `测试远端404丢失自愈恢复`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 远端文件被删除
        fakeProvider.remoteFiles.remove(remotePath)

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.RemoteLostRestored)
        // 远端已被自愈恢复
        assertTrue(fakeProvider.remoteFiles.containsKey(remotePath))
        assertArrayEquals(v1, fakeProvider.remoteFiles[remotePath]?.data)
    }

    @Test
    fun `测试网络不可达降级使用本地缓存`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 模拟网络异常
        fakeProvider.networkError = true

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.RemoteUnreachableUsingCache)
        assertArrayEquals(v1, (result as SyncOpenResult.RemoteUnreachableUsingCache).localBytes)
    }

    @Test
    fun `测试离线开关直接返回缓存`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        engine.isOffline = true
        fakeProvider.networkError = true // 即使网络断开

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.CacheHitOffline)
        assertArrayEquals(v1, (result as SyncOpenResult.CacheHitOffline).localBytes)
    }

    @Test
    fun `测试 commitLocal 成功上传与并发 412 转冲突`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 正常提交
        val v2 = "content-v2".toByteArray()
        val commitResult = engine.commitLocal(remotePath, v2)
        assertTrue(commitResult is SyncCommitResult.Uploaded)

        // 模拟他人修改引发 ETag 冲突 (412)
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile("concurrent-mod".toByteArray(), etag = "etag-other")
        val v3 = "content-v3".toByteArray()
        val conflictCommit = engine.commitLocal(remotePath, v3)
        assertTrue(conflictCommit is SyncCommitResult.ConflictNeedsMerge)
        val conflict = conflictCommit as SyncCommitResult.ConflictNeedsMerge
        assertEquals("etag-other", conflict.remoteEtag)
        assertArrayEquals("concurrent-mod".toByteArray(), conflict.remoteBytes)
    }

    @Test
    fun `测试 markResolvedAndUpload 合并解决提交基线前移`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        val mergedData = "merged-final-content".toByteArray()
        val res = engine.markResolvedAndUpload(remotePath, mergedData)
        assertTrue(res.isSuccess)
        val newEtag = res.getOrThrow()
        assertTrue(newEtag.isNotEmpty())

        assertFalse(syncCache.hasLocalChanges(remotePath))
        assertArrayEquals(mergedData, syncCache.readCache(remotePath))
    }

    @Test
    fun `测试 commitLocal 上传走事务性 uploadAtomic 路径`() = runTest {
        val localData = "local-content-v1".toByteArray()
        syncCache.writeCache(remotePath, localData)
        syncCache.updateBase(remotePath, SyncCache.sha256Hex(localData), "etag-base")

        val result = engine.commitLocal(remotePath, "local-content-v2".toByteArray())
        assertTrue(result is SyncCommitResult.Uploaded)
        // 生产管线必须经由事务性原子上传，而非普通 PUT
        assertEquals(1, fakeProvider.uploadAtomicCalls)
    }

    @Test
    fun `测试 ICacheSupervisor 全部六种事件的触发与流转`() = runTest {
        // 1. LoadedFromRemoteInSync: 首次加载
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.LoadedFromRemoteInSync })

        // 2. UpdatedCachedFileOnLoad: 远端变更刷新缓存
        val v2 = "content-v2".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v2, etag = "etag-2")
        engine.openRemote(remotePath)
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.UpdatedCachedFileOnLoad })

        // 3. UpdatedRemoteFileOnLoad: 本地修改远端未变，本地赢自动推送
        val localNew = "content-local-mod".toByteArray()
        syncCache.writeCache(remotePath, localNew)
        engine.openRemote(remotePath)
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.UpdatedRemoteFileOnLoad })

        // 4. OpenedFromLocalDueToConflict: 双方均修改
        val localConflict = "local-conflict".toByteArray()
        syncCache.writeCache(remotePath, localConflict)
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile("remote-conflict".toByteArray(), etag = "etag-conflict")
        engine.openRemote(remotePath)
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.OpenedFromLocalDueToConflict })

        // 5. CouldntSaveToRemote: commit 遭遇网络断开
        fakeProvider.networkError = true
        engine.commitLocal(remotePath, "commit-bytes".toByteArray())
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.CouldntSaveToRemote })

        // 6. CouldntOpenFromRemote: open 遭遇网络断开
        engine.openRemote(remotePath)
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.CouldntOpenFromRemote })
    }

    private class FakeRemoteFile(
        var data: ByteArray,
        var etag: String,
        var lastModified: Long = System.currentTimeMillis()
    )

    private class FakeSyncProvider : SyncProvider {
        val remoteFiles = mutableMapOf<String, FakeRemoteFile>()
        var networkError: Boolean = false
        var uploadAtomicCalls: Int = 0

        override suspend fun uploadAtomic(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            uploadAtomicCalls++
            return upload(remotePath, data, expectedEtag)
        }

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            if (networkError) return Result.failure(SyncException.NetworkError("Network down"))
            val file = remoteFiles[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("Not found"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    etag = file.etag,
                    contentLength = file.data.size.toLong(),
                    lastModifiedMillis = file.lastModified
                )
            )
        }

        override suspend fun download(remotePath: String): Result<ByteArray> {
            if (networkError) return Result.failure(SyncException.NetworkError("Network down"))
            val file = remoteFiles[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("Not found"))
            return Result.success(file.data)
        }

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            if (networkError) return Result.failure(SyncException.NetworkError("Network down"))
            val existing = remoteFiles[remotePath]
            if (expectedEtag != null && existing != null && existing.etag != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing.etag,
                        localExpectedEtag = expectedEtag,
                        message = "Conflict"
                    )
                )
            }
            val newEtag = "etag-${System.currentTimeMillis()}-${data.size}"
            remoteFiles[remotePath] = FakeRemoteFile(data, newEtag)
            return Result.success(newEtag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            if (networkError) return Result.failure(SyncException.NetworkError("Network down"))
            remoteFiles.remove(remotePath)
            return Result.success(Unit)
        }
    }
}
