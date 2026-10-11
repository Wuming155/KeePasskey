package com.keepasskey.sync.scenario

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P2-572：WebDAV 409/423 兜底「DELETE + 无预条件重试」在 DELETE 前必须重探目标元数据。
 * 无条件 DELETE 会销毁他端在本端上传临时文件期间写入的新版本（窗口 A），其后的无预条件重试
 * 又会静默覆盖 DELETE 与重试之间他端新建的目标（窗口 B）。
 *
 * 四条不变量：
 * ① 远端 ETag 漂移（他端并发写入）⇒ ConflictError（携当前远端 ETag），**不 DELETE 远端目标**；
 * ② 重探失败（PROPFIND 持续失败）⇒ fail-closed 如实上抛（不折 ConflictError），远端不被删除；
 * ③ 重探确认未漂移 ⇒ 既有兜底保留（DELETE + 无 `If` 重试成功，Kp2a 兼容收益不变）；
 * ④ 无 ETag 服务器（重探成功且空 etag）⇒ 同样放行兜底（无锁可护）。
 */
class WebDavOverwriteFallbackGuardTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    /** 单次尝试即放弃传输级重试（让「持续失败」确定性落地，不引入真实退避等待） */
    private val fastFailOptions = SyncNetworkOptions(transientRetryAttempts = 1)

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun provider(options: SyncNetworkOptions = SyncNetworkOptions()): WebDavSyncProvider =
        WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "tester",
            passwordChars = "tester123".toCharArray(),
            networkOptions = options,
            client = client
        )

    @Test
    fun `兜底前重探发现他端并发写入 抛ConflictError携当前ETag且不DELETE远端`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(fastFailOptions)

        val v0 = "fallback-v0".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        // 他端在「本端上传临时文件」窗口内写入 v1：远端 ETag 前移
        val v1 = "other-writer-v1".toByteArray()
        val etag1 = p.upload("vault.kdbx", v1).getOrThrow()

        state.failMoveOnExistingTarget409.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "local-v2".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
        }

        assertTrue("漂移必须失败: $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("必须是 ConflictError: $ex", ex is SyncException.ConflictError)
        assertEquals("ConflictError 必须携当前远端 ETag（他端版本）", etag1, (ex as SyncException.ConflictError).remoteEtag)
        assertEquals("localExpectedEtag 原样透传", etag0, ex.localExpectedEtag)
        assertArrayEquals("他端写入不得被 DELETE 销毁（窗口 A 被守卫拦截）", v1, state.files["/vault.kdbx"])
        assertEquals("远端 ETag 保持他端版本", etag1, state.etags["/vault.kdbx"])
        assertEquals("漂移后不得再发兜底重试 MOVE", 1, state.moveLog.size)
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `兜底前重探失败 failClosed如实上抛且远端不被删除`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(fastFailOptions)

        val v0 = "intact-v0".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        state.failMoveOnExistingTarget409.set(true)
        state.failAllPropfind.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "local-v1".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
            state.failAllPropfind.set(false)
        }

        assertTrue("重探失败必须 fail-closed: $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertFalse("探测失败不得折成 ConflictError（P2-501 分型）: $ex", ex is SyncException.ConflictError)
        assertTrue("必须如实上抛探测自身失败: $ex", ex is SyncException.ProtocolError)
        assertArrayEquals("远端内容不得被 DELETE 销毁", v0, state.files["/vault.kdbx"])
        assertEquals("远端 ETag 保持原状", etag0, state.etags["/vault.kdbx"])
        assertEquals("不得再发兜底重试 MOVE", 1, state.moveLog.size)
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `兜底前重探确认未漂移 仍走DELETE加无If重试成功`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider()

        val etag0 = p.upload("vault.kdbx", "fallback-v0".toByteArray()).getOrThrow()

        state.failMoveOnExistingTarget409.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "fallback-v1".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
        }

        assertTrue("未漂移必须放行既有兜底: ${result.exceptionOrNull()}", result.isSuccess)
        assertArrayEquals("远端内容为新版本", "fallback-v1".toByteArray(), state.files["/vault.kdbx"])
        assertEquals("请求序应为 MOVE(409) + PROPFIND 重探 + DELETE + MOVE(重试)", 2, state.moveLog.size)
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `兜底前重探无ETag服务器即成功且空etag 仍放行DELETE重试`() = runTest {
        server.start()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-1\""))  // PUT 临时文件
        server.enqueue(MockResponse().setResponseCode(409).setBody("destination exists"))   // MOVE 首试 409
        server.enqueue(                                                                     // 重探：无 ETag 服务器
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag></D:getetag>
                   <D:getcontentlength>10</D:getcontentlength></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))                                 // DELETE 目标
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"final-1\"")) // MOVE 兜底重试

        val result = provider().uploadAtomic("vault.kdbx", "v1".toByteArray(), expectedEtag = "base-etag")
        assertTrue("空 ETag 重探（无锁可护）必须放行兜底: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("final-1", result.getOrThrow())

        val recorded = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        repeat(server.requestCount) { recorded += server.takeRequest() }
        val moves = recorded.filter { it.method == "MOVE" }
        assertEquals("应恰有两次 MOVE（首试 + 兜底重试）", 2, moves.size)
        val probeIndex = recorded.indexOfFirst { it.method == "PROPFIND" }
        val deleteIndex = recorded.indexOfFirst { it.method == "DELETE" }
        assertTrue(
            "重探必须发生在兜底 DELETE 之前（ISSUE-P2-572 判据核心）",
            probeIndex in 0 until deleteIndex
        )
        assertNull("兜底重试不得携带 If 预条件", moves[1].getHeader("If"))
        assertEquals("兜底重试仍须保持 Overwrite: T", "T", moves[1].getHeader("Overwrite"))
    }
}
