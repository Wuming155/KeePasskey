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
}
