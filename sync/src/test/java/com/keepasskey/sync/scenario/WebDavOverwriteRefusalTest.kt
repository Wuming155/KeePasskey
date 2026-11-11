package com.keepasskey.sync.scenario

import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ENG-20261028-20 / ISSUE-P2-572：服务器拒绝 MOVE 覆盖已有目标（409/423）时的行为契约。
 *
 * 默认态下旧兜底「DELETE 正式目标 + 无预条件重试」已移除：DELETE 成功而重试 MOVE 失败、
 * 取消或进程死亡会让正式远端路径缺失，两步之间他端重建的目标又会被无 `If` 的 MOVE 静默覆盖。
 * HTTP/WebDAV 没有原子的覆盖原语，因此拒绝覆盖时只能如实失败并保留远端旧文件。
 *
 * 下列四条不变量针对**兼容开关默认关闭**态；用户显式开启 `allowDeleteBeforeMove` 后的
 * delete-then-move 路径、ETag 准入边界与恢复失败语义由本文件后五条用例覆盖。
 *
 * 四条不变量：
 * ① 远端 ETag 漂移（他端并发写入）⇒ ConflictError（携当前远端 ETag），**不 DELETE 远端目标**；
 * ② 重探失败（PROPFIND 持续失败）⇒ fail-closed 如实上抛（不折 ConflictError），远端不被删除；
 * ③ 重探确认未漂移 ⇒ 可处理的协议错误（携服务器状态码、文案声明远端未删除），**不发任何 DELETE**；
 * ④ 无 ETag 服务器（重探成功且空 etag）⇒ 与 ③ 相同：无锁可护不等于可以删除再重试。
 */
class WebDavOverwriteRefusalTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    /** 已被 [drainRequests] 取走的请求数：`requestCount` 是累计值，不能当作当前队列长度 */
    private var consumedRequests = 0

    /** 单次尝试即放弃传输级重试（让「持续失败」确定性落地，不引入真实退避等待） */
    private val fastFailOptions = SyncNetworkOptions(transientRetryAttempts = 1)

    @Before
    fun setUp() {
        server = MockWebServer()
        consumedRequests = 0
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun provider(
        options: SyncNetworkOptions = SyncNetworkOptions(),
        transferOptions: SyncTransferOptions = SyncTransferOptions.DISABLED
    ): WebDavSyncProvider =
        WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "tester",
            passwordChars = "tester123".toCharArray(),
            networkOptions = options,
            transferOptions = transferOptions,
            client = client
        )

    private fun drainRequests(): List<RecordedRequest> {
        val pending = server.requestCount - consumedRequests
        val recorded = (0 until pending).map { server.takeRequest() }
        consumedRequests += pending
        return recorded
    }

    @Test
    fun `覆盖被拒时重探发现他端并发写入 抛ConflictError携当前ETag且不DELETE远端`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(fastFailOptions)

        val v0 = "refusal-v0".toByteArray()
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
        assertArrayEquals("他端写入不得被 DELETE 销毁", v1, state.files["/vault.kdbx"])
        assertEquals("远端 ETag 保持他端版本", etag1, state.etags["/vault.kdbx"])
        assertEquals("漂移后不得再发 MOVE", 1, state.moveLog.size)
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `覆盖被拒后重探失败 failClosed如实上抛且远端不被删除`() = runTest {
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
    fun `覆盖被拒且远端未漂移 返回协议错误并保留远端旧文件不发送目标DELETE`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider()

        val v0 = "refusal-v0".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        state.failMoveOnExistingTarget409.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "refusal-v1".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
        }

        assertTrue("服务器不支持安全覆盖时必须如实失败: ${result.exceptionOrNull()}", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("必须归可处理的 ProtocolError: $err", err is SyncException.ProtocolError)
        assertEquals("错误码须为服务器返回的 409", 409, (err as SyncException.ProtocolError).statusCode)
        assertTrue("文案须声明远端旧文件未被删除", err.message.orEmpty().contains("远端旧文件未删除"))
        assertArrayEquals("远端旧文件必须原样保留", v0, state.files["/vault.kdbx"])
        assertEquals("远端 ETag 不得变化", etag0, state.etags["/vault.kdbx"])
        assertEquals("不得发出兜底重试 MOVE", 1, state.moveLog.size)
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `覆盖被拒423且无ETag可锁 同样不删除正式目标只清理临时文件`() = runTest {
        server.start()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-1\""))  // PUT 临时文件
        server.enqueue(MockResponse().setResponseCode(423).setBody("locked"))              // MOVE 首试 423
        server.enqueue(                                                                    // 重探：无 ETag 服务器
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag></D:getetag>
                   <D:getcontentlength>10</D:getcontentlength></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))                                 // DELETE 临时文件

        val result = provider().uploadAtomic("vault.kdbx", "v1".toByteArray(), expectedEtag = "base-etag")

        assertTrue("423 拒绝覆盖必须如实失败: $result", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("必须是 ProtocolError: $err", err is SyncException.ProtocolError)
        assertEquals("错误码须为服务器返回的 423", 423, (err as SyncException.ProtocolError).statusCode)

        val recorded = drainRequests()
        assertEquals("请求序应为 PUT → MOVE(423) → PROPFIND → DELETE(临时文件)", 4, recorded.size)
        assertEquals("PUT", recorded[0].method)
        assertEquals("MOVE", recorded[1].method)
        assertEquals("PROPFIND", recorded[2].method)
        val deletes = recorded.filter { it.method == "DELETE" }
        assertEquals("只允许一次 DELETE", 1, deletes.size)
        assertEquals("DELETE 必须是本次临时文件而非正式目标", recorded[0].path, deletes[0].path)
        assertTrue("临时名必须含 .kpktmp 中缀", deletes[0].path.orEmpty().contains(".kpktmp"))
        assertNotNull("首试 MOVE 必须携带 If 预条件", recorded[1].getHeader("If"))
        assertEquals("MOVE 必须保持 Overwrite: T", "T", recorded[1].getHeader("Overwrite"))
    }

    @Test
    fun `开启兼容写入且远端未漂移 删除目标后以OverwriteF完成MOVE`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(transferOptions = SyncTransferOptions(allowDeleteBeforeMove = true))

        val etag0 = p.upload("vault.kdbx", "refusal-v0".toByteArray()).getOrThrow()
        drainRequests()

        state.failMoveOnExistingTarget409.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "local-v2".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
        }

        assertTrue("兼容写入须成功: $result", result.isSuccess)
        assertArrayEquals("远端须为本轮上传的新版本", "local-v2".toByteArray(), state.files["/vault.kdbx"])
        assertTrue("MOVE 成功后不得残留临时文件", state.tmpResidues().isEmpty())

        val recorded = drainRequests()
        assertEquals("请求序应为 PUT(tmp) → MOVE(409) → PROPFIND → DELETE(目标) → MOVE(恢复)", 5, recorded.size)
        assertEquals("PUT", recorded[0].method)
        assertEquals("MOVE", recorded[1].method)
        assertEquals("PROPFIND", recorded[2].method)
        assertEquals("DELETE", recorded[3].method)
        assertEquals("DELETE 必须是正式目标而非临时文件", "/vault.kdbx", recorded[3].path)
        assertEquals("MOVE", recorded[4].method)
        assertNotNull("首试 MOVE 须带 If 预条件", recorded[1].getHeader("If"))
        assertEquals("首试 MOVE 须带 Overwrite: T", "T", recorded[1].getHeader("Overwrite"))
        assertTrue(
            "恢复 MOVE 不得复用已随删除失效的旧 ETag 条件",
            recorded[4].getHeader("If") == null
        )
        assertEquals(
            "恢复 MOVE 须用 Overwrite: F 拒绝覆盖他端窗口内重建的目标",
            "F", recorded[4].getHeader("Overwrite")
        )
    }

    @Test
    fun `开启兼容写入但远端ETag漂移 仍不删除目标并转ConflictError`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(transferOptions = SyncTransferOptions(allowDeleteBeforeMove = true))

        val etag0 = p.upload("vault.kdbx", "refusal-v0".toByteArray()).getOrThrow()
        val v1 = "other-writer-v1".toByteArray()
        val etag1 = p.upload("vault.kdbx", v1).getOrThrow()
        drainRequests()

        state.failMoveOnExistingTarget409.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "local-v2".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
        }

        assertTrue("漂移必须失败: $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("漂移须转 ConflictError: $ex", ex is SyncException.ConflictError)
        assertEquals("ConflictError 须携当前远端 ETag", etag1, (ex as SyncException.ConflictError).remoteEtag)
        assertArrayEquals("他端写入不得被 DELETE 销毁", v1, state.files["/vault.kdbx"])
        assertEquals("远端 ETag 保持他端版本", etag1, state.etags["/vault.kdbx"])
        val recorded = drainRequests()
        assertTrue(
            "兼容开关开启也不得对正式目标发 DELETE",
            recorded.none { it.method == "DELETE" && it.path == "/vault.kdbx" }
        )
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `开启兼容写入但无ETag可核验 仍不删除目标`() = runTest {
        val state = StatefulDavDispatcher()
        server.dispatcher = state
        server.start()
        val p = provider(transferOptions = SyncTransferOptions(allowDeleteBeforeMove = true))

        val etag0 = p.upload("vault.kdbx", "refusal-v0".toByteArray()).getOrThrow()
        state.etags["/vault.kdbx"] = ""
        drainRequests()

        state.failMoveOnExistingTarget409.set(true)
        val result = try {
            p.uploadAtomic("vault.kdbx", "local-v2".toByteArray(), expectedEtag = etag0)
        } finally {
            state.failMoveOnExistingTarget409.set(false)
        }

        assertTrue("无 ETag 可核验时必须如实失败: $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ProtocolError)
        assertArrayEquals("远端旧文件必须原样保留", "refusal-v0".toByteArray(), state.files["/vault.kdbx"])
        val recorded = drainRequests()
        assertTrue(
            "不得对正式目标发 DELETE",
            recorded.none { it.method == "DELETE" && it.path == "/vault.kdbx" }
        )
    }

    @Test
    fun `兼容写入恢复MOVE失败 保留临时文件且如实上抛恢复状态码`() = runTest {
        server.start()
        val p = provider(transferOptions = SyncTransferOptions(allowDeleteBeforeMove = true))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-1\""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("destination exists"))
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop>
                     <D:getetag>"etag-1"</D:getetag>
                     <D:getcontentlength>10</D:getcontentlength>
                   </D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(500).setBody("move failed"))

        val result = p.uploadAtomic("vault.kdbx", "local-v2".toByteArray(), expectedEtag = "etag-1")

        assertTrue("恢复 MOVE 失败必须如实失败: $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("须上抛恢复 MOVE 的协议错误: $ex", ex is SyncException.ProtocolError)
        assertEquals(500, (ex as SyncException.ProtocolError).statusCode)
        assertTrue(
            "文案须说明旧文件已删除、临时文件保留",
            ex.message.orEmpty().contains("旧文件已删除") && ex.message.orEmpty().contains("临时文件保留")
        )
        val recorded = drainRequests()
        assertEquals("失败后不得再清理临时文件（保留恢复锚点）", 5, recorded.size)
        assertEquals("DELETE 必须是正式目标", "/vault.kdbx", recorded[3].path)
        assertTrue(
            "不得有针对临时文件的 DELETE",
            recorded.none { it.method == "DELETE" && it.path.orEmpty().contains(".kpktmp") }
        )
    }

    @Test
    fun `覆盖被拒事件经带异常WARN进入可导出诊断通道`() = runTest {
        val events = mutableListOf<String>()
        val previous = AppLog.diagnosticSink
        AppLog.diagnosticSink = { level, tag, message, _ -> events += "$level|$tag|$message" }
        try {
            val state = StatefulDavDispatcher()
            server.dispatcher = state
            server.start()
            val p = provider()
            val etag0 = p.upload("vault.kdbx", "refusal-v0".toByteArray()).getOrThrow()
            state.failMoveOnExistingTarget409.set(true)
            try {
                p.uploadAtomic("vault.kdbx", "local-v2".toByteArray(), expectedEtag = etag0)
            } finally {
                state.failMoveOnExistingTarget409.set(false)
            }
            assertTrue(
                "覆盖被拒须经 AppLog.w 带异常重载写入诊断通道（导出日志可见）: $events",
                events.any { it.startsWith("WARN|WebDavSyncProvider|") && it.contains("HTTP 409") }
            )
        } finally {
            AppLog.diagnosticSink = previous
        }
    }
}