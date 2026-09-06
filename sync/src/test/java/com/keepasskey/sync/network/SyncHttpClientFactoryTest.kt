package com.keepasskey.sync.network

import okhttp3.ConnectionSpec
import okhttp3.CertificatePinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncHttpClientFactory 传输加固单元测试（Wave 12）：
 * 验证 TLS-only 连接规格（明文整体下线）、显式超时与可选证书锁定装配。
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
    fun `未配置锁定时不挂载证书锁定`() {
        val client = SyncHttpClientFactory.createSyncClient()

        // 官方警示：锁定会限制服务端证书轮换，默认不启用（尊重自建服务器）
        assertTrue(client.certificatePinner.pins.isEmpty())
    }

    @Test
    fun `配置锁定条目时装配 CertificatePinner`() {
        val options = SyncNetworkOptions(
            pinnedHosts = mapOf(
                "dav.example.com" to listOf("sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
            )
        )

        val client = SyncHttpClientFactory.createSyncClient(options)

        assertNotEquals(CertificatePinner.DEFAULT, client.certificatePinner)
        assertEquals(1, client.certificatePinner.pins.size)
        assertEquals("dav.example.com", client.certificatePinner.pins.first().pattern)
    }

    @Test
    fun `多主机多 pin 全量装配`() {
        val options = SyncNetworkOptions(
            pinnedHosts = mapOf(
                "a.example.com" to listOf("sha256/AAAA=", "sha256/BBBB="),
                "b.example.com" to listOf("sha256/CCCC=")
            )
        )

        val client = SyncHttpClientFactory.createSyncClient(options)

        assertEquals(3, client.certificatePinner.pins.size)
    }
}
