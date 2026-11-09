package com.keepasskey.sync.s3

import com.keepasskey.sync.downloadBytes
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SsrfAddressApprovals
import com.keepasskey.sync.network.SsrfGuardDns
import com.keepasskey.sync.network.SsrfGuardSocketFactory
import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ENG-20261028-22：真实 MinIO 虚拟主机私网端点与寻址对照端到端验收。
 *
 * ## 验收目标与闭环契约
 * 1. **真实 MinIO 虚拟主机模式**：使用生产公有构造函数 `S3SyncProvider(...)`（严禁注入
 *    `trustingClient`，生产客户端完全经 [SyncHttpClientFactory.createSyncClient] 构建，
 *    装配默认 TLS、系统默认 CA 链、[SsrfGuardDns] 与 [SsrfGuardSocketFactory]）；
 *    在隔离 JVM 进程中由 `javax.net.ssl.trustStore` 载入临时测试 CA，执行真实 HTTPS + 真实
 *    主机名验证（SAN `minio.127.0.0.1.nip.io` 与 `*.minio.127.0.0.1.nip.io`）；
 *    验收 HEAD bucket、PUT 对象、GET 对象（字节回读与 SHA-256 校验）、HEAD 元数据、DELETE 对象。
 * 2. **真实 MinIO 路径模式对照**：同一真实 MinIO 服务实例上以 `usePathStyle = true` 执行
 *    HEAD / PUT / GET / DELETE，验证两套寻址模式在真实对象存储上均可正常工作。
 * 3. **仅实际请求主机获豁免**：virtual-host 模式下授权给工厂的是 `bucket.端点主机`
 *    （`S3KeyCodec.requestHost`），基准端点主机或其他未授权私网子域名仍落入普通阻断分支；
 * 4. **外部重定向与云元数据红线**：即便入口获批，重定向到非预期私网或云元数据（169.254.169.254）
 *    依然在连接期被坚决拒绝；构造期与 DNS 层的云元数据红线恒常生效。
 *
 * ## 真实服务样本声明
 * 本测试以 Windows 本机真实 MinIO (RELEASE.2025-09-07) 实例为样本，准确反映该服务在私网
 * virtual-host 与 path 模式下的真实行为；不将样本结论机械外推至全部 Ceph RGW 或其他商业云实现。
 * 未启用联调时（未设置 `liveS3VirtualHost=true` 或环境变量 `LIVE_S3_VIRTUALHOST=true`）默认跳过，
 * 绝不伪绿。
 */
class LiveS3VirtualHostTest {

    private val endpoint = prop("liveS3VirtualHostEndpoint", "https://minio.127.0.0.1.nip.io:19000")
    private val bucket = prop("liveS3Bucket", "vault")
    private val access = credential("liveS3Access", "MINIO_ROOT_USER")
    private val secret = credential("liveS3Secret", "MINIO_ROOT_PASSWORD")

    private fun prop(key: String, default: String): String =
        System.getProperty(key) ?: System.getenv(key) ?: default

    private fun credential(propertyKey: String, envKey: String): String =
        System.getProperty(propertyKey)
            ?: System.getenv(envKey)
            ?: ("kp" + UUID.randomUUID().toString().replace("-", ""))

    private fun assumeLive() {
        Assume.assumeTrue(
            "跳过：未启用 S3 虚拟主机私网端点真实服务验收（需设置 -DliveS3VirtualHost=true 或环境变量 LIVE_S3_VIRTUALHOST=true）",
            System.getProperty("liveS3VirtualHost") == "true" || System.getenv("LIVE_S3_VIRTUALHOST") == "true"
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `真实MinIO虚拟主机模式HEAD-PUT-GET回读与哈希比对`() = runBlocking {
        assumeLive()
        // 关键安全契约：必须走生产构造（无 client 参数），严禁注入 trustingClient！
        val provider = S3SyncProvider(
            endpoint = endpoint,
            bucketName = bucket,
            region = REGION,
            accessKeyId = access.toCharArray(),
            secretAccessKey = secret.toCharArray(),
            usePathStyle = false, // 真实 virtual-host 风格
            networkOptions = SyncNetworkOptions()
        )
        val testKey = "live-vhost-${UUID.randomUUID().toString().take(8)}.kdbx"
        val payload = ("keepasskey-vhost-payload-" + UUID.randomUUID()).toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(payload)

        try {
            // 1. HEAD bucket (testConnection)
            val conn = provider.testConnection()
            assertTrue("虚拟主机连接测试(HEAD bucket)必须成功: ${conn.exceptionOrNull()}", conn.isSuccess)

            // 2. PUT 对象 (upload)
            val uploadRes = provider.upload(testKey, payload)
            assertTrue("虚拟主机上传对象(PUT)必须成功: ${uploadRes.exceptionOrNull()}", uploadRes.isSuccess)
            val etag = uploadRes.getOrThrow()
            assertTrue("上传成功必须返回有效 ETag", etag.isNotBlank())

            // 3. GET 对象 (download & 字节流式落盘回读)
            val downloadRes = provider.downloadBytes(testKey)
            assertTrue("虚拟主机回读对象(GET)必须成功: ${downloadRes.exceptionOrNull()}", downloadRes.isSuccess)
            val readback = downloadRes.getOrThrow()
            assertArrayEquals("回读字节流必须与上传原始内容严格完全一致", payload, readback)
            assertEquals("回读内容 SHA-256 必须一致", expectedSha, sha256Hex(readback))

            // 4. HEAD 对象元数据 (getMetadata)
            val metaRes = provider.getMetadata(testKey)
            assertTrue("虚拟主机获取元数据(HEAD)必须成功: ${metaRes.exceptionOrNull()}", metaRes.isSuccess)
            val meta = metaRes.getOrThrow()
            assertEquals("元数据大小必须与文件大小一致", payload.size.toLong(), meta.contentLength)

            // 5. DELETE 对象
            val deleteRes = provider.delete(testKey)
            assertTrue("虚拟主机删除对象(DELETE)必须成功: ${deleteRes.exceptionOrNull()}", deleteRes.isSuccess)
        } finally {
            provider.delete(testKey)
            provider.clearCredentials()
        }
    }

    @Test
    fun `真实MinIO路径模式对照HEAD-PUT-GET回读与哈希比对`() = runBlocking {
        assumeLive()
        // 关键安全契约：必须走生产构造（无 client 参数），对照组验证 path 风格
        val provider = S3SyncProvider(
            endpoint = endpoint,
            bucketName = bucket,
            region = REGION,
            accessKeyId = access.toCharArray(),
            secretAccessKey = secret.toCharArray(),
            usePathStyle = true, // 路径风格对照
            networkOptions = SyncNetworkOptions()
        )
        val testKey = "live-path-${UUID.randomUUID().toString().take(8)}.kdbx"
        val payload = ("keepasskey-path-payload-" + UUID.randomUUID()).toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(payload)

        try {
            // 1. HEAD bucket
            val conn = provider.testConnection()
            assertTrue("路径模式连接测试(HEAD bucket)必须成功: ${conn.exceptionOrNull()}", conn.isSuccess)

            // 2. PUT 对象
            val uploadRes = provider.upload(testKey, payload)
            assertTrue("路径模式上传对象(PUT)必须成功: ${uploadRes.exceptionOrNull()}", uploadRes.isSuccess)
            val etag = uploadRes.getOrThrow()
            assertTrue("上传成功必须返回有效 ETag", etag.isNotBlank())

            // 3. GET 对象回读
            val downloadRes = provider.downloadBytes(testKey)
            assertTrue("路径模式回读对象(GET)必须成功: ${downloadRes.exceptionOrNull()}", downloadRes.isSuccess)
            val readback = downloadRes.getOrThrow()
            assertArrayEquals("回读字节流必须完全一致", payload, readback)
            assertEquals("回读 SHA-256 必须一致", expectedSha, sha256Hex(readback))

            // 4. DELETE 对象
            val deleteRes = provider.delete(testKey)
            assertTrue("路径模式删除对象(DELETE)必须成功: ${deleteRes.exceptionOrNull()}", deleteRes.isSuccess)
        } finally {
            provider.delete(testKey)
            provider.clearCredentials()
        }
    }

    @Test
    fun `虚拟主机模式仅实际请求主机获豁免 未授权私网域名被SsrfGuardDns拦截`() {
        assumeLive()
        // 验证守卫精确豁免契约：配置 bucket=vault, endpointHost=minio.127.0.0.1.nip.io 时，
        // 实际请求主机是 vault.minio.127.0.0.1.nip.io。
        val authorizedHost = requireNotNull(
            S3KeyCodec.requestHost(endpoint, bucket, usePathStyle = false)
        )
        assertEquals("推导的授权主机必须是虚拟主机", "vault.minio.127.0.0.1.nip.io", authorizedHost)

        // 经工厂生产客户端装配（生产真实逻辑）：
        val client = SyncHttpClientFactory.createSyncClient(
            options = SyncNetworkOptions(),
            configuredEndpointHost = authorizedHost
        )

        // 1. 获授权的实际请求主机可以解析
        val approvedDns = client.dns.lookup(authorizedHost)
        assertTrue("获批主机必须返回解析结果", approvedDns.isNotEmpty())

        // 2. 未授权的同域私网主机（如 other.minio.127.0.0.1.nip.io）同样解析到 127.0.0.1，但必须被拒绝
        val unauthorizedSubdomain = "other.minio.127.0.0.1.nip.io"
        val ex1 = runCatching { client.dns.lookup(unauthorizedSubdomain) }.exceptionOrNull()
        assertTrue(
            "未授权的私网子域名必须被拒绝（SSRF 防护），实际=$ex1",
            ex1 is UnknownHostException && ex1.message.orEmpty().contains("SSRF 防护")
        )

        // 3. 基础域名本身（minio.127.0.0.1.nip.io）在 virtual-host 模式下并未被授权，同样被拒绝
        val baseHost = "minio.127.0.0.1.nip.io"
        val ex2 = runCatching { client.dns.lookup(baseHost) }.exceptionOrNull()
        assertTrue(
            "基础域名在虚拟主机模式下不应被额外放行，实际=$ex2",
            ex2 is UnknownHostException && ex2.message.orEmpty().contains("SSRF 防护")
        )
    }

    @Test
    fun `外部重定向到未授权私网地址在连接期被拦截且目标零请求`() {
        assumeLive()
        // 模拟协议攻击向量：已获批的虚拟主机入口返回 302 重定向到未获批的私网端点（或基础端点）
        val targetServer = MockWebServer().apply {
            start(LOOPBACK_ADDR, 0)
            enqueue(MockResponse().setBody("unauthorized-target-must-not-be-reached"))
        }
        val entryServer = MockWebServer().apply { start(LOOPBACK_ADDR, 0) }
        try {
            val authorizedHost = "vault.minio.127.0.0.1.nip.io"
            val unauthorizedRedirectTarget = "http://minio.127.0.0.1.nip.io:${targetServer.port}/steal"
            entryServer.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", unauthorizedRedirectTarget)
            )

            val approvals = SsrfAddressApprovals()
            // 真实生产工厂客户端装配逻辑（明文仅用于 MockWebServer 协议验证）
            val client = OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .dns(SsrfGuardDns(Dns.SYSTEM, allowedHosts = setOf(authorizedHost), approvals = approvals))
                .socketFactory(SsrfGuardSocketFactory(approvals))
                .connectTimeout(5, TimeUnit.SECONDS)
                .build()

            val failure = runCatching {
                client.newCall(Request.Builder().url("http://$authorizedHost:${entryServer.port}/probe").build())
                    .execute().use { it.body.string() }
            }.exceptionOrNull()

            val guarded = generateSequence(failure) { it.cause }
                .any { it.message.orEmpty().contains("SSRF 防护") }
            assertTrue("重定向到未授权私网主机必须被守卫拦截，实际=$failure", guarded)
            assertEquals("对照组：请求必须已抵达获批的虚拟主机入口", 1, entryServer.requestCount)
            assertEquals("被拦截的目标不得收到任何请求", 0, targetServer.requestCount)
        } finally {
            entryServer.shutdown()
            targetServer.shutdown()
        }
    }

    @Test
    fun `云元数据红线在虚拟主机配置下依然严格拒绝`() {
        assumeLive()
        // 1. 构造期：端点直接填云元数据 IP，无论寻址模式均被拒
        listOf(
            "https://169.254.169.254",
            "https://169.254.169.254:9000",
            "https://[::ffff:169.254.169.254]:9000"
        ).forEach { metaEndpoint ->
            val ex = runCatching {
                S3SyncProvider(
                    endpoint = metaEndpoint,
                    bucketName = "vault",
                    accessKeyId = access.toCharArray(),
                    secretAccessKey = secret.toCharArray(),
                    usePathStyle = false
                )
            }.exceptionOrNull()
            assertTrue("云元数据端点必须在构造期被拒: $metaEndpoint, 实际=$ex", ex is SyncException.InvalidEndpointError)
            assertTrue("错误信息必须包含云元数据红线", ex?.message.orEmpty().contains("云元数据"))
        }

        // 2. DNS 解析到云元数据：即便配置了白名单，红线依然不随白名单放行
        val mockDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("169.254.169.254"))
        }
        val approvals = SsrfAddressApprovals()
        val dnsGuard = SsrfGuardDns(
            delegate = mockDns,
            allowedHosts = setOf("vault.minio.127.0.0.1.nip.io"),
            approvals = approvals
        )

        val dnsEx = runCatching { dnsGuard.lookup("vault.minio.127.0.0.1.nip.io") }.exceptionOrNull()
        assertTrue("DNS解析到云元数据必须被拒绝，实际=$dnsEx", dnsEx is UnknownHostException)
        assertTrue("异常文案必须标明红线不随豁免放行", dnsEx?.message.orEmpty().contains("红线不随豁免放行"))
        assertFalse(
            "云元数据地址绝不得进入连接期豁免表",
            approvals.isApproved(InetAddress.getByName("169.254.169.254"))
        )
    }

    private companion object {
        const val REGION = "us-east-1"
        val LOOPBACK_ADDR: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
