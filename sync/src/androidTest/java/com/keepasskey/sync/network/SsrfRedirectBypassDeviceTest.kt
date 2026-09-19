package com.keepasskey.sync.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import javax.net.ssl.SSLException

/**
 * **ISSUE-P2-208 设备侧取证**：OkHttp 路由层对「IP 字面量」短路，不经自定义 `[Dns]`；
 * 以及连接期复核（[SsrfGuardSocketFactory]）落地后的现状。
 *
 * ## 为什么必须设备侧
 *
 * 本案的结论此前只有**上游源码级**证据（`RouteSelector.nextRoutes` 的
 * `if (socketHost.canParseAsIpAddress()) return ...` 早于 `dnsLookup`）。本用例把该结论
 * 钉在**真实设备 + 真实解析产物**上，并顺带裁决两处长期悬置的事实前提：
 *
 * 1. `MockWebServer.url("/")` 的 host 究竟是**主机名 `localhost`** 还是 **IP 字面量**
 *    —— 它决定 `CleartextPolicyDeviceTest` 的 SSRF 用例是「有效」还是「假阳性归因」；
 * 2. 「302 重定向到 IPv4 字面量」是否真的绕过 `[SsrfGuardDns]`，以及**控制组**
 *    （重定向到主机名）是否确实被拦——只有同时观察两侧，「旁路」才不是自说自话。
 *
 * ## 整改前后的判别面（ISSUE-P2-208 修复取证，2026-09-19 第三轮起）
 *
 * | 目标 | Dns 层（含自定义 Dns 的客户端） | 生产工厂客户端（TLS-only + 连接期复核） |
 * |---|---|---|
 * | 主机名（`localhost`） | `UnknownHostException`（Dns 拦） | 同左（Dns 拦得更早） |
 * | IPv4 字面量 | **旁路** ⇒ 抵达 TCP ⇒ `SSLException` | `IOException("SSRF 防护…")`，**SYN 之前**拒绝 |
 *
 * 故第 3 个用例（复刻 **Dns-only** 接线的明文客户端）依旧成立并作为「为何需要连接期层」的
 * 设备侧证据；第 2 个用例断言的是**生产工厂客户端对字面量的现状**（整改后已为连接期拒绝）。
 *
 * ## 边界（如实声明）
 *
 * 明文对照用例使用复刻 `Dns` 接线的测试客户端——`Dns` 参与的是 OkHttp 路由选择，
 * 与传输层（`ConnectionSpec`）无关，故复刻客户端对「Dns-only 旁路」的结论等价。
 */
@RunWith(AndroidJUnit4::class)
class SsrfRedirectBypassDeviceTest {

    @Test
    fun `MockWebServer 默认 url 的主机形态为主机名而非 IP 字面量`() {
        val server = MockWebServer()
        server.start()
        try {
            val host = server.url("/").host
            assertEquals(
                "MockWebServer.url() 的 host 必须是**主机名**：其 hostName = socketAddress.address.hostName，" +
                    "而 start() 以 InetAddress.getByName(\"localhost\") 绑定（主机名被记住、不做反向解析）。" +
                    "若此处为 IP 字面量，则 CleartextPolicyDeviceTest 的 SSRF 用例即为假阳性归因。实际=$host",
                "localhost",
                host
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `真实工厂客户端对主机名走 SsrfGuardDns、对 IPv4 字面量在连接期即拒`() {
        val server = newTlsServer()
        try {
            val client = SyncHttpClientFactory.createSyncClient(SyncNetworkOptions())
            assertTrue(
                "工厂客户端必须装配 SsrfGuardDns（Dns 层防线接线断言）",
                client.dns is SsrfGuardDns
            )
            assertTrue(
                "工厂客户端必须装配 SsrfGuardSocketFactory（ISSUE-P2-208 的连接期层接线断言）",
                client.socketFactory is SsrfGuardSocketFactory
            )

            val hostnameFailure = execute(client, "https://localhost:${server.port}/")
            val literalFailure = execute(client, "https://127.0.0.1:${server.port}/")

            assertTrue(
                "主机名目标必须被 SsrfGuardDns 拦在 DNS 层（UnknownHostException）；实际=$hostnameFailure",
                causes(hostnameFailure).any { it is UnknownHostException }
            )
            assertFalse(
                "IPv4 字面量不得经 SsrfGuardDns（若经必抛 UnknownHostException——它确实不走 Dns）；" +
                    "实际=$literalFailure",
                causes(literalFailure).any { it is UnknownHostException }
            )
            assertTrue(
                "IPv4 字面量必须在**建立 TCP 之前**被连接期复核拒掉（ISSUE-P2-208 整改后的现状）：" +
                    "异常须带守卫标识；实际=$literalFailure",
                causes(literalFailure).any {
                    it is IOException && it.message.orEmpty().contains("SSRF 防护")
                }
            )
            assertFalse(
                "整改后字面量不再抵达自签 TLS 阶段（若仍抛 SSLException 说明连接期层未生效）；" +
                    "实际=$literalFailure",
                causes(literalFailure).any { it is SSLException }
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `302 重定向到 IPv4 字面量触发连接、到主机名被 SsrfGuardDns 拦截`() {
        val target = MockWebServer().apply {
            start(LOOPBACK, 0)
            enqueue(MockResponse().setBody(REDIRECT_REACHED))
        }
        val entry = MockWebServer().apply { start(LOOPBACK, 0) }
        try {
            // ① 初跳与重定向目标皆为 IPv4 字面量：若 OkHttp 对字面量短路，则 Dns 零调用且请求抵达 target
            // （本客户端**不含**连接期层——那正是要证明「仅靠 Dns 够不着」这一半的隔离面）
            entry.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", "http://127.0.0.1:${target.port}/")
            )
            val literalDns = RecordingDns()
            val literalBody = plainClient(literalDns)
                .newCall(Request.Builder().url("http://127.0.0.1:${entry.port}/").build())
                .execute().use { it.body.string() }

            assertEquals(
                "IPv4 字面量重定向目标必须被真实连接并取回响应体（若经 SsrfGuardDns 会被拦，取不到）",
                REDIRECT_REACHED,
                literalBody
            )
            assertTrue(
                "IPv4 字面量跳转**不得**触发自定义 Dns.lookup（ISSUE-P2-208 旁路的设备侧决定性证据）；" +
                    "实际 lookup=${literalDns.looked}",
                literalDns.looked.isEmpty()
            )

            // ② 控制组：重定向目标为主机名 ⇒ 必经 Dns ⇒ 环回被拒（证明①的有效性，排除「重定向整体未发生」）
            entry.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", "http://localhost:${target.port}/")
            )
            val hostnameDns = RecordingDns()
            val failure = execute(plainClient(hostnameDns), "http://127.0.0.1:${entry.port}/")

            assertTrue(
                "主机名重定向目标必须被 SsrfGuardDns 拦（UnknownHostException）；实际=$failure",
                causes(failure).any { it is UnknownHostException }
            )
            assertTrue(
                "主机名跳转必须真实调用自定义 Dns（对照组有效性证据）；实际 lookup=${hostnameDns.looked}",
                hostnameDns.looked.contains("localhost")
            )
        } finally {
            entry.shutdown()
            target.shutdown()
        }
    }

    /** 记录 `lookup` 入参并委托给真实 `[SsrfGuardDns]`（与工厂装配同构） */
    private class RecordingDns(
        private val delegate: Dns = SsrfGuardDns(Dns.SYSTEM, emptySet())
    ) : Dns {

        val looked: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        override fun lookup(hostname: String): List<InetAddress> {
            looked.add(hostname)
            return delegate.lookup(hostname)
        }
    }

    /**
     * **刻意只复刻工厂的 `Dns` 一层**（明文放行以便重定向链路可端到端跑通；`Dns` 与传输层无关）。
     *
     * 这样做的目的正是「隔离 Dns 层的可达面」：客户端的字面量旁路必须原样复现，
     * 才能证明 ISSUE-P2-208 的结论（Dns 层对 IP 字面量无能为力）与连接期层的**必要性**。
     * 生产工厂客户端的现状由本类第 2 个用例断言。
     */
    private fun plainClient(dns: Dns): OkHttpClient =
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .dns(dns)
            .build()

    /** 自签 HTTPS 服务（系统 CA 必不信任，故客户端只可能失败于 TLS 或更早的 DNS 层） */
    private fun newTlsServer(): MockWebServer {
        val server = MockWebServer()
        server.start(LOOPBACK, 0)
        val heldCertificate = HeldCertificate.Builder()
            .commonName("loopback.keepasskey.test")
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("localhost")
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("tls"))
        server.enqueue(MockResponse().setBody("tls"))
        return server
    }

    private fun execute(client: OkHttpClient, url: String): Throwable? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        null
    } catch (t: IOException) {
        t
    }

    /** 展开异常因果链（OkHttp 会把连接期失败包一层后再抛） */
    private fun causes(t: Throwable?): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current = t
        while (current != null && !chain.contains(current)) {
            chain.add(current)
            current = current.cause
        }
        return chain
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val REDIRECT_REACHED = "redirect-reached"
    }
}
