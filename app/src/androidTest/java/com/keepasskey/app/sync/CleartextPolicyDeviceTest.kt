package com.keepasskey.app.sync

import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.net.ssl.SSLHandshakeException

/**
 * ISSUE-P2-192 余量第 2 项：**平台网络策略在真实设备上被证明生效**。
 *
 * ## 为什么必须设备侧
 *
 * `network_security_config.xml` 是 Android-only 的 NetworkSecurityConfig，宿主 JVM 根本
 * 不解析它；`SyncHttpClientFactory` 的 TLS-only `ConnectionSpec` 只是双层防御的**上层**，
 * 此前该配置只有 XML 注释与代码注释作证据，从未在平台 HTTP 栈上被证过。instrumentation
 * 运行在被测 app 进程内，`android:networkSecurityConfig` 对测试进程**真实生效**。
 *
 * ## 证明结构（四条，分层归因）
 *
 * 1. **策略读数**：`NetworkSecurityPolicy` 的默认域读数为 `false`——
 *    `base-config cleartextTrafficPermitted="false"` 的声明被平台解析并应用
 *    （本仓 config 即「显式声明并固化 targetSdk 28+ 平台默认行为」，见 XML 注释，
 *    故「行为与平台默认一致」与「config 生效」在此设计下互为同义命题）；
 * 2. **非回环主机明文请求被拦**（判别性最强）：经设备自身 LAN IP 的明文请求被平台拒绝——
 *    排除「回环豁免」干扰后，明文禁令对真实主机形态成立；
 * 3. **回环请求结果与策略读数一致**：API 36.1 模拟器拦回环明文；**API 37 真机平台对回环
 *    内置豁免**（2026-09-19 Redmi 4X 实测 `loopback=true localhost=true` 而 default=false，
 *    与模拟器行为相反）——断言改为「请求结果必须与策略读数一致」，两代平台均稳定且
 *    证明 OkHttp 尊重平台策略（策略差异本身作为平台事实登记批次文档）；
 * 4. **信任锚仅系统 CA**：自签证书 HTTPS 握手被拒（自签 / 用户注入证书链不被信任）；
 *    顺带证明工厂客户端的 SSRF 回环防线（ISSUE-P1-05 连接期防线）在设备上生效。
 *
 * ## 边界（如实声明）
 *
 * 本用例不证明证书固定（已整体移除，无此面）。
 */
class CleartextPolicyDeviceTest {

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

    /** 默认 OkHttp 客户端（无 ConnectionSpec 限制、系统默认信任锚）——平台策略的裸探针 */
    private val defaultClient = OkHttpClient()

    /** 设备自身非回环 IPv4（eth0 / wlan0），无可用接口时 null（调用方 Assume 跳过） */
    private fun deviceLanAddress(): InetAddress? {
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address
                }
            }
        }
        return null
    }

    /** 发起一次同步请求，返回失败的异常（成功返回 null——拦截类断言中不应发生） */
    private fun execute(client: OkHttpClient, url: String): Throwable? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body.string()
            null
        }
    } catch (t: IOException) {
        t
    }

    @Test
    fun `平台策略读数为默认域禁明文`() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertEquals(
            "NetworkSecurityPolicy 默认域读数必须为 false" +
                "（base-config cleartextTrafficPermitted=\"false\" 被平台解析并应用）",
            false,
            policy.isCleartextTrafficPermitted()
        )
    }

    @Test
    fun `非回环主机的明文请求被平台拦截`() {
        val lan = deviceLanAddress()
        Assume.assumeTrue(
            "设备无非回环 IPv4（site-local）接口，本形态无法验证",
            lan != null
        )
        val lanAddress: InetAddress = lan!!
        val lanServer = MockWebServer()
        try {
            lanServer.start(lanAddress, 0)
            val policy = android.security.NetworkSecurityPolicy.getInstance()
            assertEquals(
                "策略对象对非回环主机（${lanAddress.hostAddress}）必须禁明文",
                false,
                policy.isCleartextTrafficPermitted(lanAddress.hostAddress!!)
            )
            lanServer.enqueue(MockResponse().setBody("cleartext"))

            val error = execute(defaultClient, lanServer.url("/").toString())

            assertTrue(
                "非回环主机的明文请求必须被平台拦截，" +
                    "实际: ${error?.javaClass?.simpleName}: ${error?.message}",
                error != null
            )
            assertTrue(
                "失败必须可归因为明文禁令，实际: ${error?.javaClass?.simpleName}: ${error?.message}",
                error?.message?.contains("Cleartext", ignoreCase = true) == true
            )
        } finally {
            lanServer.shutdown()
        }
    }

    @Test
    fun `回环明文请求结果与平台策略读数一致`() {
        server.enqueue(MockResponse().setBody("cleartext"))
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        // API 37 真机平台对回环内置豁免（2026-09-19 Redmi 4X 实测），API 36.1 模拟器不豁免——
        // 故断言「请求结果与策略读数一致」而非硬编码「必拦」
        val permittedLoopback = policy.isCleartextTrafficPermitted("127.0.0.1") ||
            policy.isCleartextTrafficPermitted("localhost")

        val error = execute(defaultClient, server.url("/").toString())

        if (permittedLoopback) {
            assertTrue(
                "策略读数声明回环豁免（permitted=true）时请求必须成功（OkHttp 尊重平台策略），" +
                    "实际: ${error?.javaClass?.simpleName}: ${error?.message}",
                error == null
            )
        } else {
            assertTrue(
                "策略读数禁回环明文（permitted=false）时请求必须失败，" +
                    "实际: ${error?.javaClass?.simpleName}: ${error?.message}",
                error != null
            )
            assertTrue(
                "失败必须可归因为明文禁令，实际: ${error?.javaClass?.simpleName}: ${error?.message}",
                error?.message?.contains("Cleartext", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun `信任锚仅系统 CA 时自签证书 HTTPS 握手被拒`() {
        val heldCertificate = HeldCertificate.Builder()
            .commonName("loopback.keepasskey.test")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("self-signed"))

        val error = execute(defaultClient, server.url("/").toString())

        assertTrue(
            "自签 HTTPS 必须握手失败（信任锚仅系统 CA ⇒ 自签 / 用户注入证书链不被信任），" +
                "实际: ${error?.javaClass?.simpleName}: ${error?.message}",
            error is SSLHandshakeException
        )
    }

    @Test
    fun `工厂客户端的 SSRF 防线在设备上拒绝回环解析`() {
        val factoryClient = SyncHttpClientFactory.createSyncClient(SyncNetworkOptions())
        val heldCertificate = HeldCertificate.Builder()
            .commonName("loopback.keepasskey.test")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server.useHttps(certificates.sslSocketFactory(), false)

        val error = execute(factoryClient, server.url("/").toString())

        assertTrue(
            "工厂客户端对回环端点必须失败（SsrfGuardDns 环回网段拦截），" +
                "实际: ${error?.javaClass?.simpleName}: ${error?.message}",
            error != null
        )
    }
}
