package com.keepasskey.sync.s3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.sync.model.SyncException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional AVD integration test against a separately running S3-compatible HTTP service.
 *
 * ISSUE-P2-655 / P3-650：服务端凭据可由 runner 参数 `s3AccessKey` / `s3SecretKey` 提供——
 * 联调脚本按次生成随机凭据时，本用例才能与之对齐；未提供时退回仓库内既有的**显式测试
 * fixture**（仅用于本地隔离服务，不进入生产工具，也不作为任何默认口令）。
 */
@RunWith(AndroidJUnit4::class)
class S3IndependentServiceDeviceTest {

    private lateinit var provider: S3SyncProvider
    private lateinit var endpoint: String
    private lateinit var accessKey: String

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        endpoint = arguments.getString(ARG_ENDPOINT).orEmpty()
        assumeTrue("Provide -Pandroid.testInstrumentationRunnerArguments.$ARG_ENDPOINT", !endpoint.isNullOrBlank())
        accessKey = arguments.getString(ARG_ACCESS_KEY) ?: ACCESS_KEY
        provider = newProvider(
            access = accessKey,
            secret = arguments.getString(ARG_SECRET_KEY) ?: SECRET_KEY
        )
    }

    private fun newProvider(access: String, secret: String): S3SyncProvider = S3SyncProvider(
        endpoint = endpoint,
        bucketName = BUCKET,
        region = REGION,
        accessKeyId = access.toCharArray(),
        secretAccessKey = secret.toCharArray(),
        usePathStyle = true,
        client = OkHttpClient()
    )

    @After
    fun tearDown() {
        if (::provider.isInitialized) provider.clearCredentials()
    }

    @Test
    fun `独立S3服务接受SigV4并回读含与号前缀的ISO时间和续页`() = runBlocking {
        val startMillis = System.currentTimeMillis() - CLOCK_TOLERANCE_MILLIS
        val connection = provider.testConnection()
        assertTrue("独立服务必须接受 SigV4 bucket HEAD: ${connection.exceptionOrNull()}", connection.isSuccess)

        val first = provider.listRemoteDirectory("notes&archive", cursor = null, pageSize = 1)
        assertTrue("首分页必须成功: ${first.exceptionOrNull()}", first.isSuccess)
        val firstPage = first.getOrThrow()
        assertEquals("notes& 前缀必须整体作为单一 ListObjects 参数", "notes&archive/a.kdbx", firstPage.entries.single().path)
        assertEquals("对象大小必须来自独立服务响应", FIXTURE_BYTES.size.toLong(), firstPage.entries.single().contentLength)
        assertTrue("独立服务返回的 ISO LastModified 必须解析", firstPage.entries.single().lastModifiedMillis > startMillis)
        assertTrue("pageSize=1 且有两项时必须返回续页", firstPage.truncated)
        val cursor = requireNotNull(firstPage.nextCursor) { "截断页必须给出续页 token" }

        val second = provider.listRemoteDirectory("notes&archive", cursor = cursor, pageSize = 1)
        assertTrue("续页必须成功: ${second.exceptionOrNull()}", second.isSuccess)
        val secondPage = second.getOrThrow()
        assertEquals("续页必须返回第二个对象", "notes&archive/b.kdbx", secondPage.entries.single().path)
        assertTrue("最后一页不得继续标为截断", !secondPage.truncated)
        assertEquals("最后一页游标必须清空", null, secondPage.nextCursor)
    }

    @Test
    fun `独立S3服务拒绝错误的SigV4密钥`() = runBlocking {
        val wrongCredentials = newProvider(
            access = accessKey,
            secret = "wrong-secret-for-device-test"
        )
        try {
            val result = wrongCredentials.testConnection()
            assertTrue("错误的 SigV4 secret 不得认证成功", result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue("错误凭据应映射为 AuthenticationError: $error", error is SyncException.AuthenticationError)
            assertTrue("错误凭据应由 HTTP 403 拒绝: ${error?.message}", error?.message?.contains("403") == true)
        } finally {
            wrongCredentials.clearCredentials()
        }
    }

    private companion object {
        const val ARG_ENDPOINT = "s3Endpoint"
        const val ARG_ACCESS_KEY = "s3AccessKey"
        const val ARG_SECRET_KEY = "s3SecretKey"
        const val BUCKET = "kp-device-validation"
        const val REGION = "us-east-1"

        /** 显式测试 fixture（仅用于本地隔离服务；生产工具与本用例默认值都不来自此常量）。 */
        const val ACCESS_KEY = "DEVICEACCESSKEY"
        const val SECRET_KEY = "devicesecretkey"
        const val CLOCK_TOLERANCE_MILLIS = 5 * 60 * 1000L
        val FIXTURE_BYTES = "device-s3-fixture".toByteArray()
    }
}
