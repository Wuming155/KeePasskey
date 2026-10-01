package com.keepasskey.sync.network

import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.Locale
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
 *    证书轮换导致连接阻断；证书验证完全依赖系统默认 CA 链，
 *    不注入任何自定义 TrustManager 或 CertificatePinner。
 * 4. **SSRF 防护（ISSUE-P1-05 / ZT-05，ISSUE-P2-208 补第二道连接期防线）**：
 *    - [SsrfGuardDns] 拦截**主机名解析结果**（内网/保留网段即整体拒绝，含环回、链路本地
 *      169.254 云元数据、RFC1918、ULA 等），同时抵御 DNS 重绑定；
 *    - [SsrfGuardSocketFactory] 在建立 TCP 之前复核**实际目标地址**，兜住 Dns 层够不着的
 *      **IP 字面量**（OkHttp 路由层对其短路）与**重定向跳转**（`302 → 内网 IP`）。
 *    仅生产客户端经本工厂构建时生效；测试/本地联调经注入自定义客户端旁路。
 * 5. **已配置端点豁免（ISSUE-P2-425）**：构造期口径放宽为「加密即可、明文全拒」后，用户
 *    显式配置的自建 / 内网 HTTPS 端点须在连接期同样可达，否则放宽只是纸面的——
 *    [configuredEndpointHost] 即为此而设：主机名端点登记进 Dns 豁免（其解析地址经
 *    [SsrfAddressApprovals] 获批），IP 字面量端点直接预登记（OkHttp 对字面量短路、
 *    不经自定义 Dns）。**云元数据网段（169.254.0.0/16）两种路径都不放行**；重定向 /
 *    DNS 重绑定跳到的**其他**地址不在豁免之列，仍按网段复核拦截。
 */
object SyncHttpClientFactory {

    /** TLS-only 连接规格：显式排除 CLEARTEXT，杜绝 HTTP 明文回退 */
    private val TLS_CONNECTION_SPECS = listOf(
        ConnectionSpec.RESTRICTED_TLS,
        ConnectionSpec.MODERN_TLS
    )

    /**
     * 构建同步专用客户端。
     *
     * @param options 传输安全与超时配置
     * @param configuredEndpointHost 用户显式配置的端点主机（ISSUE-P2-425；由 Provider 经
     *   [SyncEndpointGuard.validateEndpointHost] 的返回值传入，可为 null = 未配置/不豁免）。
     *   主机名端点：登记进 Dns 豁免，解析地址经豁免分支获批（云元数据除外）；
     *   IP 字面量端点：直接预登记进 [SsrfAddressApprovals]（OkHttp 路由层对字面量短路，
     *   Dns 层豁免对字面量天然不生效）。仅此一台主机豁免——重定向 / 重绑定跳到的其他地址
     *   仍被连接期复核拦截。
     */
    fun createSyncClient(
        options: SyncNetworkOptions = SyncNetworkOptions(),
        configuredEndpointHost: String? = null
    ): OkHttpClient {
        // ISSUE-P2-208：Dns 层与连接期层共享同一份「显式白名单豁免地址」登记表——
        // 内网自建（白名单）解析出的地址在连接期同样放行，其余目标一律按网段复核。
        val approvals = SsrfAddressApprovals()
        // ISSUE-P2-425：已配置端点主机豁免（规范化：小写、去尾点）
        val endpointHost = configuredEndpointHost
            ?.trim()
            ?.lowercase(Locale.US)
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() }
        // IP 字面量端点：Dns 层豁免够不着（OkHttp 短路），在工厂处预登记放行；
        // 云元数据红线不预登记（169.254.0.0/16 任何路径都不放行）
        val endpointLiteral = endpointHost?.let { SyncEndpointGuard.parseIpLiteral(it) }
        if (endpointLiteral != null && !SyncEndpointGuard.isCloudMetadataAddress(endpointLiteral)) {
            approvals.approveAll(listOf(endpointLiteral))
        }
        return OkHttpClient.Builder()
            .connectionSpecs(TLS_CONNECTION_SPECS)
            // ISSUE-P1-05（ZT-05）：连接期 SSRF 防护——解析到内网/保留网段即拒绝，抵御 DNS 重绑定
            .dns(SsrfGuardDns(Dns.SYSTEM, options.ssrfAllowedHosts + setOfOrEmpty(endpointHost), approvals))
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

    /** null 安全的单元素集合（[endpointHost] 为 null 时返回空集，避免 `setOf(null)` 分支噪音） */
    private fun setOfOrEmpty(endpointHost: String?): Set<String> =
        endpointHost?.let { setOf(it) } ?: emptySet()
}
