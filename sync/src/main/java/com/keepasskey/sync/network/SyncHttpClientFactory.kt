package com.keepasskey.sync.network

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 同步专用 OkHttpClient 工厂（Wave 12 传输加固）。
 *
 * 安全策略（对齐 OkHttp 官方文档）：
 * 1. **TLS-only**：connectionSpecs 固定 [ConnectionSpec.RESTRICTED_TLS] + [ConnectionSpec.MODERN_TLS]，
 *    显式排除 [ConnectionSpec.CLEARTEXT]——任何经由本工厂构建的客户端都不可能发起 HTTP 明文请求，
 *    杜绝「配置了明文开关但从未生效」与「凭据/密码库明文过网」两类风险；
 * 2. **显式超时**：连接/读/写默认 10s/30s/30s，防止弱网下同步协程无限悬挂（默认 OkHttpClient 无超时约束）；
 * 3. **可选证书锁定**：仅当用户显式配置 [SyncNetworkOptions.pinnedHosts] 时启用 [CertificatePinner]
 *    （锁定对端 SPKI 公钥哈希），防御 CA 层中间人；默认不锁定以尊重自建服务器证书轮换
 *    （官方警示：未经服务端 TLS 管理员同意不得强制锁定）。
 */
object SyncHttpClientFactory {

    /** TLS-only 连接规格：显式排除 CLEARTEXT，杜绝 HTTP 明文回退 */
    private val TLS_CONNECTION_SPECS = listOf(
        ConnectionSpec.RESTRICTED_TLS,
        ConnectionSpec.MODERN_TLS
    )

    fun createSyncClient(options: SyncNetworkOptions = SyncNetworkOptions()): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionSpecs(TLS_CONNECTION_SPECS)
            .connectTimeout(options.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(options.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(options.writeTimeoutMs, TimeUnit.MILLISECONDS)

        if (options.pinnedHosts.isNotEmpty()) {
            val pinnerBuilder = CertificatePinner.Builder()
            options.pinnedHosts.forEach { (host, pins) ->
                pins.forEach { pin -> pinnerBuilder.add(host, pin) }
            }
            builder.certificatePinner(pinnerBuilder.build())
        }
        return builder.build()
    }
}
