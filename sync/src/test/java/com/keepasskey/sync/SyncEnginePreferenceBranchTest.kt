package com.keepasskey.sync

import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ISSUE-P3-03 (43a)：同步传输偏好的引擎分支单测。
 *
 * 覆盖两个由偏好驱动的新分支：
 * 1. `checkRemoteChangesBeforeSave = false`（上传前不比对云端版本）
 *    → [SyncEngine.commitLocalForce] 与 [SyncEngine.overwriteRemoteWithoutPrecondition]
 *    均以「无 ETag 预条件」上传，本地版本覆盖远端；
 * 2. 默认（开关开启）行为不得改变：同场景下仍必须走乐观锁并暴露冲突。
 */
class SyncEnginePreferenceBranchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var syncCache: SyncCache
    private lateinit var provider: PreferenceFakeProvider
    private lateinit var engine: SyncEngine

    private val remotePath = "vault.kdbx"

    @Before
    fun setUp() {
        syncCache = SyncCache(tempFolder.newFolder("sync_cache"))
        provider = PreferenceFakeProvider()
        engine = SyncEngine(provider, syncCache)
    }

    @Test
    fun `强制提交不带 ETag 预条件并覆盖远端`() = runTest {
        val localBytes = "local-v2".toByteArray()
        provider.remoteFiles[remotePath] = FakeRemote("remote-v2-concurrent".toByteArray(), "etag-remote")

        val result = engine.commitLocalForce(remotePath, localBytes)

        assertTrue(result is SyncCommitResult.Uploaded)
        assertNull("强制路径严禁携带乐观锁预条件", provider.lastExpectedEtag)
        assertArrayEquals("本地版本必须覆盖远端", localBytes, provider.remoteFiles[remotePath]?.data)
        assertTrue("基线须前移（否则下次同步会重复上传）", !syncCache.hasLocalChanges(remotePath))
    }

    @Test
    fun `默认提交路径仍以乐观锁暴露并发冲突`() = runTest {
        // 建立缓存与基线：base 版本与当前缓存内容不同 = 存在本地未提交修改
        syncCache.writeCache(remotePath, "local-v2".toByteArray())
        syncCache.updateBase(remotePath, "base-hash", "etag-base")
        provider.remoteFiles[remotePath] = FakeRemote("remote-v2-concurrent".toByteArray(), "etag-remote")

        val result = engine.commitLocal(remotePath, "local-v2".toByteArray())

        assertTrue("默认路径必须检出冲突而非覆盖", result is SyncCommitResult.ConflictNeedsMerge)
        assertEquals("etag-base", provider.lastExpectedEtag)
    }

    @Test
    fun `离线时强制提交保留本地缓存并如实返回远端不可达`() = runTest {
        engine.isOffline = true
        val localBytes = "local-v3".toByteArray()

        val result = engine.commitLocalForce(remotePath, localBytes)

        assertTrue(result is SyncCommitResult.RemoteUnreachable)
        assertTrue((result as SyncCommitResult.RemoteUnreachable).keptLocal)
        assertArrayEquals("本地修改必须安全保留在缓存", localBytes, syncCache.readCache(remotePath))
    }

    @Test
    fun `关闭远端比对后本地修改直接覆盖云端且不进入冲突`() = runTest {
        syncCache.writeCache(remotePath, "local-v2".toByteArray())
        syncCache.updateBase(remotePath, "base-hash", "etag-base")
        provider.remoteFiles[remotePath] = FakeRemote("remote-v2-concurrent".toByteArray(), "etag-remote")
        engine.overwriteRemoteWithoutPrecondition = true

        val result = engine.openRemote(remotePath)

        assertTrue(result is SyncOpenResult.LocalWinAutoUploaded)
        assertNull(provider.lastExpectedEtag)
        assertArrayEquals(
            "本地版本必须覆盖远端",
            "local-v2".toByteArray(),
            provider.remoteFiles[remotePath]?.data
        )
    }

    @Test
    fun `保持默认开关时同一场景仍判定为双方冲突`() = runTest {
        syncCache.writeCache(remotePath, "local-v2".toByteArray())
        syncCache.updateBase(remotePath, "base-hash", "etag-base")
        provider.remoteFiles[remotePath] = FakeRemote("remote-v2-concurrent".toByteArray(), "etag-remote")

        val result = engine.openRemote(remotePath)

        assertTrue("默认行为（接线前语义）不得改变", result is SyncOpenResult.ConflictDetected)
    }

    private class FakeRemote(var data: ByteArray, var etag: String)

    private class PreferenceFakeProvider : SyncProvider {
        val remoteFiles = mutableMapOf<String, FakeRemote>()
        var lastExpectedEtag: String? = null
            private set

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val file = remoteFiles[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("Not found"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    etag = file.etag,
                    contentLength = file.data.size.toLong(),
                    lastModifiedMillis = 0L
                )
            )
        }

        override suspend fun download(remotePath: String): Result<ByteArray> {
            val file = remoteFiles[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("Not found"))
            return Result.success(file.data)
        }

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            lastExpectedEtag = expectedEtag
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
            val newEtag = "etag-uploaded-${data.size}"
            remoteFiles[remotePath] = FakeRemote(data.copyOf(), newEtag)
            return Result.success(newEtag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteFiles.remove(remotePath)
            return Result.success(Unit)
        }
    }
}
