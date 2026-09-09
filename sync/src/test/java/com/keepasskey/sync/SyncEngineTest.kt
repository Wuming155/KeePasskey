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
import org.junit.Assert.fail
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
    fun `测试缓存读取失败时中止同步而非以空字节继续`() = runTest {
        // F4 修复：isCached 与实际读取之间状态漂移（系统回收 cacheDir / 并发清理 / 外部删除）
        // 时，绝不能以空字节数组继续——空数组命中本地赢路径会把远端全库覆盖为空
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 模拟「isCached 为 true 但 readCache 失败返回 null」的竞态窗口
        val flakyCache = object : SyncCache(cacheDir) {
            override fun readCache(remotePath: String): ByteArray? = null
        }
        val flakyEngine = SyncEngine(fakeProvider, flakyCache)

        try {
            flakyEngine.openRemote(remotePath)
            fail("缓存读取失败必须 fail-fast 终止同步")
        } catch (_: SyncException.CacheCorruptedError) {
            // 预期路径
        }

        // 远端内容必须原样保留，绝未被空字节覆盖
        assertArrayEquals(v1, fakeProvider.remoteFiles[remotePath]?.data)
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
    fun `测试 commitLocal 冲突后下载失败返回远端不可达而非伪造空冲突`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 远端被他人修改引发 412 冲突；随后下载远端内容失败（412 之后网络中断）
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile("concurrent-mod".toByteArray(), etag = "etag-other")
        fakeProvider.downloadError = true

        val v3 = "content-v3".toByteArray()
        val result = engine.commitLocal(remotePath, v3)

        // 严禁以 ByteArray(0) 伪造空冲突远端参与三方合并；
        // 本地缓存已安全保留 → 如实返回 RemoteUnreachable(keptLocal = true)
        assertTrue("期望 RemoteUnreachable，实际: $result", result is SyncCommitResult.RemoteUnreachable)
        assertTrue((result as SyncCommitResult.RemoteUnreachable).keptLocal)
        assertArrayEquals(v3, syncCache.readCache(remotePath))
        // 应发布远端保存失败事件（含下载失败原因）
        assertTrue(engine.events.replayCache.any { it is SyncCacheEvent.CouldntSaveToRemote })
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

    @Test
    fun `测试无ETag服务器内容未变不误判冲突`() = runTest {
        // 服务器不返回 ETag（etag 为空串）：只能依赖内容哈希裁决
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "")
        engine.openRemote(remotePath)

        // 远端内容未变（etag 仍为空）：应判定一致而非"远端有更新/冲突"
        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.RemoteSynced)
        assertArrayEquals(v1, (result as SyncOpenResult.RemoteSynced).remoteBytes)
    }

    @Test
    fun `测试无ETag服务器本地修改且远端内容未变时本地赢`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "")
        engine.openRemote(remotePath)

        val localNew = "content-local-edited".toByteArray()
        syncCache.writeCache(remotePath, localNew)

        // 远端内容哈希与基线一致 -> 本地赢自动上传，而非误报冲突
        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.LocalWinAutoUploaded)
        assertArrayEquals(localNew, fakeProvider.remoteFiles[remotePath]?.data)
    }

    @Test
    fun `测试无ETag服务器远端内容变化时触发冲突检测`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "")
        engine.openRemote(remotePath)

        val localNew = "content-local-edited".toByteArray()
        syncCache.writeCache(remotePath, localNew)
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile("remote-changed".toByteArray(), etag = "")

        val result = engine.openRemote(remotePath)
        assertTrue(result is SyncOpenResult.ConflictDetected)
        assertArrayEquals("remote-changed".toByteArray(), (result as SyncOpenResult.ConflictDetected).remoteBytes)
    }

    @Test
    fun `测试 base 内容快照随同步状态推进持久化`() = runTest {
        // 首次下载：base 内容 = 远端内容
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)
        assertArrayEquals(v1, syncCache.readBaseContent(remotePath))

        // 本地修改并本地赢上传：base 内容前移为本地新内容
        val localNew = "content-local-edited".toByteArray()
        syncCache.writeCache(remotePath, localNew)
        engine.openRemote(remotePath)
        assertArrayEquals(localNew, syncCache.readBaseContent(remotePath))

        // 远端更新下载刷新：base 内容前移为最新远端内容
        val v2 = "content-v2".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v2, etag = "etag-2")
        engine.openRemote(remotePath)
        assertArrayEquals(v2, syncCache.readBaseContent(remotePath))
    }

    @Test
    fun `测试 markResolvedAndUpload 失败不污染缓存与基线`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 本地有未同步修改
        val localNew = "content-local-unsynced".toByteArray()
        syncCache.writeCache(remotePath, localNew)
        val baseBefore = syncCache.getState(remotePath)?.baseVersion

        fakeProvider.networkError = true
        val res = engine.markResolvedAndUpload(remotePath, "merged-bytes".toByteArray(), expectedEtag = "etag-1")
        assertTrue(res.isFailure)

        // 写序约定（先上传后落缓存）：失败时缓存与基线保持原状，
        // 本地未同步修改仍在，下次同步自动重试
        assertArrayEquals(localNew, syncCache.readCache(remotePath))
        assertEquals(baseBefore, syncCache.getState(remotePath)?.baseVersion)
        assertTrue(syncCache.hasLocalChanges(remotePath))
    }

    @Test
    fun `测试 markResolvedAndUpload 使用冲突时刻 etag 遭遇并发修改时失败`() = runTest {
        val v1 = "content-v1".toByteArray()
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile(v1, etag = "etag-1")
        engine.openRemote(remotePath)

        // 模拟用户决策期间远端又被他人修改：以冲突时刻的旧 etag 提交必须 412 失败，
        // 而不是通过校验静默覆盖他端更新
        fakeProvider.remoteFiles[remotePath] = FakeRemoteFile("concurrent-mod".toByteArray(), etag = "etag-other")
        val res = engine.markResolvedAndUpload(remotePath, "merged-bytes".toByteArray(), expectedEtag = "etag-1")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is SyncException.ConflictError)
        // 远端内容未被覆盖
        assertArrayEquals("concurrent-mod".toByteArray(), fakeProvider.remoteFiles[remotePath]?.data)
    }

    private class FakeRemoteFile(
        var data: ByteArray,
        var etag: String,
        var lastModified: Long = System.currentTimeMillis()
    )

    private class FakeSyncProvider : SyncProvider {
        val remoteFiles = mutableMapOf<String, FakeRemoteFile>()
        var networkError: Boolean = false

        /** 仅令 download 失败（模拟 412 冲突响应后网络中断） */
        var downloadError: Boolean = false
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
            if (downloadError) return Result.failure(SyncException.NetworkError("Download failed after conflict"))
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
