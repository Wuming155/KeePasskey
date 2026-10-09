package com.keepasskey.sync.scenario

import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCommitResult
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncOpenResult
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ISSUE-P2-501：WebDAV MOVE 412 后元数据重探**瞬时失败**不得被折成空 etag
 * （空串会把「探测失败」与「无 ETag 服务器」混为同一口径，剥除合并窗口乐观锁并静默覆盖他端写入）。
 *
 * 关键事实（判据成立的前提）：无 ETag 服务器返回 `getMetadata` **成功 + 空 etag**；
 * 故 `getMetadata` 失败恰是「探测失败」，不是「无 ETag 服务器」。
 *
 * 本组用例覆盖三条：
 * ① 端到端（SyncEngine.commitLocal）：412 + PROPFIND 持续失败（HTTP 500）⇒ fail-closed，
 *    不发生无锁合并上传、他端内容不被覆盖。**ISSUE-P2-548 起降级结果按起因分型**：
 *    500 属「远端可达但拒绝」⇒ `RemoteRejected`（`NetworkError` 才归 `RemoteUnreachable`），
 *    fail-closed 与本条不变量本身未变；
 * ② Provider 层：同样的 412 + 探测失败 ⇒ 上抛**非** `ConflictError` 的探测失败（不折空）；
 * ③ 正确路径保留：无 ETag 服务器（Success + 空 etag）在 412 后仍须归 `ConflictError(remoteEtag="")`。
 */
class WebDavConflictProbeFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** 单次尝试即放弃传输级重试（让「持续失败」在一条请求内确定性落地，不引入退避等待）。 */
    private val fastFailOptions = SyncNetworkOptions(transientRetryAttempts = 1)

    private fun provider(options: SyncNetworkOptions = SyncNetworkOptions()): WebDavSyncProvider =
        WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "tester",
            passwordChars = "tester123".toCharArray(),
            networkOptions = options,
            client = client
        )

    @Test
    fun `MOVE412后元数据重探持续失败 commitLocal归RemoteRejected且不静默覆盖他端`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(fastFailOptions)

        // 1. 建立本地基线：首传 v0 并以 openRemote 采纳 ⇒ 缓存基线 etag = etag0
        val v0 = "base-v0".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        val cache = SyncCache(tempFolder.newFolder("sync_cache"))
        val engine = SyncEngine(p, cache)
        val open = engine.openRemote("vault.kdbx")
        assertTrue("首开必须同步远端内容", open is SyncOpenResult.RemoteSynced)
        (open as SyncOpenResult.RemoteSynced).adoption?.accept()
        assertEquals("基线必须落地为 etag0", etag0, cache.getState("vault.kdbx")?.etag)

        // 2. 他端写入 v1：远端 ETag 前移 ⇒ 本地以 etag0 提交将 MOVE 412
        val v1 = "other-writer-v1".toByteArray()
        val etag1 = p.upload("vault.kdbx", v1).getOrThrow()
        assertTrue("他端写入必须前移远端 ETag", etag1 != etag0)

        // 3. 412 后元数据重探**持续失败**
        state.failAllPropfind.set(true)

        val result = engine.commitLocal("vault.kdbx", "local-v2".toByteArray())
        state.failAllPropfind.set(false)

        // 不变量：探测失败 ⇒ fail-closed（不进入合并上传），本地变更如实保留。
        // ISSUE-P2-548 口径更正（**非放宽**）：起因是 HTTP 500「远端**可达**但拒绝」，
        // 降级结果按起因分型——只有 `NetworkError` 才归 `RemoteUnreachable`；500 属
        // `RemoteRejected`，UI 侧因此出「远端拒绝请求（500）」而不是伪装成「离线」。
        // 本用例的 fail-closed 与「不静默覆盖他端」两条不变量**一字未改**，断言反而更具体
        // （由「是某个降级结果」收紧为「是哪个降级结果」）。
        assertTrue(
            "探测失败必须 fail-closed（不进入合并上传），实际: $result",
            result is SyncCommitResult.RemoteRejected
        )
        assertTrue("降级必须如实保留本地", (result as SyncCommitResult.RemoteRejected).keptLocal)
        assertTrue("本地缓存不得丢失（本地数据安全第一）", cache.isCached("vault.kdbx"))
        // 他端内容与 ETag 均未被无锁覆盖
        assertArrayEquals("他端写入不得被无锁覆盖", v1, state.files["/vault.kdbx"])
        assertEquals("远端 ETag 不得被他端以外的人改写", etag1, state.etags["/vault.kdbx"])
        // 无无锁 MOVE：唯一一次 MOVE（412）仍携带 If 预条件
        assertEquals("412 后不得再发无锁 MOVE", 1, state.moveLog.size)
        assertTrue("唯一一次 MOVE 必须携带 If 预条件", state.moveLog.none { it.contains("if=null") })
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `MOVE412后元数据探测失败如实上抛不为空etag折空`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(fastFailOptions)

        val etag0 = p.upload("vault.kdbx", "v0".toByteArray()).getOrThrow()
        p.upload("vault.kdbx", "v1".toByteArray()).getOrThrow() // 他端前移
        state.failAllPropfind.set(true)

        val result = p.uploadAtomic("vault.kdbx", "v2".toByteArray(), expectedEtag = etag0)
        state.failAllPropfind.set(false)

        assertTrue("探测失败必须如实失败", result.isFailure)
        val ex = result.exceptionOrNull()
        assertFalse(
            "探测失败不得折空为 ConflictError(remoteEtag=空)——那是无 ETag 服务器的专属口径",
            ex is SyncException.ConflictError
        )
        assertTrue("必须是探测自身的失败: $ex", ex is SyncException.ProtocolError)
        assertArrayEquals("他端内容不得被覆盖", "v1".toByteArray(), state.files["/vault.kdbx"])
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `MOVE412后无ETag服务器(成功且空etag)仍归ConflictError空etag`() = runTest {
        // 正确路径保留：无 ETag 服务器在 412 后 PROPFIND 仍成功，只是 getetag 为空
        // ⇒ 必须照旧归 ConflictError(remoteEtag="") 并放行后续无锁上传（该服务器本无锁可持）。
        server.start()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-1\"")) // PUT 临时文件
        server.enqueue(MockResponse().setResponseCode(412))                                 // MOVE 预条件失败
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag></D:getetag>
                   <D:getcontentlength>10</D:getcontentlength></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))                                 // DELETE 临时文件

        val result = provider().uploadAtomic("vault.kdbx", "x".toByteArray(), expectedEtag = "base-etag")
        assertTrue("无 ETag 服务器 412 仍须归冲突", result.isFailure)
        val conflict = result.exceptionOrNull()
        assertTrue("必须是 ConflictError: $conflict", conflict is SyncException.ConflictError)
        assertEquals(
            "无 ETag 服务器须携带空 remoteEtag（而非折空的探测失败）",
            "",
            (conflict as SyncException.ConflictError).remoteEtag
        )
        assertEquals("localExpectedEtag 原样透传", "base-etag", conflict.localExpectedEtag)
    }
}
