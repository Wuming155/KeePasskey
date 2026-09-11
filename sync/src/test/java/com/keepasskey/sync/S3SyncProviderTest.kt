package com.keepasskey.sync

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.s3.S3SyncProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * S3SyncProvider 单元测试：
 * 验证 AWS SigV4 规范请求、Header 计算与鉴权串签名，
 * 以及首传 If-None-Match: * 原子创建与 URL 编码一致性。
 */
class S3SyncProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createLoopbackClient(): OkHttpClient {
        val loopbackDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        return OkHttpClient.Builder().dns(loopbackDns).build()
    }

    @Test
    fun `测试 AWS SigV4 鉴权请求头格式`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-secure-vault",
            region = "us-east-1",
            accessKeyId = "AKIAEXAMPLEKEY".toCharArray(),
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toCharArray()
        )

        val fixedDate = Date(1788500000000L) // 固定测试时间点
        val headers = provider.signV4(
            method = "GET",
            url = "https://my-secure-vault.s3.amazonaws.com/vault.kdbx",
            payloadHash = S3SyncProvider.EMPTY_SHA256,
            dateTime = fixedDate
        )

        assertNotNull(headers["Authorization"])
        assertNotNull(headers["x-amz-date"])
        assertNotNull(headers["x-amz-content-sha256"])
        assertEquals(S3SyncProvider.EMPTY_SHA256, headers["x-amz-content-sha256"])

        val auth = headers["Authorization"].orEmpty()
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLEKEY/"))
        assertTrue(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(auth.contains("Signature="))
    }

    @Test
    fun `测试首传带有 If-None-Match 星号原子创建成功`() = runTest {
        // 1. HEAD 探测返回 404 (文件不存在)
        server.enqueue(MockResponse().setResponseCode(404))
        // 2. PUT 上传返回 200 OK
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"s3-created-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue(result.isSuccess)
        assertEquals("s3-created-etag", result.getOrThrow())

        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)

        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertEquals("*", putReq.getHeader("If-None-Match"))
    }

    @Test
    fun `测试首传 If-None-Match 遭遇并发 412 抛出冲突异常`() = runTest {
        // 1. HEAD 返回 404
        server.enqueue(MockResponse().setResponseCode(404))
        // 2. PUT 返回 412 Precondition Failed (并发被抢先创建)
        server.enqueue(MockResponse().setResponseCode(412))
        // 3. 冲突后获取当前元数据
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"remote-concurrent-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is SyncException.ConflictError)
    }

    @Test
    fun `测试覆盖更新 PUT 附带 If-Match 条件头实现原子覆写`() = runTest {
        // 1. HEAD 预检返回现有 ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"remote-current-etag\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
        )
        // 2. PUT 覆写成功
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"new-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = "remote-current-etag")
        assertTrue(result.isSuccess)
        assertEquals("new-etag", result.getOrThrow())

        val headReq = server.takeRequest()
        assertEquals("HEAD", headReq.method)

        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        // If-Match 必须携带引号包裹的期望 ETag，由服务端原子校验，消除 HEAD+PUT TOCTOU 窗口
        assertEquals("\"remote-current-etag\"", putReq.getHeader("If-Match"))
    }

    @Test
    fun `测试覆盖更新 If-Match 遭遇并发 412 抛出冲突异常`() = runTest {
        // 1. HEAD 预检返回现有 ETag
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"stale-etag\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
        )
        // 2. PUT 被服务端条件校验拒绝（HEAD 后被并发修改）
        server.enqueue(MockResponse().setResponseCode(412))
        // 3. 冲突后获取当前远端元数据
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"concurrent-new-etag\"")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = "stale-etag")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is SyncException.ConflictError)
    }

    @Test
    fun `测试覆盖更新 ETag 预检不匹配快速失败不发 PUT`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"someone-elses-etag\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
        )

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = "my-expected-etag")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ConflictError)
        // 仅 HEAD 预检，无 PUT 发出
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `测试首传前置探测网络失败时快速失败不发无条件PUT`() = runTest {
        // F6 修复：expectedEtag 为空且 HEAD 探测遭遇非 404 失败（网络错误/5xx）时必须
        // 快速失败——若退化为无条件 PUT，远端存在他人更新时将被静默覆盖
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient()
        )

        val result = provider.upload("vault.kdbx", "data".toByteArray(), expectedEtag = null)
        assertTrue("探测失败必须 fail-fast: ${result.exceptionOrNull()}", result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ProtocolError)
        // 仅 HEAD 探测，绝无 PUT 发出
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `测试编码对象键与 SigV4 规范 URI 一致性`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-vault",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray()
        )

        val fixedDate = Date(1788500000000L)
        // 含中文与空格路径
        val encodedUrl = "https://my-vault.s3.amazonaws.com/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95/test%20db.kdbx"
        val headers = provider.signV4(
            method = "PUT",
            url = encodedUrl,
            payloadHash = S3SyncProvider.EMPTY_SHA256,
            dateTime = fixedDate
        )

        val auth = headers["Authorization"].orEmpty()
        assertTrue(auth.contains("Signature="))
        // 确保签名计算成功且格式合法
        assertEquals("my-vault.s3.amazonaws.com", headers["Host"])
    }

    // ===== TASK-26：AWS SigV4 URI 编码规范（* 必须编码、~ 必须保留）回归锁 =====

    @Test
    fun `测试对象键编码符合 AWS 规范已知答案`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-vault",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray()
        )

        // `*` 必须编码为 %2A、`~` 必须原样保留、空格为 %20（非 +）、中文按 UTF-8 百分号大写编码
        val encoded = provider.encodePath("keepasskey/信号*~bar baz.kdbx")
        assertEquals("keepasskey/%E4%BF%A1%E5%8F%B7%2A~bar%20baz.kdbx", encoded)
    }

    @Test
    fun `ISSUE_P3_56_对象键剔除点段与点点段`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-vault",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray()
        )

        // ISSUE-P3-56 子项 2：`.` / `..` 段被剔除（对齐 WebDAV 既有过滤语义），杜绝路径遍历
        assertEquals("keepasskey/vault.kdbx", provider.encodePath("keepasskey/../vault.kdbx"))
        assertEquals("keepasskey/vault.kdbx", provider.encodePath("keepasskey/./vault.kdbx"))
        // 含点但非纯点段的文件名不受影响
        assertEquals("keepasskey/a.b.kdbx", provider.encodePath("keepasskey/a.b.kdbx"))
    }

    @Test
    fun `测试 SigV4 签名已知答案向量含星号波浪号与UTF8键`() {
        // 已知答案向量由独立参考实现（Python hmac/hashlib）离线预计算，与被测实现零共享代码
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "examplebucket",
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE".toCharArray(),
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toCharArray()
        )

        val fixedDate = Date(1788500000000L)
        val url = "https://examplebucket.s3.amazonaws.com/keepasskey/%E4%BF%A1%E5%8F%B7%2A~bar%20baz.kdbx"
        val headers = provider.signV4(
            method = "GET",
            url = url,
            payloadHash = S3SyncProvider.EMPTY_SHA256,
            dateTime = fixedDate
        )

        assertEquals("20260904T053320Z", headers["x-amz-date"])
        val auth = headers["Authorization"].orEmpty()
        assertTrue(auth.contains("Credential=AKIAIOSFODNN7EXAMPLE/20260904/us-east-1/s3/aws4_request"))
        assertTrue(
            "签名与独立参考实现预计算值不一致: $auth",
            auth.contains("Signature=115f4d4984e0f7584c4d687b64f7a508d64ad831da9affab921fb8105bc82c7b")
        )
    }

    // ===== TASK-45（FINDINGS P2-14）：S3 SigV4 服务端时钟偏移补偿 =====

    private fun httpDate(serverMillis: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(serverMillis))

    private fun parseAmzDate(value: String): Long {
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.parse(value)?.time ?: 0L
    }

    private fun loopbackProvider(initialOffset: Long = 0L, offsetUpdater: ((Long) -> Unit)? = null) =
        S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "test-bucket",
            region = "us-east-1",
            accessKeyId = "TESTKEY".toCharArray(),
            secretAccessKey = "TESTSECRET".toCharArray(),
            client = createLoopbackClient(),
            initialClockOffsetMillis = initialOffset,
            clockOffsetUpdater = offsetUpdater
        )

    @Test
    fun `TASK45_正向偏移注入_签名采用补偿后时间`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"e1\""))
        val offset = 30L * 60 * 1000 // 设备时钟比服务端慢 30 分钟（超出 SigV4 +15min 容限）
        val provider = loopbackProvider(initialOffset = offset)

        val result = provider.getMetadata("vault.kdbx")
        assertTrue("应成功: ${result.exceptionOrNull()}", result.isSuccess)

        val amzDate = parseAmzDate(server.takeRequest().getHeader("x-amz-date").orEmpty())
        val expected = System.currentTimeMillis() + offset
        assertTrue(
            "x-amz-date 必须按 本地时间+偏移 补偿 (actual=$amzDate, expected≈$expected)",
            Math.abs(amzDate - expected) < 10_000
        )
    }

    @Test
    fun `TASK45_负向偏移注入_签名采用补偿后时间`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"e1\""))
        val offset = -45L * 60 * 1000 // 设备时钟比服务端快 45 分钟（超出 SigV4 -15min 容限）
        val provider = loopbackProvider(initialOffset = offset)

        val result = provider.getMetadata("vault.kdbx")
        assertTrue("应成功: ${result.exceptionOrNull()}", result.isSuccess)

        val amzDate = parseAmzDate(server.takeRequest().getHeader("x-amz-date").orEmpty())
        val expected = System.currentTimeMillis() + offset
        assertTrue(
            "x-amz-date 必须按 本地时间+偏移 补偿 (actual=$amzDate, expected≈$expected)",
            Math.abs(amzDate - expected) < 10_000
        )
    }

    @Test
    fun `TASK45_首次同步偏斜403_据Date头自愈重试一次成功`() = runTest {
        // getMetadata 走 HEAD 请求：HTTP 规范禁止 HEAD 携带错误主体（真实 AWS 亦无 body），
        // 故偏斜判定依赖 Date 头偏移跳变信号——MockWebServer 对 HEAD 若设 body 会违规发送
        // 实体字节污染持久连接，此处严禁设置 body
        val serverTimeAhead = System.currentTimeMillis() + 25L * 60 * 1000
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Date", httpDate(serverTimeAhead))
        )
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"ok-etag\""))

        val reportedOffsets = mutableListOf<Long>()
        val provider = loopbackProvider(offsetUpdater = { reportedOffsets.add(it) })

        val result = provider.getMetadata("vault.kdbx")
        assertTrue("偏斜自愈后应成功: ${result.exceptionOrNull()}", result.isSuccess)

        assertEquals("恰好一次自愈重试（共 2 个请求）", 2, server.requestCount)
        // FIFO：第 1 个为未补偿的首请求（403），第 2 个才是补偿后重试请求
        val firstRequest = server.takeRequest()
        val retryRequest = server.takeRequest()
        val firstAmzDate = parseAmzDate(firstRequest.getHeader("x-amz-date").orEmpty())
        assertTrue(
            "首请求应使用本地时间（未补偿）",
            Math.abs(firstAmzDate - System.currentTimeMillis()) < 10_000
        )
        val retryAmzDate = parseAmzDate(retryRequest.getHeader("x-amz-date").orEmpty())
        assertTrue(
            "重试签名时间应逼近首响应 Date 头服务端时间 (retry=$retryAmzDate, server≈$serverTimeAhead)",
            Math.abs(retryAmzDate - serverTimeAhead) < 10_000
        )
        assertTrue("偏移刷新后必须回调持久化", reportedOffsets.isNotEmpty())
        assertTrue(
            "回调偏移应≈+25min (actual=${reportedOffsets.last()})",
            Math.abs(reportedOffsets.last() - 25L * 60 * 1000) < 10_000
        )
    }

    @Test
    fun `TASK45_偏斜403无有效Date头_failClosed不盲目重试`() = runTest {
        // 无有效 Date 头 = 无法刷新偏移 → 无法确认偏斜 → 按原路径如实上浮（不静默放行也不盲目重试）
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Date", "not-a-parseable-date")
        )
        val provider = loopbackProvider()

        val result = provider.getMetadata("vault.kdbx")

        assertTrue(result.isFailure)
        assertTrue("偏斜无 Date 头须按原路径上浮鉴权错误", result.exceptionOrNull() is SyncException.AuthenticationError)
        assertEquals("无有效 Date 头不得盲目重试", 1, server.requestCount)
    }

    // ===== ISSUE-P1-05（ZT-05）：SSRF 与 S3 桶名主机注入防线 =====

    @Test
    fun `生产路径桶名注入 x at evil dot com 在构造期被拒`() {
        // `x@evil.com/` 拼进 virtual-host authority 后，真实主机被改写为 evil.com（userinfo 注入），
        // 且 SigV4 canonicalHeaders 取自被注入后的 host → 签名对攻击者主机自洽
        val ex = runCatching {
            S3SyncProvider(
                endpoint = "https://s3.amazonaws.com",
                bucketName = "x@evil.com/",
                accessKeyId = "TESTKEY".toCharArray(),
                secretAccessKey = "TESTSECRET".toCharArray()
            )
        }.exceptionOrNull()
        assertTrue("桶名 @ / 注入必须被拒", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `生产路径桶名注入 x 井号 在构造期被拒`() {
        val ex = runCatching {
            S3SyncProvider(
                endpoint = "https://s3.amazonaws.com",
                bucketName = "x#",
                accessKeyId = "TESTKEY".toCharArray(),
                secretAccessKey = "TESTSECRET".toCharArray()
            )
        }.exceptionOrNull()
        assertTrue("桶名 # 注入必须被拒", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `生产路径内网与云元数据 IP 端点在构造期被拒`() {
        // 用户可控端点直连云元数据服务（169.254.169.254）与内网段属 SSRF，构造期即 fail-closed
        listOf(
            "https://169.254.169.254",
            "https://192.168.1.10",
            "https://127.0.0.1"
        ).forEach { endpoint ->
            val ex = runCatching {
                S3SyncProvider(
                    endpoint = endpoint,
                    bucketName = "test-bucket",
                    accessKeyId = "TESTKEY".toCharArray(),
                    secretAccessKey = "TESTSECRET".toCharArray()
                )
            }.exceptionOrNull()
            assertTrue("内网/元数据端点 \"$endpoint\" 必须被拒", ex is SyncException.InvalidEndpointError)
        }
    }
}
