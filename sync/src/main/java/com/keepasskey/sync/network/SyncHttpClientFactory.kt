package com.keepasskey.sync.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 同步专用 OkHttpClient 工厂（Wave 12 传输加固，Wave 14 策略收敛）。
 *
 * 安全策略（对齐 OkHttp 官方文档）：
 * 1. **TLS-only**：connectionSpecs 固定 [ConnectionSpec.RESTRICTED_TLS] + [ConnectionSpec.MODERN_TLS]，
 *    显式排除 [ConnectionSpec.CLEARTEXT]——任何经由本工厂构建的客户端都不可能发起 HTTP 明文请求，
 *    与平台 Network Security Config 全局禁明文形成「平台层 + 传输层」双层防御；
 * 2. **显式超时**：连接/读/写默认 10s/30s/30s，防止弱网下同步协程无限悬挂（默认 OkHttpClient 无超时约束）；
 * 3. **系统默认 CA 链为唯一信任源（Wave 14）**：证书固定已整体移除——SPKI 锁定会阻碍云厂商常规
 *    证书轮换导致连接阻断；本应用仅面向正规公网商业云服务，证书验证完全依赖系统默认 CA 链，
 *    不注入任何自定义 TrustManager 或 CertificatePinner。
 */
object SyncHttpClientFactory {

    /** TLS-only 连接规格：显式排除 CLEARTEXT，杜绝 HTTP 明文回退 */
    private val TLS_CONNECTION_SPECS = listOf(
        ConnectionSpec.RESTRICTED_TLS,
        ConnectionSpec.MODERN_TLS
    )

    fun createSyncClient(options: SyncNetworkOptions = SyncNetworkOptions()): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionSpecs(TLS_CONNECTION_SPECS)
            .connectTimeout(options.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(options.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(options.writeTimeoutMs, TimeUnit.MILLISECONDS)
            // TASK-42 整改（P2-12）：全局 callTimeout 覆盖 DNS 解析 + 连接 + 请求体写 + 响应体读
            // 全生命周期——逐段超时无法约束「每段都缓慢重启计时」的悬挂场景，弱网下同步协程
            // 仍可能无限滞留。callTimeout 不含重试（OkHttp 默认重试由重试次数约束），兜底封顶。
            .callTimeout(options.callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }
}
