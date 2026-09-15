package com.keepasskey.app.passkey

import com.keepasskey.app.di.DalVerifierModule
import com.keepasskey.sync.network.SsrfGuardDns
import okhttp3.ConnectionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DAL 出口**纵深防御**回归（**ISSUE-P3-124**）。
 *
 * ## 为什么单独立一个用例文件
 *
 * `DigitalAssetLinksVerifier` 的**逻辑**用例面向 `MockWebServer`（明文本地回环），必须注入自建
 * 客户端——否则加固客户端会在传输层拒绝它们。若把「生产出口是否加固」混在那套用例里断言，
 * 实际断言的只是**测试替身**，与生产出口无关。故此处直接对**生产提供方法**
 * （[DalVerifierModule.provideDalHttpClient]）求值。
 *
 * ## 覆盖与不覆盖
 *
 * - **覆盖**：DAL 出口客户端恒为 TLS-only（明文规格整体下线）、恒装配 SSRF/DNS 重绑定守卫、
 *   超时沿用 DAL 紧预算、且**拒绝回环目标**（证明内网可达性防护真的生效、不是纸面配置）。
 * - **不覆盖**：`SsrfGuardDns` 自身的判定逻辑（由 `SyncEndpointGuardTest` 覆盖，本批只保证
 *   DAL 出口**确实接上了**它）。
 */
class DigitalAssetLinksEgressHardeningTest {

    private val client = DalVerifierModule.provideDalHttpClient()

    @Test
    fun `DAL 出口恒为 TLS-only 且明文规格整体下线`() {
        assertTrue("连接规格不得为空（空集等于沿用宽松默认）", client.connectionSpecs.isNotEmpty())
        assertTrue(
            "DAL 出口不得允许 CLEARTEXT：目标 host 来自调用方影响面（rp.id / webDomain）",
            client.connectionSpecs.none { it == ConnectionSpec.CLEARTEXT }
        )
        assertTrue(client.connectionSpecs.all { it.isTls })
    }

    @Test
    fun `DAL 出口装配 SSRF 与 DNS 重绑定守卫`() {
        assertTrue(
            "DAL 出口必须装配 SsrfGuardDns（ISSUE-P3-124 ①：对照 SyncEndpointGuard）",
            client.dns is SsrfGuardDns
        )
    }

    @Test
    fun `DAL 出口拒绝回环目标`() {
        val thrown = runCatching { client.dns.lookup("localhost") }.exceptionOrNull()

        assertNotNull(
            "回环目标必须被拒绝——否则「已装配守卫」只是纸面配置",
            thrown
        )
    }

    @Test
    fun `DAL 出口沿用紧超时预算`() {
        assertEquals(
            DigitalAssetLinksVerifier.CONNECT_TIMEOUT_MS,
            client.connectTimeoutMillis.toLong()
        )
        assertEquals(
            DigitalAssetLinksVerifier.READ_TIMEOUT_MS,
            client.readTimeoutMillis.toLong()
        )
        assertEquals(
            DigitalAssetLinksVerifier.CALL_TIMEOUT_MS,
            client.callTimeoutMillis.toLong()
        )
    }

    @Test
    fun `校验器不得在运行期把出口改回裸客户端`() {
        // 接线守卫：出口只能来自 DI 注入（@DalHttpClient），校验器内部不得再自建 OkHttpClient
        val source = File(repositoryRoot, VERIFIER).readText()

        assertTrue(
            "必须经 @DalHttpClient 注入出口客户端",
            source.contains("@DalHttpClient")
        )
        assertTrue(
            "不得残留自建客户端的写法（ISSUE-P3-124 整改前的形态）",
            !source.contains("OkHttpClient.Builder()")
        )
    }

    private companion object {
        const val VERIFIER = "app/src/main/java/com/keepasskey/app/passkey/DigitalAssetLinksVerifier.kt"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
