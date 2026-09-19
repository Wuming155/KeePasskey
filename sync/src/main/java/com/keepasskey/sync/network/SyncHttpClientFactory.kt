package com.keepasskey.sync.network

import okhttp3.ConnectionSpec
import okhttp3.Dns
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
 * 4. **SSRF 防护（ISSUE-P1-05 / ZT-05，ISSUE-P2-208 补第二道连接期防线）**：
 *    - [SsrfGuardDns] 拦截**主机名解析结果**（内网/保留网段即整体拒绝，含环回、链路本地
 *      169.254 云元数据、RFC1918、ULA 等），同时抵御 DNS 重绑定；
 *    - [SsrfGuardSocketFactory] 在建立 TCP 之前复核**实际目标地址**，兜住 Dns 层够不着的
 *      **IP 字面量**（OkHttp 路由层对其短路）与**重定向跳转**（`302 → 内网 IP`）。
 *    仅生产客户端经本工厂构建时生效；测试/本地联调经注入自定义客户端旁路。
 */
object SyncHttpClientFactory {

    /** TLS-only 连接规格：显式排除 CLEARTEXT，杜绝 HTTP 明文回退 */
    private val TLS_CONNECTION_SPECS = listOf(
        ConnectionSpec.RESTRICTED_TLS,
        ConnectionSpec.MODERN_TLS
    )

    fun createSyncClient(options: SyncNetworkOptions = SyncNetworkOptions()): OkHttpClient {
        // ISSUE-P2-208：Dns 层与连接期层共享同一份「显式白名单豁免地址」登记表——
        // 内网自建（白名单）解析出的地址在连接期同样放行，其余目标一律按网段复核。
        val approvals = SsrfAddressApprovals()
        return OkHttpClient.Builder()
            .connectionSpecs(TLS_CONNECTION_SPECS)
            // ISSUE-P1-05（ZT-05）：连接期 SSRF 防护——解析到内网/保留网段即拒绝，抵御 DNS 重绑定
            .dns(SsrfGuardDns(Dns.SYSTEM, options.ssrfAllowedHosts, approvals))
            // ISSUE-P2-208：连接期目标地址复核——覆盖 IP 字面量与重定向跳转（Dns 层管不到）
            .socketFactory(SsrfGuardSocketFactory(approvals))
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
