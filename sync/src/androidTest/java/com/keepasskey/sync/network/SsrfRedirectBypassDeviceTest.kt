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
 * **ISSUE-P2-208 设备侧取证**：OkHttp 路由层对「IP 字面量」短路，不经自定义 `[Dns]`。
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
 * ## 判别方式：异常类型 + Dns 调用记录（双证据）
 *
 * - 主机名目标：`SsrfGuardDns.lookup` 被调用 ⇒ 环回被拒 ⇒ `UnknownHostException`；
 * - IP 字面量目标：`lookup` **零调用** ⇒ 请求抵达连接期 ⇒ 自签证书 `TLS` 失败（`SSLException`）。
 *
 * 二者在异常类型上互斥，故断言具备**判别力**（而非仅"请求失败了"）。
 *
 * ## 边界（如实声明）
 *
 * 明文对照用例使用**复刻工厂 `Dns` 接线**的测试客户端——`Dns` 参与的是 OkHttp 的
 * 路由选择，与传输层（`ConnectionSpec`）无关，故复刻客户端对本案结论等价；
 * 真实工厂客户端（TLS-only）对「字面量旁路」的判别见本类第 2 个用例的异常类型对照。
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
    fun `真实工厂客户端对主机名走 SsrfGuardDns、对 IPv4 字面量旁路`() {
        val server = newTlsServer()
        try {
            val client = SyncHttpClientFactory.createSyncClient(SyncNetworkOptions())
            assertTrue(
                "工厂客户端必须装配 SsrfGuardDns（第二层防线接线断言）",
                client.dns is SsrfGuardDns
            )

            val hostnameFailure = execute(client, "https://localhost:${server.port}/")
            val literalFailure = execute(client, "https://127.0.0.1:${server.port}/")

            assertTrue(
                "主机名目标必须被 SsrfGuardDns 拦在 DNS 层（UnknownHostException）；实际=$hostnameFailure",
                causes(hostnameFailure).any { it is UnknownHostException }
            )
            assertFalse(
                "IPv4 字面量不得经 SsrfGuardDns（若经必抛 UnknownHostException，则 ISSUE-P2-208 的旁路不成立）；" +
                    "实际=$literalFailure",
                causes(literalFailure).any { it is UnknownHostException }
            )
            assertTrue(
                "IPv4 字面量应已建立 TCP 并在自签 TLS 阶段失败——这正是「旁路 Dns、抵达连接期」的设备侧证据；" +
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

    /** 复刻工厂的 `Dns` 接线（明文放行以便重定向链路可端到端跑通；`Dns` 与传输层无关） */
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
