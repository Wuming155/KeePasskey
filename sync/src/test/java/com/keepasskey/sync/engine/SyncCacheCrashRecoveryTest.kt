package com.keepasskey.sync.engine

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/**
 * ISSUE-P2-636：缓存 `.cache` 与 `.version` 分离提交在崩溃 / 冷启动后的状态收敛矩阵。
 *
 * 缺陷形态：`writeCache` 先原子替换 `<key>.cache`，再独立写 `<key>.version`。两步之间中断会留下
 * 「新字节 + 旧 / 缺失版本标记」。整改前 `hasLocalChanges` 只比 `.version` 与 `.baseversion`，
 * 该状态被判为 clean；即便修正为按实际字节比对（isDirty 为真），**基线前移仍取 `.version`**
 * ⇒ 上传成功后 `baseversion` 写成陈旧摘要，`hasLocalChanges` 此后恒为真、每轮重复上传。
 *
 * 本矩阵以「崩溃后重新构造的 SyncCache / SyncEngine 实例」为观察点，逐项核对正式库
 * （测试内以独立 `vault.kdbx` 文件代表）、`.cache`、`.basecache`、`.version` / `.baseversion`、
 * `.meta`（ETag）与防回滚状态，且覆盖远端不变（A）与远端并发变化（C）两侧。
 *
 * 竞态与崩溃点均以确定性的写序注入（`writeCache(updateVersion = false)`、定向删文件），
 * 不使用 sleep 或概率触发。
 */
class SyncCacheCrashRecoveryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val remotePath = "remote/vault.kdbx"

    /** 正式库演示载体：与缓存分开，用来核对「缓存改动不外溢到正式文件」。 */
    private lateinit var vaultFile: File

    private fun cacheDir(): File = tmpFolder.newFolder("cache-${System.nanoTime()}")

    private fun keyHash(): String = SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8))

    private fun cacheFile(dir: File) = File(dir, keyHash() + SyncCache.SUFFIX_CACHE)
    private fun versionFile(dir: File) = File(dir, keyHash() + SyncCache.SUFFIX_VERSION)
    private fun baseVersionFile(dir: File) = File(dir, keyHash() + ".baseversion")
    private fun baseCacheFile(dir: File) = File(dir, keyHash() + SyncCache.SUFFIX_BASE_CACHE)
    private fun metaFile(dir: File) = File(dir, keyHash() + ".meta")

    private fun sha(bytes: ByteArray) = SyncCache.sha256Hex(bytes)

    // ------------------------------------------------------------------
    // 1. cache 已替换、version 未更新（崩溃窗口 A）
    // ------------------------------------------------------------------

    @Test
    fun `cache 已替换而 version 仍为旧摘要时不得判 clean`() = runTest {
        val dir = cacheDir()
        val provider = RecordingProvider()
        val a = "accepted-A".toByteArray()
        val b = "local-unsynced-B".toByteArray()
        provider.remoteFiles[remotePath] = a to "etag-a"

        // 建立已同步基线 A（写入 cache + version + baseversion + basecache + meta）
        SyncCache(dir).apply {
            writeCache(remotePath, a)
            updateBase(remotePath, sha(a), "etag-a")
            writeBaseContent(remotePath, a)
        }

        // 崩溃注入：cache 已交付 B，version 未刷新（等价于 rename 后、写 version 前被杀）
        SyncCache(dir).writeCache(remotePath, b, updateVersion = false)
        assertEquals("前置：version 必须仍是 A 的摘要（即真实崩溃形态）", sha(a), versionFile(dir).readText().trim())
        assertEquals("前置：cache 已是 B", sha(b), sha(cacheFile(dir).readBytes()))

        // 冷启动：新实例按实际字节判定
        val restarted = SyncCache(dir)
        assertTrue(
            "ISSUE-P2-636：cache 字节已变而 version 落后时必须判为本地有修改（禁止 false-clean）",
            restarted.hasLocalChanges(remotePath)
        )
    }

    @Test
    fun `version 缺失时同样不得判 clean`() = runTest {
        val dir = cacheDir()
        val a = "accepted-A".toByteArray()
        val b = "local-unsynced-B".toByteArray()
        val cache = SyncCache(dir)
        cache.writeCache(remotePath, a)
        cache.updateBase(remotePath, sha(a), "etag-a")
        cache.writeCache(remotePath, b, updateVersion = false)
        assertTrue("前置：version 必须已被删除", versionFile(dir).delete())

        assertTrue("version 缺失但 cache 有字节时必须判为有本地修改", SyncCache(dir).hasLocalChanges(remotePath))
    }

    @Test
    fun `baseversion 缺失时任意非空 cache 均判为有修改`() = runTest {
        val dir = cacheDir()
        val a = "accepted-A".toByteArray()
        val cache = SyncCache(dir)
        cache.writeCache(remotePath, a)
        assertTrue("前置：baseversion 不存在", !baseVersionFile(dir).exists())

        assertTrue(SyncCache(dir).hasLocalChanges(remotePath))
    }

    // ------------------------------------------------------------------
    // 2. 崩溃后本地赢上传：基线必须前移到**实际字节**摘要（本轮修复点）
    // ------------------------------------------------------------------

    @Test
    fun `崩溃遗留旧version时本地赢上传后基线与字节一致且后端到端收敛`() = runTest {
        val dir = cacheDir()
        val provider = RecordingProvider()
        val a = "accepted-A".toByteArray()
        val b = "local-unsynced-B".toByteArray()
        provider.remoteFiles[remotePath] = a to "etag-a"

        SyncCache(dir).apply {
            writeCache(remotePath, a)
            updateBase(remotePath, sha(a), "etag-a")
            writeBaseContent(remotePath, a)
        }
        SyncCache(dir).writeCache(remotePath, b, updateVersion = false)

        // 冷启动同步（新实例）：远端仍是 A（未变）⇒ 本地赢自动上传 B
        val restartedCache = SyncCache(dir)
        val engine = SyncEngine(provider, restartedCache)
        val opened = engine.openRemote(remotePath)
        assertTrue("远端未变且本地有修改时必须走本地赢分支，实际: $opened", opened is SyncOpenResult.LocalWinAutoUploaded)

        assertArrayEquals("远端必须被更新为崩溃保留下来的本地内容", b, provider.remoteFiles[remotePath]!!.first)

        // 逐项核对落盘状态：baseversion 取 B 的摘要，而不是落后的 `.version`
        assertEquals(
            "ISSUE-P2-636：baseversion 必须前移到本次上传字节的摘要（旧实现写成 A 的摘要）",
            sha(b),
            baseVersionFile(dir).readText().trim()
        )
        assertEquals("`.version` 必须与 cache 字节一致", sha(b), versionFile(dir).readText().trim())
        assertArrayEquals("基准内容快照必须与 cache 一致", b, baseCacheFile(dir).readBytes())
        val uploadedEtag = provider.remoteFiles[remotePath]!!.second.also {
            assertEquals("meta 必须记录本次上传返回的 ETag", it, metaFile(dir).readText().lines()
                .first { line -> line.startsWith("etag=") }.substringAfter('=').trim())
        }
        assertEquals("上传后远端 ETag 与元数据同源", uploadedEtag, SyncCache(dir).getState(remotePath)?.etag)

        assertFalse("收敛后必须判为无本地修改（禁止每轮重复上传）", restartedCache.hasLocalChanges(remotePath))

        // 再冷启动一次（真正的新进程形态）：不应再次上传
        val secondCache = SyncCache(dir)
        val secondProvider = RecordingProvider().apply { remoteFiles[remotePath] = b to "etag-b" }
        val second = SyncEngine(secondProvider, secondCache).openRemote(remotePath)
        assertTrue("重启后远端未变应直接加载，实际: $second", second is SyncOpenResult.RemoteSynced)
        assertEquals("重启后不得重复上传", 0, secondProvider.uploadCalls)
        assertFalse(secondCache.hasLocalChanges(remotePath))
    }

    @Test
    fun `崩溃遗留旧version且远端并发变化时不得静默覆盖远端C`() = runTest {
        val dir = cacheDir()
        val provider = RecordingProvider()
        val a = "accepted-A".toByteArray()
        val b = "local-unsynced-B".toByteArray()
        val c = "remote-concurrent-C".toByteArray()
        provider.remoteFiles[remotePath] = a to "etag-a"

        SyncCache(dir).apply {
            writeCache(remotePath, a)
            updateBase(remotePath, sha(a), "etag-a")
            writeBaseContent(remotePath, a)
        }
        SyncCache(dir).writeCache(remotePath, b, updateVersion = false)

        // 远端被另一端改成 C（ETag 同步变化）
        provider.remoteFiles[remotePath] = c to "etag-c"

        val cache = SyncCache(dir)
        val opened = SyncEngine(provider, cache).openRemote(remotePath)
        assertTrue("本地有修改且远端已变必须进冲突面，实际: $opened", opened is SyncOpenResult.ConflictDetected)
        val conflict = opened as SyncOpenResult.ConflictDetected
        assertArrayEquals("冲突必须携带本地崩溃保留的 B", b, conflict.localBytes)
        assertArrayEquals("冲突必须携带远端 C", c, conflict.remoteBytes)
        assertEquals("etag-c", conflict.remoteEtag)
        assertEquals("冲突路径不得上传", 0, provider.uploadCalls)
        assertArrayEquals("冲突路径不得改写远端", c, provider.remoteFiles[remotePath]!!.first)
        assertArrayEquals("本地工作副本必须保留 B", b, cache.readCache(remotePath))
        assertTrue("冲突未决期间仍判为本地有修改", cache.hasLocalChanges(remotePath))
    }

    // ------------------------------------------------------------------
    // 3. rename 之前失败 / 标记超前 / 并发写同一路径
    // ------------------------------------------------------------------

    @Test
    fun `cache rename 失败时旧字节与旧标记保持自洽`() = runTest {
        val dir = cacheDir()
        val a = "accepted-A".toByteArray()
        val b = "local-unsynced-B".toByteArray()
        val cache = SyncCache(dir)
        cache.writeCache(remotePath, a)
        cache.updateBase(remotePath, sha(a), "etag-a")
        cache.writeBaseContent(remotePath, a)

        // 故障注入：交付目标是（非空）目录 ⇒ rename 必然失败（无需注入 IO 故障）
        val cacheTarget = cacheFile(dir)
        assertTrue("前置：删除既有 cache 文件", cacheTarget.delete())
        cacheTarget.mkdirs()
        File(cacheTarget, "busy").writeText("x")

        val failed = runCatching { SyncCache(dir).writeCache(remotePath, b) }.isFailure
        assertTrue("前置：交付必须真的失败（否则本用例无鉴别力）", failed)

        // 崩溃/失败后：cache 不可读、标记仍是 A ⇒ 状态自洽且不误判 clean
        val restarted = SyncCache(dir)
        assertNull("交付失败时不得留下可读的 B", restarted.readCache(remotePath))
        assertEquals("标记必须仍是 A", sha(a), versionFile(dir).readText().trim())
        assertTrue(
            "cache 文件不可读但标记存在 ⇒ 必须判为有本地修改（宁可重下，不误判 clean）",
            restarted.hasLocalChanges(remotePath)
        )
        assertEquals("交付失败后不得残留 tmp", emptyList<String>(), dir.listFiles().orEmpty()
            .map { it.name }.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `标记超前但字节仍等于基线时按字节判 clean 且上传后标记收敛`() = runTest {
        val dir = cacheDir()
        val provider = RecordingProvider()
        val a = "accepted-A".toByteArray()
        val b = "stale-marker-B".toByteArray()
        provider.remoteFiles[remotePath] = a to "etag-a"
        val cache = SyncCache(dir)
        cache.writeCache(remotePath, a)
        cache.updateBase(remotePath, sha(a), "etag-a")
        cache.writeBaseContent(remotePath, a)

        // 反向崩溃形态：标记已前移而字节仍是 A（等价于 version 先写、cache rename 丢失）
        versionFile(dir).writeText(sha(b))

        val restarted = SyncCache(dir)
        assertFalse(
            "裁决只看实际字节：字节 == baseversion 就是 clean，标记超前不得反过来制造假 dirty",
            restarted.hasLocalChanges(remotePath)
        )
        assertEquals(
            "`getState().localVersion` 仍如实暴露磁盘上的标记值（调用方不得把它当作内容真值）",
            sha(b),
            restarted.getState(remotePath)?.localVersion
        )

        // 引擎在本地赢路径上以字节摘要前移基线，随后三份标记收敛到同一摘要
        cache.writeCache(remotePath, "local-C".toByteArray())
        val opened = SyncEngine(provider, restarted).openRemote(remotePath)
        assertTrue("本地有修改且远端未变应本地赢，实际: $opened", opened is SyncOpenResult.LocalWinAutoUploaded)
        assertEquals("基线前移后 version 必须收敛到实际字节", sha("local-C".toByteArray()),
            versionFile(dir).readText().trim())
        assertEquals("baseversion 必须与 version 同源", versionFile(dir).readText().trim(),
            baseVersionFile(dir).readText().trim())
    }

    @Test
    fun `并发写同一路径后字节与标记始终同源`() = runTest {
        val dir = cacheDir()
        val payloads = (0 until 6).map { idx -> "payload-$idx".toByteArray() }

        val outcomes = payloads.map { payload ->
            async(Dispatchers.IO) { runCatching { SyncCache(dir).writeCache(remotePath, payload) } }
        }.awaitAll()

        // Windows 上两个并发 rename 覆盖同一目标可返回 AccessDenied（平台限制，不是本仓缺陷）：
        // 本用例断言的是**不变量**而非「全部成功」——失败必须干净退出，成功必须字节与标记同源。
        val finalBytes = requireNotNull(SyncCache(dir).readCache(remotePath)) {
            "至少一次写必须成功（失败集合=${outcomes.map { it.exceptionOrNull() }}）"
        }
        val finalDigest = sha(finalBytes)
        assertEquals(
            "并发交付后 `.version` 必须与最终字节同源（不得出现标记与字节交叉污染）",
            finalDigest,
            versionFile(dir).readText().trim()
        )
        assertTrue("最终内容必须是某个完整载荷（不得混合）", payloads.any { it.contentEquals(finalBytes) })
        assertEquals(
            "失败的交付不得残留 tmp（失败=${outcomes.count { it.isFailure }}）",
            emptyList<String>(),
            dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }
        )
        assertTrue("并发写后基线未前移，必须判为有本地修改", SyncCache(dir).hasLocalChanges(remotePath))
    }

    // ------------------------------------------------------------------
    // 4. 正式库 / 防回滚状态与缓存三者不互相污染
    // ------------------------------------------------------------------

    @Test
    fun `缓存崩溃与恢复不改写正式库且防回滚状态保持`() = runTest {
        val dir = cacheDir()
        val stateDir = tmpFolder.newFolder("rollback-${System.nanoTime()}")
        vaultFile = File(tmpFolder.root, "vault.kdbx").apply { writeBytes("accepted-A".toByteArray()) }
        val a = "accepted-A".toByteArray()
        val b = "local-unsynced-B".toByteArray()
        val provider = RecordingProvider().apply { remoteFiles[remotePath] = a to "etag-a" }
        val guard = SyncRollbackGuard(stateDir)
        guard.recordAccepted(remotePath, sha(a))

        SyncCache(dir).apply {
            writeCache(remotePath, a)
            updateBase(remotePath, sha(a), "etag-a")
            writeBaseContent(remotePath, a)
        }
        SyncCache(dir).writeCache(remotePath, b, updateVersion = false)
        val vaultBefore = vaultFile.readBytes()

        val cache = SyncCache(dir)
        val engine = SyncEngine(provider, cache, guard)
        assertTrue(engine.openRemote(remotePath) is SyncOpenResult.LocalWinAutoUploaded)

        assertArrayEquals("同步缓存恢复不得改写正式库文件", vaultBefore, vaultFile.readBytes())
        val stateFiles = stateDir.listFiles().orEmpty().filter { it.name.endsWith(SyncRollbackGuard.SUFFIX_STATE) }
        assertEquals("防回滚状态文件必须存在且仅一份", 1, stateFiles.size)
        assertEquals(
            "本地赢上传后必须把新内容记入已接受历史（否则下次远端返回 B 会被判重放）",
            RollbackVerdict.Unchanged,
            SyncRollbackGuard(stateDir).inspect(remotePath, sha(b))
        )

        // 缓存清理不得连带摧毁防回滚状态（F-23），崩溃恢复路径同样如此
        assertTrue(cache.clearAll())
        assertTrue("缓存清理后防回滚状态必须保留", SyncRollbackGuard(stateDir).inspect(remotePath, sha(b)) == RollbackVerdict.Unchanged)
    }

    @Test
    fun `未交付的临时文件不参与状态判定且冷启动后自动清除`() = runTest {
        val dir = cacheDir()
        val a = "accepted-A".toByteArray()
        val cache = SyncCache(dir)
        cache.writeCache(remotePath, a)
        cache.updateBase(remotePath, sha(a), "etag-a")

        // 崩溃残留：半写 tmp（命名同 SecureFileWrite 唯一临时名）
        val leftover = File(dir, "${keyHash()}${SyncCache.SUFFIX_CACHE}.11111111-2222-3333-4444-555555555555.tmp")
            .apply { writeBytes("half-written-ciphertext".toByteArray()) }

        assertFalse("残留 tmp 不得影响判定", SyncCache(dir).hasLocalChanges(remotePath))
        assertTrue("前置：残留 tmp 存在", leftover.exists())

        SyncCache(dir).clear(remotePath)
        assertFalse("清理必须同时收敛该路径的崩溃 tmp", leftover.exists())
    }

    // ------------------------------------------------------------------
    // 测试替身：只记录上传/元数据次数，行为对齐生产契约（条件写 + 流式下载）
    // ------------------------------------------------------------------

    private class RecordingProvider : SyncProvider {
        val remoteFiles = mutableMapOf<String, Pair<ByteArray, String>>()
        var uploadCalls = 0
            private set
        var metadataCalls = 0
            private set

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            metadataCalls++
            val file = remoteFiles[remotePath] ?: return Result.failure(SyncException.FileNotFound("not found"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    etag = file.second,
                    contentLength = file.first.size.toLong(),
                    lastModifiedMillis = 0L
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            val file = remoteFiles[remotePath] ?: return Result.failure(SyncException.FileNotFound("not found"))
            sink.write(file.first)
            return Result.success(Unit)
        }

        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> =
            uploadAtomic(remotePath, data, expectedEtag, null)

        override suspend fun uploadAtomic(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?,
            remoteExists: Boolean?
        ): Result<String> {
            uploadCalls++
            val existing = remoteFiles[remotePath]
            if (expectedEtag != null && existing != null && existing.second != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(existing.second, expectedEtag, "conflict")
                )
            }
            val etag = "etag-" + data.size + "-" + uploadCalls
            remoteFiles[remotePath] = data.copyOf() to etag
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteFiles.remove(remotePath)
            return Result.success(Unit)
        }
    }
}
