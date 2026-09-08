package com.keepasskey.app.di

import com.keepasskey.app.data.breach.HibpRangeClient
import okhttp3.ConnectionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * TASK-47：泄露查询客户端传输安全回归锁。
 *
 * 断言生产装配的 OkHttpClient 明确排除 CLEARTEXT——与平台层 `network_security_config.xml`
 * 共同构成「明文绝不外发」的双层防御（同 `SyncHttpClientFactory` 策略）。
 */
class BreachCheckModuleTest {

    @Test
    fun `泄露查询客户端排除明文连接规格`() {
        val client = BreachCheckModule.provideBreachHttpClient()

        assertFalse(
            "泄露查询严禁 CLEARTEXT 连接规格",
            client.connectionSpecs.contains(ConnectionSpec.CLEARTEXT)
        )
        assertEquals(
            listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS),
            client.connectionSpecs
        )
    }

    @Test
    fun `默认基址为 HTTPS 官方端点`() {
        assertEquals(
            "https://api.pwnedpasswords.com",
            HibpRangeClient.DEFAULT_BASE_URL
        )
    }
}
