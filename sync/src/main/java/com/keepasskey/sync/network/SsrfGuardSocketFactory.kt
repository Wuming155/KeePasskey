package com.keepasskey.sync.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * ISSUE-P2-208：**连接期目标地址**复核（SSRF 防线的第三层，覆盖重定向与 IP 字面量）。
 *
 * ## 为什么 `[SsrfGuardDns]` 不够
 * OkHttp 的路由层对 **IP 字面量短路**：`RouteSelector.nextRoutes` 先判
 * `socketHost.canParseAsIpAddress()` 并直接 `InetAddress.getByName(...)` 返回路由，
 * **其后**才是 `dnsLookup`（本机 `okhttp-android-5.5.0-sources.jar` 的
 * `internal/connection/RouteSelector.kt:174-184`，设备侧实证见 `SsrfRedirectBypassDeviceTest`）。
 * 于是一个 `302 → https://<内网 IPv4>/` 的重定向目标**完全不经自定义 `Dns`**：
 * 构造期校验（只管用户配置的端点）与 Dns 层防线全部落空，只剩 NSC 证书链约束。
 *
 * ## 本层口径
 * 拦在 **SYN 之前**：OkHttp 建连时经 `Address.socketFactory.createSocket()` 取裸 socket，
 * 再 `Platform.connectSocket(rawSocket, route.socketAddress, …)`（即 `socket.connect(…)`），
 * 故覆写 `connect` 即可在建立 TCP 之前拿到**最终目标地址**并复核
 * （裸 `SocketFactory` 取不到地址——OkHttp 调的是无参 `createSocket()`）。
 *
 * 与 Dns 层**互补而非重复**：Dns 层管「主机名解析结果」（含 DNS 重绑定），本层管
 * 「实际要连的地址」，对 IP 字面量与全部重定向跳数一律生效。
 *
 * ## 白名单豁免必须延续（[SsrfAddressApprovals]）
 * 显式白名单（[SyncNetworkOptions.ssrfAllowedHosts]，如自建 NAS / 内网 WebDAV）在 Dns 层被
 * 豁免，其解析出的内网地址**在连接期同样必须放行**——否则纵深防御会退化为可用性回归。
 * 故由 [SsrfGuardDns] 在豁免时把解析结果登记进 [SsrfAddressApprovals]，本层据此放行；
 * 未登记的地址（含一切 IP 字面量重定向目标）一律按 [SyncEndpointGuard.isBlockedAddress] 复核。
 */
class SsrfGuardSocketFactory(
    private val approvals: SsrfAddressApprovals = SsrfAddressApprovals()
) : SocketFactory() {

    /** OkHttp DIRECT / HTTP 代理路径：取**未连接** socket，由本类覆写的 `connect` 把关 */
    override fun createSocket(): Socket = SsrfGuardedSocket(approvals)

    override fun createSocket(host: String, port: Int): Socket =
        SsrfGuardedSocket(approvals).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = SsrfGuardedSocket(approvals).apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        SsrfGuardedSocket(approvals).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = SsrfGuardedSocket(approvals).apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}

/** 连接期目标地址复核（见 [SsrfGuardSocketFactory] 的类 KDoc） */
private class SsrfGuardedSocket(
    private val approvals: SsrfAddressApprovals
) : Socket() {

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        assertTargetAllowed(endpoint)
        super.connect(endpoint, timeout)
    }

    override fun connect(endpoint: SocketAddress?) {
        assertTargetAllowed(endpoint)
        super.connect(endpoint)
    }

    private fun assertTargetAllowed(endpoint: SocketAddress?) {
        // 未解析的地址（`InetSocketAddress` 的 hostname 形式解析失败）没有可复核的目标，
        // 交由底层 `connect` 抛 `UnknownHostException`——不在此处伪造结论
        val target = (endpoint as? InetSocketAddress)?.address ?: return
        if (approvals.isApproved(target)) return
        if (SyncEndpointGuard.isBlockedAddress(target)) {
            throw IOException(
                "SSRF 防护：连接目标 \"${target.hostAddress}\" 属内网/保留网段，已在建立 TCP 之前拒绝" +
                    "（本层覆盖 IP 字面量与重定向跳转）"
            )
        }
    }
}

/**
 * 显式白名单豁免地址的**登记表**（进程 / 客户端粒度）。
 *
 * 由 [SsrfGuardDns] 在「主机命中白名单」时登记其解析结果，由 [SsrfGuardSocketFactory]
 * 在连接期查询。两处共享同一实例（在 [SyncHttpClientFactory.createSyncClient] 内装配），
 * 故「白名单豁免」这一语义在两层间**逐地址**一致：登记过的地址放行，其余一律复核。
 *
 * 线程安全：`Dns.lookup` 与建连可能并发，故用并发集合。
 */
class SsrfAddressApprovals {

    private val approvedAddresses = ConcurrentHashMap.newKeySet<String>()

    /** 登记一批「已被显式白名单豁免」的解析结果 */
    fun approveAll(addresses: List<InetAddress>) {
        addresses.forEach { approvedAddresses.add(key(it)) }
    }

    /** 该地址是否已被显式白名单豁免 */
    fun isApproved(address: InetAddress): Boolean = approvedAddresses.contains(key(address))

    /**
     * 地址键：`hostAddress` 的规范化文本。
     *
     * 用文本而非 `InetAddress` 本身作键——`InetAddress` 的 `equals` 按二进制地址比较，
     * 但不同 `InetAddress` 子类实例（如 IPv4-mapped 形态）在同一目标上可能不互等；
     * 文本形态对同一地址稳定（`hostAddress` 不做反向解析）。
     */
    private fun key(address: InetAddress): String = address.hostAddress.lowercase(Locale.US)
}
