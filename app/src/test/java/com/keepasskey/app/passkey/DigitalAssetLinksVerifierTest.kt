package com.keepasskey.app.passkey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P2-02：DAL 远程资产声明校验单元测试。
 * 覆盖：声明匹配核心（relation/namespace/包名/证书指纹）、极简 JSON 解析容错、
 * 远程拉取（MockWebServer）、缓存 TTL（正/负）与 fail-closed 语义。
 */
class DigitalAssetLinksVerifierTest {

    private lateinit var server: MockWebServer
    private lateinit var verifier: DigitalAssetLinksVerifier

    /** 可推进的 fake 时钟（毫秒） */
    private var nowMs = 1_000_000L

    private val pkg = "com.example.app"
    private val fpNoColon = "32A2FC74D731105859E5A85DF16D95F102D85B22099B8064C6D6BABB6652849F"
    private val fpColon = "32:a2:fc:74:d7:31:10:58:59:e5:a8:5d:f1:6d:95:f1:02:d8:5b:22:09:9b:80:64:c6:d6:ba:bb:66:52:84:9f"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        verifier = DigitalAssetLinksVerifier()
        verifier.clockMs = { nowMs }
        verifier.endpointOverride = { host -> server.url("/.well-known/assetlinks.json").toString() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun dalJson(
        packageName: String = pkg,
        fingerprint: String = fpColon,
        relation: String = DalStatementMatcher.RELATION_GET_LOGIN_CREDS,
        namespace: String = "android_app"
    ): String = """
        [
          {
            "relation": ["delegate_permission/common.handle_all_urls", "$relation"],
            "target": {
              "namespace": "$namespace",
              "package_name": "$packageName",
              "sha256_cert_fingerprints": ["$fingerprint"]
            }
          }
        ]
    """.trimIndent()

    private suspend fun verify(
        rpId: String = "example.com",
        packageName: String = pkg,
        fingerprint: String = fpNoColon
    ): DigitalAssetLinksVerifier.DalResult = withContext(Dispatchers.IO) {
        verifier.verify(rpId, packageName, fingerprint)
    }

    // ===== 远程拉取与声明匹配 =====

    @Test
    fun `远程声明_包名与指纹匹配_校验通过`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson()))
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())

        val recorded = server.takeRequest()
        assertEquals("/.well-known/assetlinks.json", recorded.path)
    }

    @Test
    fun `远程声明_冒号指纹与无冒号指纹跨格式匹配`() = runBlocking {
        // 证书侧无冒号大写，声明侧冒号小写 → 归一后命中
        server.enqueue(MockResponse().setBody(dalJson(fingerprint = fpColon)))
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify(fingerprint = fpNoColon.lowercase()))
    }

    @Test
    fun `远程声明_包名不匹配_拒绝`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson(packageName = "com.evil.app")))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
    }

    @Test
    fun `远程声明_指纹不匹配_拒绝`() = runBlocking {
        val other = fpNoColon.substring(0, fpNoColon.length - 2) + "AA"
        server.enqueue(MockResponse().setBody(dalJson(fingerprint = other)))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
    }

    @Test
    fun `远程声明_relation不符_拒绝`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson(relation = "delegate_permission/common.handle_all_urls")))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
    }

    @Test
    fun `远程声明_namespace非android_app_拒绝`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson(namespace = "web")))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
    }

    @Test
    fun `远程声明_DAL格式错误_拒绝而非网络故障`() = runBlocking {
        server.enqueue(MockResponse().setBody("not a json {"))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
    }

    @Test
    fun `远程声明_HTTP404视为无声明_拒绝`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
    }

    @Test
    fun `远程声明_网络不可用_返回NETWORK_UNAVAILABLE`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NETWORK_UNAVAILABLE, verify())
    }

    @Test
    fun `非法入参_直接拒绝且不发起网络请求`() = runBlocking {
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify(rpId = ""))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify(packageName = " "))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify(fingerprint = " "))
        assertEquals(0, server.requestCount)
    }

    // ===== 缓存与 TTL =====

    @Test
    fun `缓存_命中期内不重复发起网络请求`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson()))
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())
        // 同 key 缓存命中：不新增网络请求
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())
        assertEquals(1, server.requestCount)

        // 不同包名（不同 key）：重新发起请求
        server.enqueue(MockResponse().setBody(dalJson(packageName = "com.evil.app")))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify(packageName = "com.other.app"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `缓存_正向TTL过期后重新拉取`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson()))
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())

        // 未过期：不重新拉取
        nowMs += DigitalAssetLinksVerifier.POSITIVE_TTL_MS - 1
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())
        assertEquals(1, server.requestCount)

        // 过期：重新拉取
        nowMs += 1
        server.enqueue(MockResponse().setBody(dalJson(packageName = "com.evil.app")))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `缓存_负向TTL短于正向_过期后重新拉取`() = runBlocking {
        server.enqueue(MockResponse().setBody(dalJson(packageName = "com.evil.app")))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())

        // 负向缓存期内不重复请求
        nowMs += DigitalAssetLinksVerifier.NEGATIVE_TTL_MS - 1
        assertEquals(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED, verify())
        assertEquals(1, server.requestCount)

        // 负向过期：重新拉取并成功（站点补发声明后可及时收敛）
        nowMs += 1
        server.enqueue(MockResponse().setBody(dalJson()))
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `缓存_网络故障负向缓存过期后可恢复`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(DigitalAssetLinksVerifier.DalResult.NETWORK_UNAVAILABLE, verify())

        nowMs += DigitalAssetLinksVerifier.NEGATIVE_TTL_MS + 1
        server.enqueue(MockResponse().setBody(dalJson()))
        assertEquals(DigitalAssetLinksVerifier.DalResult.VERIFIED, verify())
        assertEquals(2, server.requestCount)
    }

    // ===== 指纹归一与 JSON 解析边界（纯函数） =====

    @Test
    fun `指纹归一_冒号空白与大小写全兼容`() {
        assertEquals(fpNoColon, DigitalAssetLinksVerifier.normalizeFingerprint(fpColon))
        assertEquals(fpNoColon, DigitalAssetLinksVerifier.normalizeFingerprint(fpNoColon.lowercase()))
        assertEquals(fpNoColon, DigitalAssetLinksVerifier.normalizeFingerprint("  $fpNoColon "))
        assertEquals("", DigitalAssetLinksVerifier.normalizeFingerprint("::"))
    }

    @Test
    fun `匹配核心_多声明文档与结构容错`() {
        val multi = """
            [
              {"relation": ["delegate_permission/common.handle_all_urls"],
               "target": {"namespace": "android_app", "package_name": "com.other.app",
                          "sha256_cert_fingerprints": ["$fpColon"]}},
              {"relation": ["${DalStatementMatcher.RELATION_GET_LOGIN_CREDS}"],
               "target": {"namespace": "android_app", "package_name": "$pkg",
                          "sha256_cert_fingerprints": ["$fpColon", "AA:BB"]}}
            ]
        """.trimIndent()
        assertEquals(
            DigitalAssetLinksVerifier.DalResult.VERIFIED,
            DalStatementMatcher.match(multi, pkg, fpNoColon)
        )
        // 非数组根 / 空数组 / 非法 JSON → 一律 NOT_VERIFIED
        assertEquals(
            DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED,
            DalStatementMatcher.match("{\"a\":1}", pkg, fpNoColon)
        )
        assertEquals(
            DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED,
            DalStatementMatcher.match("[]", pkg, fpNoColon)
        )
        assertEquals(
            DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED,
            DalStatementMatcher.match("[", pkg, fpNoColon)
        )
    }

    @Test
    fun `JSON解析_基础结构完整支持`() {
        val parsed = MinimalJson.parse(
            """{"s":"a\"b\\c\n日","n":-1.5e2,"b":true,"z":null,"arr":[1,"x",{"k":false}]}"""
        ) as? Map<*, *>
        assertTrue(parsed != null)
        assertEquals("""a"b\c
日""".trimIndent(), parsed?.get("s"))
        assertEquals(-150.0, parsed?.get("n"))
        assertEquals(true, parsed?.get("b"))
        assertEquals(null, parsed?.get("z"))
        val arr = parsed?.get("arr") as? List<*>
        assertEquals(listOf(1L, "x", mapOf("k" to false)), arr)
        // 非法输入一律 null
        assertFalse(listOf("", "  ", "{", "\"unterminated", "tru", "[1,]", "{\"a\"}").any { MinimalJson.parse(it) != null })
    }
}
