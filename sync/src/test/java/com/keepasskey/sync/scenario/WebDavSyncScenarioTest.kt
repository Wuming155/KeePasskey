package com.keepasskey.sync.scenario

import com.keepasskey.sync.downloadBytes
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.webdav.WebDavSyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WebDAV 同步真实使用场景测试（对应《同步存储测试场景清单》10 项）。
 *
 * 场景 -> 用例映射：
 * 1. 多端并发冲突：S1-A/B/C（乐观锁 412 / 有状态竞态恰好一胜 / 编辑对删除）
 * 2. 大文件传输：S2-A（2MiB 字节精确往返；分段上传/断点续传不在 SyncProvider 契约内，见 README）
 * 3. 网络中断与重试：S3-A~G（MOVE 重试 / 回滚幂等 / 上传中断 / 超时 / 半截数据 / 5xx 与 401 / 删除幂等）
 * 4. 元数据一致性：S4-A~E（ETag->If-Match 规范化 / PUT 响应缺 ETag 回退 / 缺失即失败 / 时间格式 / 多 response）
 * 5. 特殊字符与中文：S5-A/B（全量编码 + MOVE Destination 编码）
 * 6. 空文件：S6-A（零字节 PUT/GET/元数据）
 * 7. 深层嵌套与路径归一：S7-A/B
 * 8. 并发上传不同版本：并入 S1-B（同基线竞态）
 * 9. 后端不可用：S9-A/B/C（连接拒绝 / 连接超时 / TLS 对明文服务器）
 * 10. 大目录分页：SyncProvider 契约无 LIST 操作（单文件同步模型），以 S4-E 的
 *     PROPFIND 多 response 解析健壮性作为可行部分覆盖，其余 N/A（见 README）。
 */
class WebDavSyncScenarioTest {

    private lateinit var server: MockWebServer
    private var stateful: StatefulDavDispatcher? = null

    private val plainClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** 有状态模式（真实协议语义涌现） */
    private fun startStateful(): StatefulDavDispatcher {
        val d = StatefulDavDispatcher()
        server.dispatcher = d
        server.start()
        stateful = d
        return d
    }

    /** 队列模式（手工编排响应） */
    private fun startQueued() {
        server.start()
    }

    private fun provider(client: OkHttpClient = plainClient): WebDavSyncProvider =
        WebDavSyncProvider(
            serverUrl = server.url("/").toString(),
            username = "tester",
            passwordChars = "tester123".toCharArray(),
            client = client
        )

    // ------------------------------------------------------------------
    // 场景 11：首传往返次数（ISSUE-P3-180）
    // ------------------------------------------------------------------

    @Test
    fun `场景11 已知远端不存在时首传不得再探 PROPFIND`() = runTest {
        startStateful()
        val p = provider()

        val before = server.requestCount
        val etag = p.uploadAtomic(
            "fresh.kdbx",
            "v0".toByteArray(),
            expectedEtag = null,
            remoteExists = false
        ).getOrThrow()

        assertTrue("首传必须拿到 ETag", etag.isNotBlank())
        assertEquals(
            "已知远端不存在时首传只应有 PUT + MOVE 两个请求" +
                "（原实现额外插一次 PROPFIND 存在性探测，首传路径多一个 RTT）",
            2,
            server.requestCount - before
        )
    }

    @Test
    fun `场景11 存在性未知时仍按原逻辑探测一次`() = runTest {
        startStateful()
        val p = provider()

        val before = server.requestCount
        p.uploadAtomic("fresh2.kdbx", "v0".toByteArray(), expectedEtag = null, remoteExists = null)
            .getOrThrow()

        assertEquals(
            "未提供结论时必须现探一次 PROPFIND（PUT + PROPFIND + MOVE）——对侧保守语义不变",
            3,
            server.requestCount - before
        )
    }

    @Test
    fun `场景11 已知远端已存在时覆盖上传同样不探测`() = runTest {
        val state = startStateful()
        val p = provider()
        p.upload("existing.kdbx", "v0".toByteArray()).getOrThrow()

        val before = server.requestCount
        p.uploadAtomic("existing.kdbx", "v1".toByteArray(), expectedEtag = null, remoteExists = true)
            .getOrThrow()

        assertEquals("已知存在时同样只需 PUT + MOVE", 2, server.requestCount - before)
        assertArrayEquals(
            "覆盖后远端内容必须为新版本（Overwrite: T 语义生效）",
            "v1".toByteArray(),
            state.files["/existing.kdbx"]
        )
    }

    // ------------------------------------------------------------------
    // 场景 1：多端并发冲突（Provider 协议层）
    // ------------------------------------------------------------------

    @Test
    fun `场景1 过期乐观锁上传被412拒绝 远端内容与ETag不受污染`() = runTest {
        val state = startStateful()
        val p = provider()

        val v0 = "version-0".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        // 设备 A 以基线 etag0 胜出，远端前移至 etag1
        val vA = "device-A".toByteArray()
        val etag1 = p.uploadAtomic("vault.kdbx", vA, expectedEtag = etag0).getOrThrow()

        // 设备 B 仍持有过期基线 etag0，提交必须 412 转 ConflictError
        val staleResult = p.uploadAtomic("vault.kdbx", "device-B".toByteArray(), expectedEtag = etag0)
        assertTrue("过期基线必须失败", staleResult.isFailure)
        val conflict = staleResult.exceptionOrNull()
        assertTrue(conflict is SyncException.ConflictError)
        assertEquals("冲突必须携带远端当前 ETag", etag1, (conflict as SyncException.ConflictError).remoteEtag)

        // 远端内容与 ETag 不被过期写入污染
        assertArrayEquals("远端保持设备 A 的内容", vA, state.files["/vault.kdbx"])
        assertEquals(etag1, state.etags["/vault.kdbx"])
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `场景1 双设备同基线并发提交 恰好一方获胜且无临时文件残留`() = runTest {
        val state = startStateful()
        val p = provider()

        val etag0 = p.upload("vault.kdbx", "base".toByteArray()).getOrThrow()

        val bytesA = ByteArray(32 * 1024) { 'A'.code.toByte() }
        val bytesB = ByteArray(32 * 1024) { 'B'.code.toByte() }

        val (resultA, resultB) = coroutineScope {
            val a = async(Dispatchers.IO) { p.uploadAtomic("vault.kdbx", bytesA, expectedEtag = etag0) }
            val b = async(Dispatchers.IO) { p.uploadAtomic("vault.kdbx", bytesB, expectedEtag = etag0) }
            a.await() to b.await()
        }

        val winnerBytes: ByteArray
        val winnerEtag: String
        if (resultA.isSuccess) {
            assertFalse("同基线并发必须恰好一胜", resultB.isSuccess)
            assertTrue(resultB.exceptionOrNull() is SyncException.ConflictError)
            winnerBytes = bytesA
            winnerEtag = resultA.getOrThrow()
        } else {
            assertTrue("同基线并发必须恰好一胜", resultB.isSuccess)
            assertTrue(resultA.exceptionOrNull() is SyncException.ConflictError)
            winnerBytes = bytesB
            winnerEtag = resultB.getOrThrow()
        }

        assertEquals("远端最终内容必须是胜者载荷", winnerBytes.toList(), (state.files["/vault.kdbx"] ?: ByteArray(0)).toList())
        assertEquals(winnerEtag, state.etags["/vault.kdbx"])
        assertTrue("败者临时文件必须被回滚清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `场景1 一端编辑一端删除 上传412转ConflictError并清理临时文件`() = runTest {
        startQueued()
        // 设备 B 持基线 etag 上传时，远端对象已被设备 A 删除：
        // 1. PUT .kpktmp 成功  2. MOVE 的 If 预条件对已删除目标判 false -> 412
        // 3. 412 后探测远端元数据  4. 回滚删除临时文件
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"tmp-1\""))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag>"remote-gone"</D:getetag></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val result = provider().uploadAtomic("vault.kdbx", "local-edit".toByteArray(), expectedEtag = "base-etag")
        assertTrue("编辑对删除必须以冲突暴露而非静默覆盖", result.isFailure)
        val conflict = result.exceptionOrNull() as SyncException.ConflictError
        assertEquals("remote-gone", conflict.remoteEtag)
        assertEquals("base-etag", conflict.localExpectedEtag)

        assertEquals("请求序：PUT/MOVE/PROPFIND/DELETE", 4, server.requestCount)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("MOVE", server.takeRequest().method)
        assertEquals("PROPFIND", server.takeRequest().method)
        val deleteReq = server.takeRequest()
        assertEquals("DELETE", deleteReq.method)
        assertTrue("清理的是本事务临时文件", deleteReq.path.orEmpty().endsWith(".kpktmp"))
    }

    // ------------------------------------------------------------------
    // 场景 2：大文件传输
    // ------------------------------------------------------------------

    @Test
    fun `场景2 2MiB二进制大文件往返字节精确`() = runTest {
        val state = startStateful()
        val p = provider()

        val payload = Random(20260907).nextBytes(2 * 1024 * 1024)
        val etag = p.upload("big-vault.kdbx", payload).getOrThrow()
        assertTrue(etag.isNotBlank())

        val downloaded = p.downloadBytes("big-vault.kdbx").getOrThrow()
        assertArrayEquals("2MiB 载荷必须逐字节一致", payload, downloaded)

        val meta = p.getMetadata("big-vault.kdbx").getOrThrow()
        assertEquals("元数据大小与载荷一致", payload.size.toLong(), meta.contentLength)
        assertEquals(payload.size.toLong(), (state.files["/big-vault.kdbx"] ?: ByteArray(0)).size.toLong())
    }

    // ------------------------------------------------------------------
    // 场景 3：网络中断与重试
    // ------------------------------------------------------------------

    @Test
    fun `场景3 MOVE首次失败重试成功 事务最终一致`() = runTest {
        val state = startStateful()
        state.failNextMove.set(true)
        val p = provider()

        val data = "retry-payload".toByteArray()
        val result = p.uploadAtomic("vault.kdbx", data)

        assertTrue("MOVE 单次失败后重试必须成功: ${result.exceptionOrNull()}", result.isSuccess)
        assertArrayEquals(data, state.files["/vault.kdbx"])
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `场景3 MOVE持续失败回滚后目标保持原状 幂等不损坏`() = runTest {
        val state = startStateful()
        val p = provider()

        val v0 = "intact-v0".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        state.failAllMoves.set(true)
        val result = p.uploadAtomic("vault.kdbx", "should-not-land".toByteArray())
        state.failAllMoves.set(false)

        assertTrue("持续失败必须如实上抛", result.isFailure)
        assertArrayEquals("回滚后目标内容保持原状", v0, state.files["/vault.kdbx"])
        assertEquals("回滚后目标 ETag 保持原状", etag0, state.etags["/vault.kdbx"])
        assertTrue("临时文件必须清理", state.tmpResidues().isEmpty())
    }

    @Test
    fun `场景3 上传时服务器连接立即断开 失败且既有对象不被污染`() = runTest {
        val state = startStateful()
        val p = provider()

        val v0 = "before-drop".toByteArray()
        val etag0 = p.upload("vault.kdbx", v0).getOrThrow()

        state.failAllPuts.set(true)
        val result = p.uploadAtomic("vault.kdbx", "mid-air".toByteArray())
        assertTrue("上传中断必须如实失败", result.isFailure)
        assertArrayEquals("既有对象不被半途连接污染", v0, state.files["/vault.kdbx"])
        assertEquals(etag0, state.etags["/vault.kdbx"])
    }

    @Test
    fun `场景3 下载超时如实失败不悬挂`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutClient = OkHttpClient.Builder()
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        val start = System.nanoTime()
        val result = provider(shortTimeoutClient).downloadBytes("vault.kdbx")
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertTrue("无响应必须在读取超时后如实失败", result.isFailure)
        assertTrue("超时应在合理时间内返回而非悬挂 (actual=${elapsedMs}ms)", elapsedMs < 10_000)
    }

    @Test
    fun `场景3 下载响应体中途断开 绝不静默返回截断数据`() = runTest {
        startQueued()
        val full = ByteArray(512 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(full))
                .throttleBody(8 * 1024, 1, TimeUnit.MILLISECONDS)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val result = provider().downloadBytes("vault.kdbx")
        // 核心不变量：要么如实失败，要么拿到完整字节——绝不静默返回半截数据
        if (result.isSuccess) {
            assertArrayEquals("若返回成功则必须字节完整", full, result.getOrThrow())
        } else {
            assertTrue("失败应为 IO 类异常", result.exceptionOrNull() is java.io.IOException)
        }
    }

    @Test
    fun `场景3 服务端5xx与401错误如实映射类型化异常`() = runTest {
        // GET 500 -> ProtocolError（ISSUE-P3-298 ③：瞬时 5xx 先重试，耗尽后仍映射 ProtocolError）
        startQueued()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val r500 = provider().downloadBytes("vault.kdbx")
        assertTrue(r500.exceptionOrNull() is SyncException.ProtocolError)
        server.shutdown()

        // GET 401 -> AuthenticationError（401 是确定性结论，不在瞬时重试面）
        server = MockWebServer(); startQueued()
        server.enqueue(MockResponse().setResponseCode(401))
        val r401 = provider().downloadBytes("vault.kdbx")
        assertTrue(r401.exceptionOrNull() is SyncException.AuthenticationError)
        server.shutdown()

        // PUT 503 -> ProtocolError（无期望 ETag = 无条件 PUT，AC③ 禁止重试，恒单次）
        server = MockWebServer(); startQueued()
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val r503 = provider().upload("vault.kdbx", "x".toByteArray())
        assertTrue(r503.exceptionOrNull() is SyncException.ProtocolError)
    }

    @Test
    fun `场景3 删除远端不存在对象幂等成功`() = runTest {
        val state = startStateful()
        assertTrue("对不存在对象的 DELETE 必须幂等成功", provider().delete("ghost.kdbx").isSuccess)
        assertFalse(state.files.containsKey("/ghost.kdbx"))
    }

    // ------------------------------------------------------------------
    // 场景 4：元数据一致性
    // ------------------------------------------------------------------

    @Test
    fun `场景4 元数据ETag弱校验标记保留 弱期望不进IfMatch`() = runTest {
        startQueued()
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag>W/"weak-123"</D:getetag>
                   <D:getcontentlength>10</D:getcontentlength></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e2\""))
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
               <D:response><D:propstat><D:prop><D:getetag>"strong-456"</D:getetag>
               <D:getcontentlength>10</D:getcontentlength></D:prop></D:propstat></D:response>
               </D:multistatus>"""
        ))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e3\""))

        val p = provider()
        // ISSUE-P1-275 AC②：弱标记必须保留——规范化形态 = W/ 前缀 + 不带引号不透明值
        val meta = p.getMetadata("vault.kdbx").getOrThrow()
        assertEquals("弱校验标记必须保留", "W/weak-123", meta.etag)

        // 弱期望 ETag 不得进 If-Match（RFC 7232 §3.1 强比较下永不匹配 → 恒 412 死锁）：
        // 不发送 If-Match，上传照常成功
        val newEtag = p.upload("vault.kdbx", "0123456789".toByteArray(), expectedEtag = meta.etag).getOrThrow()
        assertEquals("e2", newEtag)
        server.takeRequest() // 第 1 个请求：PROPFIND（getMetadata）
        val putReq = server.takeRequest() // 第 2 个请求：PUT
        assertEquals("弱期望 ETag 不得产生 If-Match 头", null, putReq.getHeader("If-Match"))

        // 强期望 ETag 仍以带引号形态传输 If-Match（既有行为锁定）
        val strongMeta = p.getMetadata("vault.kdbx").getOrThrow()
        assertEquals("strong-456", strongMeta.etag)
        p.upload("vault.kdbx", "0123456789".toByteArray(), expectedEtag = strongMeta.etag).getOrThrow()
        server.takeRequest() // 第 3 个请求：PROPFIND
        val strongPut = server.takeRequest() // 第 4 个请求：PUT
        assertEquals("\"strong-456\"", strongPut.getHeader("If-Match"))
    }

    @Test
    fun `场景4 弱校验ETag服务器全链路上传不产生恒412`() = runTest {
        // ISSUE-P1-275 AC④：弱 ETag 服务器（如部分 Apache/mod_dav 配置）上，乐观锁必须照常工作：
        // MOVE 的 If 头以弱形态回传（RFC 4918 §10.4.4 允许弱比较），不得因剥标记的强形态恒 412
        val state = startStateful()
        state.weakEtagPaths.add("/vault.kdbx")
        val p = provider()

        val etag0 = p.upload("vault.kdbx", "v0".toByteArray()).getOrThrow()
        assertTrue("弱存储标签必须以弱形态回读", etag0.startsWith("W/"))

        // 以弱形态作预条件覆盖上传：If 头携带 W/"..."，服务器弱比较通过 ⇒ 上传成功而非恒 412
        val etag1 = p.uploadAtomic("vault.kdbx", "v1".toByteArray(), expectedEtag = etag0).getOrThrow()
        assertTrue(etag1.startsWith("W/"))
        assertTrue("MOVE If 头必须携带弱形态 entity-tag", state.moveLog.last().contains("[W/\""))

        // 过期弱基线仍必须被弱比较识破（乐观锁语义不因弱形态而失效）
        val stale = p.uploadAtomic("vault.kdbx", "v2".toByteArray(), expectedEtag = etag0)
        assertTrue("过期弱基线必须 412", stale.isFailure)
        assertTrue(stale.exceptionOrNull() is SyncException.ConflictError)
    }

    @Test
    fun `场景4 PUT响应缺ETag时回退PROPFIND元数据保持一致`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(201)) // 无 ETag 头
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag>"fallback-1"</D:getetag></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        val etag = provider().upload("vault.kdbx", "x".toByteArray()).getOrThrow()
        assertEquals("必须回退到 PROPFIND 元数据而非返回空 ETag", "fallback-1", etag)
    }

    @Test
    fun `场景4 PUT响应缺ETag且元数据探测失败时上传如实失败`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(201)) // 无 ETag 头
        server.enqueue(MockResponse().setResponseCode(500)) // PROPFIND 探测失败

        val result = provider().upload("vault.kdbx", "x".toByteArray())
        assertTrue("无法确认新 ETag 时必须如实失败而非谎报成功", result.isFailure)
    }

    @Test
    fun `场景4 PROPFIND getlastmodified ISO8601格式解析`() = runTest {
        startQueued()
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop>
                   <D:getetag>"iso-1"</D:getetag>
                   <D:getlastmodified>2026-09-04T12:00:00Z</D:getlastmodified>
                   </D:prop></D:propstat></D:response></D:multistatus>"""
            )
        )
        val meta = provider().getMetadata("vault.kdbx").getOrThrow()
        assertEquals(
            "ISO8601 时间必须解析为毫秒时间戳",
            Instant.parse("2026-09-04T12:00:00Z").toEpochMilli(),
            meta.lastModifiedMillis
        )
    }

    @Test
    fun `场景4 PROPFIND多response解析健壮性 取首个条目元数据`() = runTest {
        // 真实 Depth:1 列目录响应会含多个 <D:response>；getMetadata 语义为取目标资源
        // （首个），此用例确保解析器不因多 response 崩溃或取错段
        startQueued()
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
                   <D:response><D:propstat><D:prop><D:getetag>"first-1"</D:getetag>
                   <D:getcontentlength>111</D:getcontentlength></D:prop></D:propstat></D:response>
                   <D:response><D:propstat><D:prop><D:getetag>"second-2"</D:getetag>
                   <D:getcontentlength>222</D:getcontentlength></D:prop></D:propstat></D:response>
                   </D:multistatus>"""
            )
        )
        val meta = provider().getMetadata("vault.kdbx").getOrThrow()
        assertEquals("first-1", meta.etag)
        assertEquals(111L, meta.contentLength)
    }

    // ------------------------------------------------------------------
    // 场景 5：特殊字符与中文文件名
    // ------------------------------------------------------------------

    @Test
    fun `场景5 特殊字符文件名全量编码传输`() = runTest {
        startQueued()
        val name = "中文 库#1?a=1&b=2 100%+v.kdbx"
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("payload"))

        val p = provider()
        p.upload(name, "payload".toByteArray()).getOrThrow()
        p.downloadBytes(name).getOrThrow()

        val putPath = server.takeRequest().path.orEmpty()
        val getPath = server.takeRequest().path.orEmpty()
        for (path in listOf(putPath, getPath)) {
            assertTrue("中文必须 UTF-8 编码", path.contains("%E4%B8%AD"))
            assertTrue("'#' 必须编码", path.contains("%23"))
            assertTrue("'?' 必须编码", path.contains("%3F"))
            assertTrue("'&' 必须编码", path.contains("%26"))
            assertTrue("'=' 必须编码", path.contains("%3D"))
            assertTrue("'%' 必须编码", path.contains("%25"))
            assertTrue("'+' 必须编码为 %2B 而非被空格替换吞掉", path.contains("%2B"))
            assertTrue("空格必须为 %20", path.contains("%20"))
            assertFalse("路径中不允许残留裸 '?'（会被解析为查询串）", path.contains("?"))
            assertFalse("路径中不允许残留裸空格", path.contains(" "))
        }
    }

    @Test
    fun `场景5 原子写MOVE目标对特殊字符同样编码`() = runTest {
        val state = startStateful()
        val p = provider()

        val name = "中文 库#1.kdbx"
        val result = p.uploadAtomic(name, "data".toByteArray())
        assertTrue("特殊字符名原子写必须成功: ${result.exceptionOrNull()}", result.isSuccess)

        val dest = state.lastMoveDestination.get().orEmpty()
        assertTrue("MOVE Destination 必须编码 '#'", dest.contains("%23"))
        assertTrue("MOVE Destination 必须编码中文", dest.contains("%E4%B8%AD"))
        assertFalse("Destination 不允许残留裸 '#'", dest.contains("#"))
        val storedKey = state.files.keys.first { it.endsWith(".kdbx") }
        assertArrayEquals("data".toByteArray(), state.files[storedKey])
    }

    // ------------------------------------------------------------------
    // 场景 6：空文件与零字节文件
    // ------------------------------------------------------------------

    @Test
    fun `场景6 零字节文件PUT_GET与元数据往返`() = runTest {
        val state = startStateful()
        val p = provider()

        val etag = p.upload("empty.kdbx", ByteArray(0)).getOrThrow()
        assertTrue(etag.isNotBlank())

        val downloaded = p.downloadBytes("empty.kdbx").getOrThrow()
        assertEquals("零字节文件必须下载为空数组而非失败", 0, downloaded.size)

        val meta = p.getMetadata("empty.kdbx").getOrThrow()
        assertEquals("零字节元数据大小必须为 0", 0L, meta.contentLength)
        assertArrayEquals(ByteArray(0), state.files["/empty.kdbx"])
    }

    // ------------------------------------------------------------------
    // 场景 7：深层嵌套与路径归一
    // ------------------------------------------------------------------

    @Test
    fun `场景7 端点尾部斜杠与路径前导斜杠归一且深层嵌套保持`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
               <D:response><D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop></D:propstat></D:response></D:multistatus>"""
        ))

        val baseWithSlash = server.url("/").toString() + "base/"
        val p = WebDavSyncProvider(baseWithSlash, "tester", "pw".toCharArray(), client = plainClient)
        p.upload("/deep/a/b/vault.kdbx", "data".toByteArray()).getOrThrow()

        val path = server.takeRequest().path.orEmpty()
        assertEquals("端点尾斜杠与路径前导斜杠必须归一为单斜杠", "/base/deep/a/b/vault.kdbx", path)
    }

    @Test
    fun `场景7 无斜杠端点与相对路径拼接同样归一`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
               <D:response><D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop></D:propstat></D:response></D:multistatus>"""
        ))

        val baseNoSlash = server.url("/").toString().removeSuffix("/") + "/remote.php/webdav"
        val p = WebDavSyncProvider(baseNoSlash, "tester", "pw".toCharArray(), client = plainClient)
        p.upload("x/y/vault.kdbx", "data".toByteArray()).getOrThrow()

        assertEquals("/remote.php/webdav/x/y/vault.kdbx", server.takeRequest().path)
    }

    @Test
    fun `场景7 深层嵌套路径原子写MOVE保持结构`() = runTest {
        val state = startStateful()
        val result = provider().uploadAtomic("a/b/c/d/vault.kdbx", "deep".toByteArray())
        assertTrue("深层路径原子写必须成功: ${result.exceptionOrNull()}", result.isSuccess)
        assertNotNull("目标必须落在深层路径", state.files["/a/b/c/d/vault.kdbx"])
        assertTrue("临时文件不残留", state.tmpResidues().isEmpty())
    }

    // ------------------------------------------------------------------
    // 场景 9：存储后端不可用
    // ------------------------------------------------------------------

    @Test
    fun `场景9 连接被拒如实失败`() = runTest {
        startQueued()
        val url = server.url("/").toString()
        server.shutdown()
        val result = provider().downloadBytes("vault.kdbx")
        assertTrue("连接被拒必须如实失败: ${result.getOrNull()}", result.isFailure)
        assertTrue(url.isNotBlank())
    }

    @Test
    fun `场景9 连接超时如实失败`() = runTest {
        // 保留网段不可路由地址 + 极短连接超时 => 确定性 connect timeout
        val shortConnect = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val p = WebDavSyncProvider("http://10.255.255.1:9", "tester", "pw".toCharArray(), client = shortConnect)
        val result = p.downloadBytes("vault.kdbx")
        assertTrue("连接超时必须如实失败", result.isFailure)
    }

    @Test
    fun `场景9 https端点指向明文服务器 TLS握手失败如实失败`() = runTest {
        startQueued() // 明文 HTTP 服务器
        val p = WebDavSyncProvider("https://localhost:${server.port}", "tester", "pw".toCharArray(), client = plainClient)
        val result = p.downloadBytes("vault.kdbx")
        assertTrue("TLS 握手失败必须如实失败", result.isFailure)
    }
}
