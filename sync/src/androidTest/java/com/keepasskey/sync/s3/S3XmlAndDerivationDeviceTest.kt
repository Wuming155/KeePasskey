package com.keepasskey.sync.s3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P3-656` / `ISSUE-P3-657` 的 Android 运行时验收。
 *
 * 前者经真实设备回环 socket 走 `S3SyncProvider.listRemoteDirectory`，确认平台 XML 解析器
 * 能把 ListObjectsV2 的 ISO `<LastModified>` 回读为 epoch，并确认含 `&` 的分页 token
 * 在 wire query 与签名 canonical query 之间保持一致。
 *
 * 后者在设备 JVM 上注入第 2/3/4 步 HMAC 故障，确认已分配的派生数组均在同一清理路径填零。
 * 正常签名路径由数据库/同步层既有设备用例与本次时钟偏移重签场景共同覆盖。
 *
 * 不覆盖：真实 S3 服务端（MockWebServer 不是独立 S3 实现）、跨进程内存取证。
 */
@RunWith(AndroidJUnit4::class)
class S3XmlAndDerivationDeviceTest {

    private lateinit var server: MockWebServer
    private val plainLoopbackClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `设备运行时回读ISO时间并保持分页签名查询一致`() = runBlocking {
        val pageToken = "next&page=%2F+space"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                listXml(
                    lastModified = "2026-10-06T00:00:00.000Z",
                    truncated = true,
                    nextToken = pageToken
                )
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                listXml(
                    lastModified = "2026-10-06T08:00:00+08:00",
                    truncated = false,
                    nextToken = null
                )
            )
        )

        val provider = newProvider()
        val first = provider.listRemoteDirectory("notes&archive", cursor = null, pageSize = 25)
        assertTrue("首分页必须成功: ${first.exceptionOrNull()}", first.isSuccess)
        val firstPage = first.getOrThrow()
        assertEquals("设备 XML 解析必须保留 ISO 时间", 1_791_244_800_000L, firstPage.entries.single().lastModifiedMillis)
        assertEquals("续页 token 必须回传原始值", pageToken, firstPage.nextCursor)
        assertTrue("首分页必须如实标记截断", firstPage.truncated)

        val second = provider.listRemoteDirectory("notes&archive", cursor = pageToken, pageSize = 25)
        assertTrue("续页必须成功: ${second.exceptionOrNull()}", second.isSuccess)
        assertEquals(
            "带偏移的 ISO 时间必须归一到同一 epoch",
            1_791_244_800_000L,
            second.getOrThrow().entries.single().lastModifiedMillis
        )

        val firstRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("首分页请求未到达")
        val secondRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("续页请求未到达")
        assertEquals("首分页必须使用 GET 列举", "GET", firstRequest.method)
        assertEquals("续页必须使用 GET 列举", "GET", secondRequest.method)
        assertTrue(
            "wire prefix 必须整体编码，& 不得拆成额外参数（实际=${firstRequest.path}）",
            firstRequest.path?.contains("prefix=notes%26archive%2F") == true
        )
        val signedQuery = firstRequest.getHeader("Authorization").orEmpty()
        assertTrue("请求必须携带 SigV4 Authorization", signedQuery.startsWith("AWS4-HMAC-SHA256 "))
        assertTrue(
            "续页 token 的 & 必须作为单个参数值编码（实际=${secondRequest.path}）",
            secondRequest.path?.contains("continuation-token=next%26page%3D%252F%2Bspace") == true
        )
        assertEquals(
            "续页必须固定为 list-type / max-keys / delimiter / prefix / continuation-token 五项",
            5,
            secondRequest.requestUrl?.queryParameterNames?.size
        )
        assertEquals(pageToken, secondRequest.requestUrl?.queryParameter("continuation-token"))
    }

    @Test
    fun `设备运行时第2至4步HMAC故障均擦除已分配派生数组`() {
        for (failAt in 2..4) {
            val allocated = mutableListOf<ByteArray>()
            var call = 0
            val failure = IllegalStateException("device hmac failure at $failAt")
            val thrown = runCatching {
                signer().deriveSigningKeyChain(
                    "AWS4device-secret".toByteArray(),
                    "20261006",
                    "us-east-1",
                    "s3"
                ) { _, _ ->
                    call++
                    if (call == failAt) throw failure
                    ByteArray(32) { (0x21 * call).toByte() }.also { allocated += it }
                }
            }.exceptionOrNull()

            assertTrue("第 $failAt 步受控异常必须原样上浮", thrown === failure)
            assertEquals("第 $failAt 步前必须分配 ${failAt - 1} 个中间量", failAt - 1, allocated.size)
            allocated.forEachIndexed { index, bytes ->
                assertTrue(
                    "第 $failAt 步失败后中间量 ${index + 1} 必须填零，实际=${bytes.toList()}",
                    bytes.all { it == 0.toByte() }
                )
            }
        }
    }

    @Test
    fun `Android不支持JAXP特性时S3解析仍拒绝外部实体`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val canary = "S3-XXE-CANARY-${System.nanoTime()}"
        val canaryFile = File(context.filesDir, "s3-xxe-canary.txt").apply {
            writeText(canary)
        }
        val fileEntity = "file://${canaryFile.absolutePath}"
        try {
            val payloads = listOf(
                """<!DOCTYPE ListBucketResult [<!ENTITY xxe SYSTEM "$fileEntity">]>""" +
                    "<ListBucketResult><Contents><Key>&xxe;</Key><Size>1</Size>" +
                    "</Contents></ListBucketResult>",
                """<!DOCTYPE ListBucketResult SYSTEM "$fileEntity">""" +
                    "<ListBucketResult><Contents><Key>safe-key</Key><Size>1</Size>" +
                    "</Contents></ListBucketResult>"
            )
            for (xml in payloads) {
                val parsed = S3DirectoryList.parseListObjects(xml)
                    ?: error("DOCTYPE 报文必须走完解析并得到确定结果，而不是整篇失败")
                val entries = parsed.entries
                assertTrue(
                    "外部实体 / 外部 DTD 不得被展开到 Key：实际=${entries.map { it.path }}",
                    entries.none { it.path.contains(canary) }
                )
            }
            // 反向对照：同一路径的合法报文必须正常解析，避免“未泄漏”只是“整篇失败”。
            val control = S3DirectoryList.parseListObjects(
                "<ListBucketResult><Contents><Key>control-key</Key><Size>2</Size>" +
                    "<LastModified>2026-10-06T00:00:00Z</LastModified></Contents></ListBucketResult>"
            ) ?: error("合法对照报文必须解析成功")
            assertEquals("control-key", control.entries.single().path)
            assertEquals(2L, control.entries.single().contentLength)
        } finally {
            canaryFile.delete()
        }
    }

    @Test
    fun `设备运行时时钟偏移403重签并擦除首次派生密钥`() = runBlocking {
        val expectedServerMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        val serverDate = java.text.SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            java.util.Locale.US
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(expectedServerMillis))
        val offsets = mutableListOf<Long>()
        val provider = S3SyncProvider(
            endpoint = "http://127.0.0.1:${server.port}",
            bucketName = "device-vault",
            region = "us-east-1",
            accessKeyId = "DEVICEACCESSKEY".toCharArray(),
            secretAccessKey = "devicesecretkey".toCharArray(),
            usePathStyle = true,
            clockOffsetUpdater = { offsets += it },
            client = plainLoopbackClient
        )
        // HEAD 按 HTTP 语义不带实体；偏斜信号由 Date 头与服务端时间的跳变量驱动。
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Date", serverDate)
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val result = provider.testConnection()
        assertTrue("时钟偏移重签后请求必须成功: $result", result.isSuccess)
        val first = server.takeRequest(5, TimeUnit.SECONDS) ?: error("首次签名请求未到达")
        val retried = server.takeRequest(5, TimeUnit.SECONDS) ?: error("重签请求未到达")
        assertEquals("HEAD", first.method)
        assertEquals("HEAD", retried.method)
        assertTrue("首次签名必须携带 x-amz-date", first.getHeader("x-amz-date") != null)
        assertTrue("重签必须携带更新后的 x-amz-date", retried.getHeader("x-amz-date") != null)
        assertFalse(
            "偏移超过容限时两次签名时间必须不同",
            first.getHeader("x-amz-date") == retried.getHeader("x-amz-date")
        )
        assertTrue("偏移量必须经回调持久化", offsets.isNotEmpty())
        assertTrue("偏移方向必须是服务端快于本地", offsets.last() > 0L)
        provider.clearCredentials()
    }

    private fun newProvider(): S3SyncProvider = S3SyncProvider(
        endpoint = "http://127.0.0.1:${server.port}",
        bucketName = "device-vault",
        region = "us-east-1",
        accessKeyId = "DEVICEACCESSKEY".toCharArray(),
        secretAccessKey = "devicesecretkey".toCharArray(),
        usePathStyle = true,
        client = plainLoopbackClient
    )

    private fun signer() = S3RequestSigner(
        accessKeyId = "DEVICEACCESSKEY".toCharArray(),
        secretAccessKey = "devicesecretkey".toCharArray(),
        region = "us-east-1"
    )

    private fun listXml(lastModified: String, truncated: Boolean, nextToken: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        append("<Name>device-vault</Name><Prefix>notes&amp;archive/</Prefix>")
        append("<KeyCount>1</KeyCount><MaxKeys>25</MaxKeys>")
        append("<IsTruncated>").append(truncated).append("</IsTruncated>")
        if (nextToken != null) {
            append("<NextContinuationToken>")
                .append(nextToken.replace("&", "&amp;"))
                .append("</NextContinuationToken>")
        }
        append("<Contents><Key>notes&amp;archive/vault.kdbx</Key><Size>1234</Size>")
        append("<LastModified>").append(lastModified).append("</LastModified></Contents>")
        append("</ListBucketResult>")
    }
}
