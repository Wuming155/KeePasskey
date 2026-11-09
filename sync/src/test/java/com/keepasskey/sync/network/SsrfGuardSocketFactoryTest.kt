package com.keepasskey.sync.network

import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * ISSUE-P2-208：**连接期目标地址复核**（第三层 SSRF 防线）回归。
 *
 * ## 判别口径
 * 整改前的两层防线（构造期 + [SsrfGuardDns]）对 **IP 字面量**天然无效——OkHttp 的
 * `RouteSelector.nextRoutes` 对可解析为 IP 的 host 短路返回路由，**其后**才
 * `dnsLookup`（上游源码与真机证据见 `SsrfRedirectBypassDeviceTest`）。故本类锚定：
 *
 * 1. 未获批的内网字面量必须在**建立 TCP 之前**被拒（异常文案含守卫标识，且目标服务器
 *    零请求——「零请求」是比「异常类型」更强的判据：它证明 SYN 都没发出）；
 * 2. 显式白名单豁免的地址（内网自建场景）必须放行——纵深防御不得退化为可用性回归；
 * 3. `302 → 内网字面量` 的重定向跳转同样在连接期被拒，且对照组证明请求确实先抵达了入口服务。
 *
 * ## 为什么 `127.0.0.1` 只在前两例出现（如实声明）
 * 测试载体（MockWebServer）**只能绑定回环地址**，而回环正是本层要拦的目标类别。
 * 若要构造「入口可达 + 重定向目标被拦」的端到端链路，入口必须经白名单豁免，而豁免按
 * **地址**粒度生效（`localhost` ⇒ `127.0.0.1` 获批），目标若同为 `127.0.0.1` 便一并放行。
 * 故重定向链路的被拦目标改用 AC② 点名的另一字面量 `169.254.169.254`（云元数据），
 * `127.0.0.1` 的拦截语义由第 1 例直接锚定。
 */
class SsrfGuardSocketFactoryTest {

    @Test
    fun `未获批的内网字面量在建连前即被拒且目标零请求`() {
        val server = MockWebServer().apply {
            start(LOOPBACK, 0)
            enqueue(MockResponse().setBody("must-not-be-reached"))
        }
        try {
            val failure = runCatching {
                SsrfGuardSocketFactory().createSocket().use { socket ->
                    socket.connect(InetSocketAddress(LOOPBACK, server.port), CONNECT_TIMEOUT_MS)
                }
            }.exceptionOrNull()

            assertTrue(
                "回环字面量必须在建连前被拒（ISSUE-P2-208 的连接期复核），实际=$failure",
                failure is IOException && failure.message.orEmpty().contains("SSRF 防护")
            )
            assertEquals(
                "被拒的目标不得收到任何请求（证明 TCP 从未建立，而非连上后被拒）",
                0,
                server.requestCount
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `显式白名单豁免的地址在连接期放行`() {
        val server = MockWebServer().apply {
            start(LOOPBACK, 0)
            enqueue(MockResponse().setBody(BODY))
        }
        try {
            // 白名单豁免登记（生产上由 SsrfGuardDns 在主机名命中白名单时写入）
            val approvals = SsrfAddressApprovals().apply { approveAll(listOf(LOOPBACK)) }
            val client = OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .socketFactory(SsrfGuardSocketFactory(approvals))
                .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .build()

            val body = client.newCall(Request.Builder().url(server.url("/")).build())
                .execute().use { it.body.string() }

            assertEquals("获批地址必须正常建连（内网自建 WebDAV / NAS 场景不得被误伤）", BODY, body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `302 重定向到内网字面量在连接期被拒且重定向目标零请求`() {
        val redirectTarget = MockWebServer().apply {
            start(LOOPBACK, 0)
            enqueue(MockResponse().setBody("must-not-be-reached"))
        }
        val entry = MockWebServer().apply { start(LOOPBACK, 0) }
        try {
            entry.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", "http://$METADATA_IPV4:${redirectTarget.port}/")
            )

            // 入口经白名单豁免（localhost ⇒ 127.0.0.1 获批），使请求真实抵达入口并取回 302
            val client = guardedPlainClient(allowedHosts = setOf("localhost"))
            val failure = runCatching {
                client.newCall(Request.Builder().url("http://localhost:${entry.port}/").build())
                    .execute().use { it.body.string() }
            }.exceptionOrNull()

            assertTrue(
                "302 指向内网字面量时必须在连接期被拒（ISSUE-P2-208），实际=$failure",
                failure is IOException && failure.message.orEmpty().contains("SSRF 防护")
            )
            assertEquals("对照组：请求必须已抵达入口服务（否则「被拒」可能源于链路根本没跑起来）", 1, entry.requestCount)
            assertEquals("被拒的重定向目标不得收到任何请求", 0, redirectTarget.requestCount)
        } finally {
            entry.shutdown()
            redirectTarget.shutdown()
        }
    }

    @Test
    fun `云元数据目标即便尝试登记也在建连前被拒`() {
        val metadata4 = InetAddress.getByName(METADATA_IPV4)
        // 16 字节映射形态：保留形态才走得到 16 字节判据（getByAddress 会归一为 4 字节）
        val metadataMapped16 = Inet6Address.getByAddress(null, mappedIpv6(169, 254, 169, 254), -1)
        val approvals = SsrfAddressApprovals().apply { approveAll(listOf(metadata4, metadataMapped16)) }

        assertFalse("云元数据地址不得进入连接期豁免表", approvals.isApproved(metadata4))
        listOf(metadata4, metadataMapped16).forEach { target ->
            val failure = runCatching {
                SsrfGuardSocketFactory(approvals).createSocket()
                    .connect(InetSocketAddress(target, METADATA_PORT), CONNECT_TIMEOUT_MS)
            }.exceptionOrNull()
            assertTrue(
                "元数据红线先于豁免批准（${target.address.size} 字节形态），实际=$failure",
                failure is IOException && failure.message.orEmpty().contains("SSRF 防护")
            )
        }
    }

    @Test
    fun `豁免登记按内嵌 IPv4 跨映射形态生效`() {
        val approvals = SsrfAddressApprovals()
        // 以 16 字节映射形态登记回环，4 字节目标（连接期常见归一形态）必须命中
        approvals.approveAll(listOf(Inet6Address.getByAddress(null, mappedIpv6(127, 0, 0, 1), -1)))
        assertTrue(
            "同一目标的映射拼写与 4 字节形态必须共享豁免键（InetAddress.equals 不跨形态相等）",
            approvals.isApproved(InetAddress.getByName("127.0.0.1"))
        )
    }

    @Test
    fun `虚拟主机豁免只覆盖实际请求主机 重定向到端点主机仍被拒`() {
        // ENG-20261028-22：virtual-host 下获得授权的是 `bucket.端点主机`，端点主机本身并非
        // 实际请求主机；重定向跳回端点主机（或任何其它地址）不得继承豁免
        val endpointHost = "minio.nas.local"
        val requestHost = "vault.minio.nas.local"
        val redirectTarget = MockWebServer().apply {
            start(LOOPBACK, 0)
            enqueue(MockResponse().setBody("must-not-be-reached"))
        }
        val entry = MockWebServer().apply { start(LOOPBACK, 0) }
        try {
            entry.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", "http://$endpointHost:${redirectTarget.port}/")
            )
            val loopbackDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(LOOPBACK)
            }
            val approvals = SsrfAddressApprovals()
            val client = OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .dns(SsrfGuardDns(loopbackDns, setOf(requestHost), approvals))
                .socketFactory(SsrfGuardSocketFactory(approvals))
                .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .build()

            val failure = runCatching {
                client.newCall(Request.Builder().url("http://$requestHost:${entry.port}/").build())
                    .execute().use { it.body.string() }
            }.exceptionOrNull()
            val guarded = generateSequence(failure) { it.cause }
                .any { it.message.orEmpty().contains("SSRF 防护") }

            assertTrue(
                "重定向到端点主机必须被拒（豁免只授实际请求主机 $requestHost），实际=$failure",
                guarded
            )
            assertEquals("对照组：请求必须已抵达获批的实际请求主机", 1, entry.requestCount)
            assertEquals("被拒的重定向目标不得收到任何请求", 0, redirectTarget.requestCount)
        } finally {
            entry.shutdown()
            redirectTarget.shutdown()
        }
    }

    @Test
    fun `工厂客户端同时装配 Dns 层与连接期层`() {
        val client = SyncHttpClientFactory.createSyncClient()

        assertTrue("Dns 层必须是 SsrfGuardDns", client.dns is SsrfGuardDns)
        assertTrue(
            "连接期层必须是 SsrfGuardSocketFactory（ISSUE-P2-208：覆盖 IP 字面量与重定向）",
            client.socketFactory is SsrfGuardSocketFactory
        )
    }

    @Test
    fun `白名单主机经 Dns 层登记的地址在连接期被放行`() {
        // 两层共享同一登记表的端到端证据：Dns 豁免 ⇒ 连接期放行（否则内网自建被纵深防御误伤）
        val server = MockWebServer().apply {
            start(LOOPBACK, 0)
            enqueue(MockResponse().setBody(BODY))
        }
        try {
            val client = guardedPlainClient(allowedHosts = setOf("localhost"))
            val body = client.newCall(Request.Builder().url("http://localhost:${server.port}/").build())
                .execute().use { it.body.string() }

            assertEquals("白名单主机必须端到端可用", BODY, body)
        } finally {
            server.shutdown()
        }
    }

    /** 复刻工厂的安全接线（明文放行以便 MockWebServer 可端到端跑通；`Dns` / socket 层与传输层无关） */
    private fun guardedPlainClient(allowedHosts: Set<String>): OkHttpClient {
        val approvals = SsrfAddressApprovals()
        return OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .dns(SsrfGuardDns(Dns.SYSTEM, allowedHosts, approvals))
            .socketFactory(SsrfGuardSocketFactory(approvals))
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        /** 云元数据端点（AC② 点名的字面量；本层在建连前拒绝，故不会真的发出探测） */
        const val METADATA_IPV4 = "169.254.169.254"

        /** 元数据探测端口（守卫在建连前拒绝，端口只用于构造目标地址） */
        const val METADATA_PORT = 80

        const val BODY = "guarded-ok"
        const val CONNECT_TIMEOUT_MS = 500

        /** 构造 16 字节 IPv4-mapped 地址（`::ffff:a.b.c.d`） */
        fun mappedIpv6(a: Int, b: Int, c: Int, d: Int): ByteArray = ByteArray(16).apply {
            this[10] = 0xFF.toByte()
            this[11] = 0xFF.toByte()
            this[12] = a.toByte()
            this[13] = b.toByte()
            this[14] = c.toByte()
            this[15] = d.toByte()
        }
    }
}
