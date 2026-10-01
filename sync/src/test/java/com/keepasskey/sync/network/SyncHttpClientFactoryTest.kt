package com.keepasskey.sync.network

import okhttp3.ConnectionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncHttpClientFactory 传输安全单元测试（Wave 12 建立，Wave 14 收敛）：
 * 验证 TLS-only 连接规格（明文整体下线）、显式超时，以及证书固定移除后
 * 证书验证完全依赖系统默认 CA 链（无任何 pin 装配）。
 */
class SyncHttpClientFactoryTest {

    @Test
    fun `连接规格恒为 TLS-only 不含明文`() {
        val client = SyncHttpClientFactory.createSyncClient()

        assertTrue(client.connectionSpecs.isNotEmpty())
        // 「允许明文流量」已按安全决策整体下线：任何规格都不允许 CLEARTEXT
        assertTrue(client.connectionSpecs.none { it == ConnectionSpec.CLEARTEXT })
        assertTrue(client.connectionSpecs.all { it.isTls })
    }

    @Test
    fun `默认显式超时生效`() {
        val client = SyncHttpClientFactory.createSyncClient()

        assertEquals(SyncNetworkOptions.DEFAULT_CONNECT_TIMEOUT_MS, client.connectTimeoutMillis.toLong())
        assertEquals(SyncNetworkOptions.DEFAULT_READ_TIMEOUT_MS, client.readTimeoutMillis.toLong())
        assertEquals(SyncNetworkOptions.DEFAULT_WRITE_TIMEOUT_MS, client.writeTimeoutMillis.toLong())
    }

    @Test
    fun `自定义超时可覆盖默认值`() {
        val client = SyncHttpClientFactory.createSyncClient(
            SyncNetworkOptions(connectTimeoutMs = 5_000L, readTimeoutMs = 15_000L, writeTimeoutMs = 20_000L)
        )

        assertEquals(5_000L, client.connectTimeoutMillis.toLong())
        assertEquals(15_000L, client.readTimeoutMillis.toLong())
        assertEquals(20_000L, client.writeTimeoutMillis.toLong())
    }

    @Test
    fun `证书验证依赖系统默认 CA 链 不装配任何证书锁定`() {
        val client = SyncHttpClientFactory.createSyncClient()

        // Wave 14：证书固定已整体移除——SPKI 锁定会阻碍云厂商常规证书轮换导致连接阻断；
        // 客户端不挂载任何 pin，证书验证完全走系统默认 CA 链
        assertTrue(client.certificatePinner.pins.isEmpty())
    }

    // ---------- ISSUE-P2-425：已配置端点主机的连接期豁免接线 ----------

    @Test
    fun `已配置端点主机的解析地址经 Dns 层豁免放行`() {
        val client = SyncHttpClientFactory.createSyncClient(SyncNetworkOptions(), "localhost")

        // 已配置端点主机（主机名形态）不再被 Dns 层拒绝——解析结果即豁免登记（主机名路径；
        // 字面量路径的放行由 OkHttp 短路 + 工厂预登记承担，见下一用例）
        val dns = client.dns as SsrfGuardDns
        assertTrue("已配置端点主机解析必须放行", dns.lookup("localhost").isNotEmpty())

        // 对照组：未配置端点的默认客户端对同一主机仍按网段拒绝（默认收紧语义不变）
        val defaultDns = SyncHttpClientFactory.createSyncClient().dns as SsrfGuardDns
        assertTrue(
            "默认客户端（未配置端点）解析回环必须仍被拒",
            runCatching { defaultDns.lookup("localhost") }.exceptionOrNull() is java.net.UnknownHostException
        )
    }

    @Test
    fun `已配置端点字面量预登记放行 其余内网目标仍被拒`() {
        val client = SyncHttpClientFactory.createSyncClient(SyncNetworkOptions(), "127.0.0.1")
        val factory = client.socketFactory as SsrfGuardSocketFactory

        // 获批字面量（已配置端点本身）：可进 TCP——连本机未监听端口得 ConnectException，
        // 绝非连接期守卫的「SSRF 防护」IOException（OkHttp 对字面量短路不经 Dns，
        // 该路径的放行只能靠工厂预登记）
        val approvedFailure = runCatching {
            factory.createSocket().connect(java.net.InetSocketAddress(LOOPBACK, UNBOUND_PORT), CONNECT_TIMEOUT_MS)
        }.exceptionOrNull()
        assertTrue(
            "已配置端点字面量必须被放行至 TCP（连接失败只能是传输层原因，绝非连接期守卫），实际=$approvedFailure",
            approvedFailure == null || !approvedFailure.message.orEmpty().contains("SSRF 防护")
        )

        // 未获批内网目标（重定向 / 重绑定跳到的其他地址语义）：建连前即拒
        val unapprovedFailure = runCatching {
            factory.createSocket()
                .connect(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.2"), UNBOUND_PORT), CONNECT_TIMEOUT_MS)
        }.exceptionOrNull()
        assertTrue(
            "未获批内网目标必须在建立 TCP 之前被拒，实际=$unapprovedFailure",
            unapprovedFailure is java.io.IOException && unapprovedFailure.message.orEmpty().contains("SSRF 防护")
        )
    }

    @Test
    fun `云元数据字面量即便作为已配置端点也不预登记放行`() {
        val client = SyncHttpClientFactory.createSyncClient(SyncNetworkOptions(), "169.254.169.254")
        val failure = runCatching {
            (client.socketFactory as SsrfGuardSocketFactory).createSocket()
                .connect(java.net.InetSocketAddress(java.net.InetAddress.getByName("169.254.169.254"), 80), CONNECT_TIMEOUT_MS)
        }.exceptionOrNull()
        assertTrue(
            "元数据红线不随已配置端点放行（连接期仍拒），实际=$failure",
            failure is java.io.IOException && failure.message.orEmpty().contains("SSRF 防护")
        )
    }

    private companion object {
        val LOOPBACK: java.net.InetAddress = java.net.InetAddress.getByName("127.0.0.1")

        /** 未监听端口（连之必然 Connection refused，用于证明「到达了 TCP 层」） */
        const val UNBOUND_PORT = 1

        /** 拒守卫用例的建连超时（毫秒）：获批路径即使超时也非「SSRF 防护」异常 */
        const val CONNECT_TIMEOUT_MS = 500
    }
}
