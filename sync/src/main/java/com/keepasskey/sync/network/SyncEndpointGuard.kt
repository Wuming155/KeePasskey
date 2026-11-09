package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * ISSUE-P1-05（ZT-05）同步端点 SSRF 与主机注入统一防线；
 * ISSUE-P2-425：构造期口径按产品裁决放宽为「**加密即可、明文全拒**」。
 *
 * 产品口径（PD-02，2026-10-01 定版）：同步端点**仅接受 HTTPS**（明文 HTTP 一律拒绝）；
 * 用户**显式配置**的自建 / 内网 HTTPS 端点（RFC1918、ULA、链路本地、`localhost`、`.local`、
 * `.internal`）**默认可用**——自托管 NAS / MinIO / Nextcloud 是合法加密同步目标，不再与
 * 恶意内网重定向同等待遇。SSRF 防御的重心随之收敛到**连接期**：拦截「非用户意图」的内网到达。
 *
 * **云元数据红线优先于一切豁免**：`169.254.0.0/16`（含其 IPv4-mapped / IPv4-compatible 拼写）
 * 在构造期、豁免 DNS 分支与连接期复核中都是**先判**的那一条，白名单与已配置端点都不能使其放行。
 *
 * 残余攻击面与各层职责（均 fail-closed）：
 * 1. **构造期（纯字符串/字面 IP 校验，零网络）**：桶名严格按 S3 命名正则校验杜绝 authority
 *    注入（S3 virtual-hosted 分支 `scheme://bucket.host/key`，注入 `x@evil.com/`、`x#`、`x?`
 *    即可改写真实目标主机并投递对其有效的 SigV4 签名）；端点主机拒绝 userinfo 注入与
 *    明文 scheme；**云元数据红线**——`169.254.0.0/16` 字面量即便同时命中显式白名单也仍拒绝
 *    （非合法同步目标，仅攻击面）；
 * 2. **连接期（DNS 解析后校验）**：经 [SsrfGuardDns] 拦截主机名解析结果，任一解析地址落入
 *   内网/保留网段即整体拒绝——同时抵御 DNS 重绑定（校验用的解析结果即喂给实际连接）；
 *   用户显式配置的**实际请求主机**经 [SyncHttpClientFactory] 登记为豁免（其解析地址获批放行；
 *   S3 virtual-host 风格的实际请求主机是 `bucket.端点主机`，由 `S3KeyCodec.requestHost` 推导），
 *   但豁免**不延伸到云元数据网段**；
 * 3. **连接期（目标地址复核）**：经 [SsrfGuardSocketFactory] 在建立 TCP 之前复核**实际目标地址**，
 *   覆盖 Dns 层天然够不着的两条路径——**IP 字面量**（OkHttp 路由层对其短路，不经自定义 Dns；
 *   已配置端点的字面量由工厂预登记放行）与**重定向跳转**（ISSUE-P2-208：`302 → https://<内网 IP>/`
 *   曾只剩 TLS 证书链一道约束）。
 *
 * 显式白名单豁免：[allowedHosts] 提供可审计的高级逃生通道（默认空，语义见
 * [SyncNetworkOptions.ssrfAllowedHosts]）；测试/本地联调另经 Provider 注入自定义
 * OkHttpClient 的生产旁路豁免（与既有强制 HTTPS 校验的 `client == null` 门控一致）。
 */
object SyncEndpointGuard {

    /**
     * AWS S3 通用桶命名规则（3–63 字符；仅小写字母/数字/点/连字符；首尾为字母或数字）。
     * 该字符集天然排除 `@ / # ? :` 等 authority 分隔符，是杜绝主机注入的第一道闸。
     */
    private val S3_BUCKET_REGEX = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")

    /** 相邻点号（AWS 明令禁止，且 virtual-host TLS 通配证书不覆盖多级点） */
    private val ADJACENT_DOTS = Regex("\\.\\.")

    /** IPv4 字面量粗匹配（仅用于「是否为 IP 字面量」判定，合法性交由 InetAddress 解析） */
    private val IPV4_LITERAL = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    /** IPv4 点分十进制（用于识别「桶名被格式化为 IP 地址」） */
    private val IPV4_STRICT = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )

    /**
     * 校验 S3 桶名。非法即抛 [SyncException.InvalidEndpointError]（fail-closed）。
     * 纯字符串校验，恒常执行（不受测试客户端注入门控影响——注入面与是否回环无关）。
     */
    fun validateBucketName(bucketName: String) {
        val bucket = bucketName.trim()
        val illegal = !S3_BUCKET_REGEX.matches(bucket) ||
            ADJACENT_DOTS.containsMatchIn(bucket) ||
            bucket.startsWith("xn--") ||
            bucket.startsWith("sthree-") ||
            bucket.endsWith("-s3alias") ||
            bucket.endsWith("--ol-s3") ||
            // 桶名被格式化为 IP 地址：AWS 明令禁止，且 virtual-host 拼接下极易与内网 IP 混淆
            IPV4_STRICT.matches(bucket)
        if (illegal) {
            throw SyncException.InvalidEndpointError(
                "S3 桶名 \"$bucketName\" 不符合 AWS 命名规范（3–63 字符，仅小写字母/数字/点/连字符，" +
                    "首尾须为字母或数字，禁止相邻点号、IP 地址格式与保留前后缀）。" +
                    "该限制同时用于阻断经桶名向 authority 注入 @ / # ? 等成分改写目标主机的攻击"
            )
        }
    }

    /**
     * 校验端点主机（构造期，纯解析 + 字面 IP 判定，不触发 DNS）。
     *
     * ISSUE-P2-425 放宽后的构造期仅拒绝三类（fail-closed）：
     * 1. **明文 scheme**（`http://` 等）——「明文全拒」的产品红线；
     * 2. **userinfo 注入**（`https://good.com@evil.com/`）——改写真实目标主机的注入手法；
     * 3. **云元数据字面量**（`169.254.0.0/16`）——唯一保留的网段级构造期拒绝，
     *    即便作为配置端点也仍拒绝（AC④ 红线）。
     *
     * 自建 / 内网 HTTPS 端点（RFC1918 / ULA / 链路本地（非元数据）/ `localhost` / `.local` /
     * `.internal` 及一切私网字面 IP）**不再拒绝**——其「非预期内网到达」风险由连接期两层
     * 守卫（[SsrfGuardDns] / [SsrfGuardSocketFactory]）复核，见类 KDoc。
     *
     * @param rawEndpoint 用户填写的端点（可无 scheme，按 https 归一化）
     * @param allowedHosts 显式白名单（构造期不再据此跳过任何检查——白名单只在连接期由
     *   [SsrfGuardDns] 生效，云元数据红线更不随其放行；参数保留以维持调用方签名）
     * @return 规范化后的端点主机。调用方须把**实际请求主机**传给
     *   [SyncHttpClientFactory.createSyncClient] 登记连接期豁免：WebDAV 即本返回值，
     *   S3 还要按寻址模式推导桶前缀主机（virtual-host 风格访问 `bucket.端点主机`）
     */
    fun validateEndpointHost(rawEndpoint: String, allowedHosts: Set<String> = emptySet()): String {
        val trimmed = rawEndpoint.trim()
        val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        // 用 OkHttp HttpUrl 解析：与实际发起连接所用解析器同源，杜绝「校验解析」与「连接解析」差异
        val url = normalized.toHttpUrlOrNull()
            ?: throw SyncException.InvalidEndpointError("同步端点 URL 非法，无法解析主机：\"$rawEndpoint\"")

        // ISSUE-P2-425：明文全拒——工厂恒 TLS-only，这里在构造期给出用户可理解的类型化错误
        if (!url.isHttps) {
            throw SyncException.InvalidEndpointError(
                "同步端点必须使用 HTTPS（当前协议为 \"${url.scheme}://\"）。" +
                    "明文 HTTP 已被禁止以保护凭据与密码库传输，请填写 https:// 开头的地址"
            )
        }

        // 拒绝 userinfo 注入（`https://good.com@evil.com/`）：云凭据为独立字段，端点 URL 不应携带 @
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw SyncException.InvalidEndpointError(
                "同步端点 URL 不得包含用户名/密码（userinfo，即 @ 成分）——此为改写真实目标主机的注入手法，已拒绝"
            )
        }

        val host = url.host
        if (host.isBlank()) {
            throw SyncException.InvalidEndpointError("同步端点 URL 缺少有效主机：\"$rawEndpoint\"")
        }
        // 云元数据红线（AC④）：169.254.0.0/16 字面量即便作为配置端点、即便同时命中显式白名单
        // 也仍拒绝——红线先于豁免批准（ENG-20261028-41）
        val literal = parseIpLiteral(host.lowercase(Locale.US).removeSuffix("."))
        if (literal != null && isCloudMetadataAddress(literal)) {
            val whitelistedHint = if (isHostAllowed(host, allowedHosts)) {
                "；该主机虽在显式白名单中，红线仍不随白名单放行"
            } else {
                ""
            }
            throw SyncException.InvalidEndpointError(
                "同步端点主机 \"$host\" 为云元数据网段（169.254.0.0/16）地址，已拒绝" +
                    "（该网段仅是攻击面而非合法同步目标，任何口径下不放行$whitelistedHint）"
            )
        }
        return host
    }

    /** 主机（含 virtual-host 组合后）是否命中显式白名单豁免 */
    private fun isHostAllowed(host: String, allowedHosts: Set<String>): Boolean {
        if (allowedHosts.isEmpty()) return false
        val lower = host.lowercase(Locale.US).removeSuffix(".")
        return allowedHosts.any { it.lowercase(Locale.US) == lower }
    }

    /**
     * 将主机解析为 IP **字面量**（不触发 DNS）；非字面量返回 null。
     * [InetAddress.getByName] 对合法 IP 字面量仅做本地解析，不发起网络查询。
     *
     * 公开给 [SyncHttpClientFactory]：对「已配置端点为 IP 字面量」的场景做连接期预登记放行
     * （OkHttp 路由层对字面量短路、不经自定义 Dns，豁免无法经 Dns 层自然生效）。
     */
    fun parseIpLiteral(host: String): InetAddress? {
        val bare = if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
        val looksLikeIp = IPV4_LITERAL.matches(bare) || bare.contains(':')
        if (!looksLikeIp) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    /**
     * 判定地址是否属云元数据网段 `169.254.0.0/16`（ISSUE-P2-425 的唯一网段级红线）。
     *
     * 该网段在 ISSUE-P2-425 放宽后**不再随内网一并豁免**：无论作为配置端点（构造期拒绝，
     * 白名单不改变该结论）、作为白名单 / 已配置端点主机的解析结果（DNS 豁免分支先判元数据）
     * 还是连接目标（连接期复核先判元数据），一律不放行——
     * 元数据服务只可能被攻击面利用，不存在「合法的元数据同步目标」。
     *
     * 判据对象是**承载的 IPv4**，而不是 IPv6 字面量本身：`::ffff:169.254.169.254`
     * （IPv4-mapped，RFC 4291）与 `::169.254.169.254`（已废弃的 IPv4-compatible，RFC 4291/RFC 6890）
     * 的内嵌地址都在网段内。平台常把前者归一为 4 字节
     * （`InetAddress.getByAddress` 即转换，本仓 okhttp 也把 `[::ffff:a.b.c.d]` 规格化为 IPv4 文本），
     * 但 `Inet6Address.getByAddress` 与部分 DNS / 连接层结果会保留 16 字节形态，
     * 故两种字节数都必须按内嵌 IPv4 判定（ENG-20261028-41）。
     * 未知地址族保守判定为命中（fail-closed）；普通 IPv6 不是元数据地址。
     */
    fun isCloudMetadataAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        val embedded = embeddedIpv4(bytes) ?: return bytes.size != IPV6_ADDRESS_BYTES
        return (embedded[0].toInt() and 0xFF) == METADATA_OCTET_1 &&
            (embedded[1].toInt() and 0xFF) == METADATA_OCTET_2
    }

    /**
     * 地址承载的 IPv4 字节：4 字节地址本身、`::ffff:a.b.c.d`（IPv4-mapped）或
     * `::a.b.c.d`（已废弃 IPv4-compatible）的内嵌 IPv4；其余（普通 IPv6、未知地址族）返回 null。
     *
     * 映射形态要求前 10 字节全零**且**第 10–11 字节为 `0xFF,0xFF` 标记，内嵌 IPv4 固定在偏移 12–15：
     * 只比较偏移 10–11 与 169/254 会漏判真实的映射地址（该处是 0xFF 标记，永不与 169/254 相等），
     * 也会对前导零段形态取错偏移（`::a9fe:a9fe` 的内嵌 IPv4 起于偏移 12 而非 10）。
     */
    private fun embeddedIpv4(bytes: ByteArray): ByteArray? {
        if (bytes.size == IPV4_ADDRESS_BYTES) return bytes
        if (bytes.size != IPV6_ADDRESS_BYTES) return null
        val compat = (0 until IPV4_EMBEDDED_OFFSET).all { bytes[it].toInt() == 0 }
        val mapped = (0 until IPV4_MAPPED_MARKER_OFFSET).all { bytes[it].toInt() == 0 } &&
            (bytes[IPV4_MAPPED_MARKER_OFFSET].toInt() and 0xFF) == IPV4_MAPPED_MARKER_OCTET &&
            (bytes[IPV4_MAPPED_MARKER_OFFSET + 1].toInt() and 0xFF) == IPV4_MAPPED_MARKER_OCTET
        return if (compat || mapped) {
            bytes.copyOfRange(IPV4_EMBEDDED_OFFSET, IPV6_ADDRESS_BYTES)
        } else {
            null
        }
    }

    /**
     * 地址的规范文本键：承载同一 IPv4 的不同拼写（`a.b.c.d` / `::ffff:a.b.c.d` / `::a.b.c.d`）
     * 归一为点分十进制，其余按 `hostAddress` 小写。
     *
     * [SsrfAddressApprovals] 用它比对「DNS 解析结果」与「实际连接目标」：`InetAddress.equals`
     * 不跨形态相等（映射形态的 `Inet6Address` 与 4 字节 `Inet4Address` 互不相等），
     * 只按 `hostAddress` 文本比对会让同一地址因归一形态不同而失配。
     */
    internal fun addressKey(addr: InetAddress): String {
        val embedded = embeddedIpv4(addr.address) ?: return addr.hostAddress.lowercase(Locale.US)
        return (0 until IPV4_ADDRESS_BYTES).joinToString(".") { (embedded[it].toInt() and 0xFF).toString() }
    }

    /** 云元数据网段 `169.254.0.0/16` 的首字节 */
    private const val METADATA_OCTET_1 = 169

    /** 云元数据网段 `169.254.0.0/16` 的次字节 */
    private const val METADATA_OCTET_2 = 254

    /**
     * 判定一个已解析地址是否落入必须拒绝的内网/保留网段。
     * 覆盖 IPv4 与 IPv6，fail-closed：命中任一即视为不安全。
     */
    fun isBlockedAddress(addr: InetAddress): Boolean {
        // JDK/Android 内建语义：环回、任意本地（0.0.0.0/::）、链路本地（含 169.254 云元数据）、
        // 站点本地（IPv4 RFC1918：10/8、172.16/12、192.168/16）、组播
        if (addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address
        return if (bytes.size == 4) {
            isBlockedIpv4(bytes)
        } else if (bytes.size == 16) {
            isBlockedIpv6(bytes)
        } else {
            // 未知地址族：保守拒绝
            true
        }
    }

    /** IPv4 补充保留网段（内建 isSiteLocalAddress 未覆盖者） */
    private fun isBlockedIpv4(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        return when {
            // 100.64.0.0/10 运营商级 NAT（RFC 6598）共享地址空间
            b0 == 100 && b1 in 64..127 -> true
            // 192.0.0.0/24 IETF 协议保留
            b0 == 192 && b1 == 0 && b2 == 0 -> true
            // 192.0.2.0/24 TEST-NET-1 文档示例
            b0 == 192 && b1 == 0 && b2 == 2 -> true
            // 198.18.0.0/15 网络设备基准测试
            b0 == 198 && (b1 == 18 || b1 == 19) -> true
            // 198.51.100.0/24 TEST-NET-2
            b0 == 198 && b1 == 51 && b2 == 100 -> true
            // 203.0.113.0/24 TEST-NET-3
            b0 == 203 && b1 == 0 && b2 == 113 -> true
            // 240.0.0.0/4 保留（含 255.255.255.255 广播）
            b0 >= 240 -> true
            else -> false
        }
    }

    /** IPv6 补充保留网段（内建 isSiteLocalAddress 仅覆盖已废弃的 fec0::/10） */
    private fun isBlockedIpv6(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xFF
        // fc00::/7 唯一本地地址（ULA，IPv6 版 RFC1918）
        if (b0 == ULA_PREFIX_LOWER || b0 == ULA_PREFIX_UPPER) return true
        // 承载 IPv4 的形态（::ffff:0:0/96 映射与废弃的 ::/96）：抽取内嵌 IPv4 递归判定，
        // 杜绝以映射地址绕过 IPv4 网段检查
        val embedded = embeddedIpv4(b) ?: return false
        return runCatching { isBlockedAddress(InetAddress.getByAddress(embedded)) }.getOrDefault(true)
    }

    /** fc00::/7 唯一本地地址首字节下界 */
    private const val ULA_PREFIX_LOWER = 0xFC

    /** fc00::/7 唯一本地地址首字节上界 */
    private const val ULA_PREFIX_UPPER = 0xFD

    /** ::ffff:0:0/96 IPv4-mapped 的标记字节偏移（前 10 字节须全零） */
    private const val IPV4_MAPPED_MARKER_OFFSET = 10

    /** IPv4-mapped 标记字节的取值 */
    private const val IPV4_MAPPED_MARKER_OCTET = 0xFF

    /** IPv6 字串中内嵌 IPv4 的起始偏移（同时是 IPv4-compatible 前导零段长度） */
    private const val IPV4_EMBEDDED_OFFSET = 12

    /** IPv4 地址字节数 */
    private const val IPV4_ADDRESS_BYTES = 4

    /** IPv6 地址字节数（亦作内嵌 IPv4 的截取上界） */
    private const val IPV6_ADDRESS_BYTES = 16
}

/**
 * SSRF 防护 DNS：包装系统 DNS，在**连接期**对主机名解析结果逐一判定，
 * 任一解析地址落入内网/保留网段即整体拒绝（fail-closed，不做「过滤后放行」以免
 * 攻击者以「公网 IP + 内网 IP」混合应答实施 DNS 重绑定绕过）。
 *
 * 校验用的解析结果即喂给实际连接，故对 DNS 重绑定同样有效。仅装配于生产客户端
 * （[SyncHttpClientFactory]）；测试/本地联调经注入自定义客户端旁路。
 *
 * **本层管不到 IP 字面量与重定向跳转**——那两条由 [SsrfGuardSocketFactory] 在连接期兜底
 * （ISSUE-P2-208），本类只负责「主机名 ⇒ 解析结果」这一面。
 */
class SsrfGuardDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowedHosts: Set<String> = emptySet(),
    /**
     * ISSUE-P2-208：白名单豁免地址登记表——豁免主机解析出的内网地址在此登记后，
     * 由 [SsrfGuardSocketFactory] 的连接期复核放行（保障内网自建场景不被纵深防御误伤）。
     */
    private val approvals: SsrfAddressApprovals = SsrfAddressApprovals()
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        // ISSUE-P2-425 / ENG-20261028-41：云元数据红线先于豁免与网段判定——豁免只覆盖
        // 「内网自建可用性」这一意图，**不延伸到云元数据网段**（映射形态的内嵌 IPv4 同样命中）
        val metadata = addresses.firstOrNull { SyncEndpointGuard.isCloudMetadataAddress(it) }
        if (metadata != null) {
            throw UnknownHostException(
                "SSRF 防护：主机 \"$hostname\" 解析到云元数据网段地址（${metadata.hostAddress}），" +
                    "红线不随豁免放行，已拒绝连接"
            )
        }
        val lower = hostname.lowercase(Locale.US).removeSuffix(".")
        val exempt = allowedHosts.any { it.lowercase(Locale.US) == lower }
        if (exempt) {
            approvals.approveAll(addresses)
            return addresses
        }

        val blocked = addresses.firstOrNull { SyncEndpointGuard.isBlockedAddress(it) }
        if (blocked != null) {
            throw UnknownHostException(
                "SSRF 防护：主机 \"$hostname\" 解析到内网/保留网段地址（${blocked.hostAddress}），已拒绝连接"
            )
        }
        return addresses
    }
}
