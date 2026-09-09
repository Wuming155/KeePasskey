package com.keepasskey.sync.scenario

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.s3.S3SyncProvider
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * S3 兼容存储同步真实使用场景测试（对应《同步存储测试场景清单》10 项）。
 *
 * 条件写语义（If-None-Match / If-Match）由有状态模拟器真实裁决，
 * 并发竞态断言从协议语义涌现而非手工编排；SigV4 签名正确性由真实 MinIO 联调覆盖。
 *
 * 场景 -> 用例映射：
 * 1. 多端并发冲突：S3-1/2（过期 ETag 预检即拒 / 编辑对删除）
 * 2. 大文件传输：S3-4（2MiB 字节精确往返；分段上传契约不存在，见 README）
 * 3. 网络中断与重试：S3-5/6/12（超时 / 5xx 与 401 / 删除幂等）
 * 4. 元数据一致性：S3-7（ETag 清洗 + Last-Modified + 大小映射）
 * 5. 特殊字符与中文：S3-8（对象键全量编码）
 * 6. 空文件：S3-9（零字节对象 + 空载荷哈希）
 * 7. 深层嵌套与路径归一：S3-10
 * 8. 并发上传不同版本：S3-3（同基线条件写竞态恰好一胜）+ S3-3b（首传竞态结果有界）
 * 9. 后端不可用：S3-11（连接拒绝 / 连接超时）
 * 10. 大目录分页：SyncProvider 契约无 LIST 操作，N/A（见 README）。
 */
class S3SyncScenarioTest {

    private lateinit var server: MockWebServer

    private val plainClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun startStateful(): StatefulS3Dispatcher {
        val d = StatefulS3Dispatcher()
        server.dispatcher = d
        server.start()
        return d
    }

    private fun startQueued() {
        server.start()
    }

    private fun provider(): S3SyncProvider =
        S3SyncProvider(
            endpoint = server.url("/").toString(),
            bucketName = "bucket",
            region = "us-east-1",
            accessKeyId = "tester",
            secretAccessKey = "tester1234",
            usePathStyle = true,
            client = plainClient
        )

    // ------------------------------------------------------------------
    // 场景 1：多端并发冲突（Provider 协议层）
    // ------------------------------------------------------------------

    @Test
    fun `场景1 过期ETag在HEAD预检即判冲突 不发起PUT`() = runTest {
        startQueued()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"current-1\"")
                .setHeader("Content-Length", "3")
        )

        val result = provider().upload("vault.kdbx", "stale".toByteArray(), expectedEtag = "stale-1")

        assertTrue("过期 ETag 必须判冲突", result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ConflictError)
        assertEquals("必须在 HEAD 预检阶段拦截，不发起任何 PUT", 1, server.requestCount)
    }

    @Test
    fun `场景1 一端编辑一端删除 条件写412转ConflictError`() = runTest {
        startQueued()
        // 远端对象已被他端删除：HEAD 404 -> PUT If-Match 对已删除对象判 false -> 412
        // -> 412 后探测远端元数据再次 HEAD 404
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(412))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = provider().upload("vault.kdbx", "local-edit".toByteArray(), expectedEtag = "base-etag")
        assertTrue("编辑对删除必须以冲突暴露", result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ConflictError)
        assertEquals("请求序：HEAD/PUT/HEAD", 3, server.requestCount)
    }

    // ------------------------------------------------------------------
    // 场景 8：并发上传不同版本（协议层竞态）
    // ------------------------------------------------------------------

    @Test
    fun `场景8 同基线并发条件写 恰好一胜且最终一致`() = runTest {
        val state = startStateful()
        val p = provider()

        val etag0 = p.upload("vault.kdbx", "base".toByteArray()).getOrThrow()

        val bytesA = ByteArray(32 * 1024) { 'A'.code.toByte() }
        val bytesB = ByteArray(32 * 1024) { 'B'.code.toByte() }

        val (resultA, resultB) = coroutineScope {
            val a = async(Dispatchers.IO) { p.upload("vault.kdbx", bytesA, expectedEtag = etag0) }
            val b = async(Dispatchers.IO) { p.upload("vault.kdbx", bytesB, expectedEtag = etag0) }
            a.await() to b.await()
        }

        val winnerBytes: ByteArray
        val winnerEtag: String
        if (resultA.isSuccess) {
            assertFalse("同基线并发条件写必须恰好一胜", resultB.isSuccess)
            assertTrue(resultB.exceptionOrNull() is SyncException.ConflictError)
            winnerBytes = bytesA
            winnerEtag = resultA.getOrThrow()
        } else {
            assertTrue("同基线并发条件写必须恰好一胜", resultB.isSuccess)
            assertTrue(resultA.exceptionOrNull() is SyncException.ConflictError)
            winnerBytes = bytesB
            winnerEtag = resultB.getOrThrow()
        }

        assertEquals("远端最终内容必须是胜者载荷", winnerBytes.toList(), (state.objects["/bucket/vault.kdbx"] ?: ByteArray(0)).toList())
        assertEquals(winnerEtag, state.etags["/bucket/vault.kdbx"])
    }

    @Test
    fun `场景8 首传竞态 结果有界且最终内容完整`() = runTest {
        val state = startStateful()
        val p = provider()

        val bytesA = ByteArray(16 * 1024) { 'A'.code.toByte() }
        val bytesB = ByteArray(16 * 1024) { 'B'.code.toByte() }

        val (resultA, resultB) = coroutineScope {
            val a = async(Dispatchers.IO) { p.upload("vault.kdbx", bytesA) }
            val b = async(Dispatchers.IO) { p.upload("vault.kdbx", bytesB) }
            a.await() to b.await()
        }

        // 首传竞态的合法结果域：每方要么成功（If-None-Match 原子创建或锁定所见后覆盖），
        // 要么 ConflictError（412）；绝不出现其他异常形态
        for (r in listOf(resultA, resultB)) {
            if (r.isFailure) {
                assertTrue("异常只能是 ConflictError: ${r.exceptionOrNull()}", r.exceptionOrNull() is SyncException.ConflictError)
            }
        }
        assertTrue("至少一方成功", resultA.isSuccess || resultB.isSuccess)

        // 最终远端内容必须是某一方的完整载荷（绝不混合/截断）
        val final = state.objects["/bucket/vault.kdbx"] ?: ByteArray(0)
        assertTrue(
            "最终内容必须为某一方的完整载荷",
            final.contentEquals(bytesA) || final.contentEquals(bytesB)
        )
    }

    // ------------------------------------------------------------------
    // 场景 2：大文件传输
    // ------------------------------------------------------------------

    @Test
    fun `场景2 2MiB二进制大对象往返字节精确`() = runTest {
        startStateful()
        val p = provider()

        val payload = Random(20260907).nextBytes(2 * 1024 * 1024)
        p.upload("big-vault.kdbx", payload).getOrThrow()
        val downloaded = p.download("big-vault.kdbx").getOrThrow()
        assertArrayEquals("2MiB 载荷必须逐字节一致", payload, downloaded)

        val meta = p.getMetadata("big-vault.kdbx").getOrThrow()
        assertEquals(payload.size.toLong(), meta.contentLength)
    }

    // ------------------------------------------------------------------
    // 场景 3：网络中断与重试
    // ------------------------------------------------------------------

    @Test
    fun `场景3 下载超时如实失败不悬挂`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutClient = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()

        val p = S3SyncProvider(
            endpoint = server.url("/").toString(), bucketName = "bucket",
            accessKeyId = "tester", secretAccessKey = "pw",
            usePathStyle = true, client = shortTimeoutClient
        )
        val start = System.nanoTime()
        val result = p.download("vault.kdbx")
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertTrue("无响应必须在读取超时后如实失败", result.isFailure)
        assertTrue("超时应在合理时间内返回 (actual=${elapsedMs}ms)", elapsedMs < 10_000)
    }

    @Test
    fun `场景3 服务端5xx与401错误如实映射类型化异常`() = runTest {
        // GET 500 -> ProtocolError
        startQueued()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val r500 = provider().download("vault.kdbx")
        assertTrue(r500.exceptionOrNull() is SyncException.ProtocolError)
        server.shutdown()

        // GET 401 -> AuthenticationError
        server = MockWebServer(); startQueued()
        server.enqueue(MockResponse().setResponseCode(401))
        val r401 = provider().download("vault.kdbx")
        assertTrue(r401.exceptionOrNull() is SyncException.AuthenticationError)
        server.shutdown()

        // testConnection HEAD 500 -> ProtocolError
        server = MockWebServer(); startQueued()
        server.enqueue(MockResponse().setResponseCode(500))
        val rConn = provider().testConnection()
        assertTrue("testConnection 5xx 必须如实失败: ${rConn.getOrNull()}", rConn.isFailure)
        assertTrue(rConn.exceptionOrNull() is SyncException.ProtocolError)
    }

    @Test
    fun `场景3 删除不存在对象幂等成功`() = runTest {
        startStateful()
        assertTrue("S3 删除语义幂等（不存在仍 204）", provider().delete("ghost.kdbx").isSuccess)
    }

    // ------------------------------------------------------------------
    // 场景 4：元数据一致性
    // ------------------------------------------------------------------

    @Test
    fun `场景4 HEAD元数据ETag清洗与LastModified解析与大小映射`() = runTest {
        startQueued()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"abc-123\"")
                .setHeader("Content-Length", "1234")
                .setHeader("Last-Modified", "Fri, 04 Sep 2026 10:00:00 GMT")
        )

        val meta = provider().getMetadata("vault.kdbx").getOrThrow()
        assertEquals("带引号 ETag 必须清洗", "abc-123", meta.etag)
        assertEquals(1234L, meta.contentLength)
        assertEquals(
            "RFC 1123 Last-Modified 必须解析为毫秒时间戳",
            Instant.parse("2026-09-04T10:00:00Z").toEpochMilli(),
            meta.lastModifiedMillis
        )
        assertFalse(meta.isDirectory)
    }

    @Test
    fun `场景4 HEAD 404映射FileNotFound`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(404))
        val result = provider().getMetadata("vault.kdbx")
        assertTrue(result.exceptionOrNull() is SyncException.FileNotFound)
    }

    // ------------------------------------------------------------------
    // 场景 5：特殊字符与中文对象键
    // ------------------------------------------------------------------

    @Test
    fun `场景5 特殊字符对象键全量编码传输`() = runTest {
        startQueued()
        val key = "中文 库#1?a=1&b=2 100%+v.kdbx"
        server.enqueue(MockResponse().setResponseCode(404)) // HEAD 预检：真首传
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"e1\""))

        provider().upload(key, "payload".toByteArray()).getOrThrow()

        assertEquals("请求序：HEAD 预检 + PUT", 2, server.requestCount)
        server.takeRequest() // HEAD
        val putReq = server.takeRequest()
        val path = putReq.path.orEmpty()
        assertTrue("中文必须 UTF-8 编码", path.contains("%E4%B8%AD"))
        assertTrue("'#' 必须编码", path.contains("%23"))
        assertTrue("'?' 必须编码", path.contains("%3F"))
        assertTrue("'&' 必须编码", path.contains("%26"))
        assertTrue("'=' 必须编码", path.contains("%3D"))
        assertTrue("'%' 必须编码", path.contains("%25"))
        assertTrue("'+' 必须编码为 %2B", path.contains("%2B"))
        assertTrue("空格必须为 %20", path.contains("%20"))
        assertFalse("路径中不允许残留裸 '?'", path.contains("?"))
        assertFalse("路径中不允许残留裸空格", path.contains(" "))
    }

    // ------------------------------------------------------------------
    // 场景 6：空文件与零字节对象
    // ------------------------------------------------------------------

    @Test
    fun `场景6 零字节对象首传_空载荷哈希头部与空下载`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(404)) // HEAD 预检：真首传
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"d41d8cd9\""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(0))))

        val p = provider()
        val etag = p.upload("empty.kdbx", ByteArray(0)).getOrThrow()
        assertEquals("d41d8cd9", etag)

        server.takeRequest() // HEAD
        val putReq = server.takeRequest()
        assertEquals("首传必须带 If-None-Match: * 原子创建保护", "*", putReq.getHeader("If-None-Match"))
        assertEquals(
            "空载荷的 x-amz-content-sha256 必须为空串哈希（保证 SigV4 规范请求一致）",
            S3SyncProvider.EMPTY_SHA256,
            putReq.getHeader("x-amz-content-sha256")
        )
        assertEquals(0L, putReq.bodySize ?: -1L)

        val downloaded = p.download("empty.kdbx").getOrThrow()
        assertEquals("零字节对象必须下载为空数组而非失败", 0, downloaded.size)
    }

    // ------------------------------------------------------------------
    // 场景 7：深层嵌套与路径归一
    // ------------------------------------------------------------------

    @Test
    fun `场景7 嵌套对象键与端点尾部斜杠归一`() = runTest {
        startQueued()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"e1\""))

        val p = provider() // endpoint 以 / 结尾（MockWebServer url 默认带 /）
        p.upload("a/b/c/vault.kdbx", "data".toByteArray()).getOrThrow()

        server.takeRequest() // HEAD
        val putReq = server.takeRequest()
        assertEquals("端点尾斜杠必须归一，嵌套键保持结构", "/bucket/a/b/c/vault.kdbx", putReq.path)
    }

    // ------------------------------------------------------------------
    // 场景 9：存储后端不可用
    // ------------------------------------------------------------------

    @Test
    fun `场景9 连接被拒如实失败`() = runTest {
        startQueued()
        val url = server.url("/").toString()
        server.shutdown()
        val result = provider().download("vault.kdbx")
        assertTrue("连接被拒必须如实失败: ${result.getOrNull()}", result.isFailure)
        assertTrue(url.isNotBlank())
    }

    @Test
    fun `场景9 连接超时如实失败`() = runTest {
        val shortConnect = OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).build()
        val p = S3SyncProvider(
            endpoint = "http://10.255.255.1:9", bucketName = "bucket",
            accessKeyId = "tester", secretAccessKey = "pw",
            usePathStyle = true, client = shortConnect
        )
        assertTrue("连接超时必须如实失败", p.download("vault.kdbx").isFailure)
    }
}
