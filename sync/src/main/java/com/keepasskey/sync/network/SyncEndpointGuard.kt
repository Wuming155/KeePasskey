package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * ISSUE-P1-05（ZT-05）同步端点 SSRF 与主机注入统一防线。
 *
 * 本应用仅面向正规公网商业云（AWS S3 / R2 / MinIO 公有端点、Nextcloud / 坚果云等公有 WebDAV），
 * 用户可控的端点 URL 与 S3 桶名若不加校验即可被用于两类攻击：
 *
 * 1. **SSRF（CWE-918）**：端点直填内网/云元数据地址（`https://169.254.169.254/`、
 *    `https://192.168.x.x/`、`https://localhost/`），或填入解析到内网 IP 的域名，
 *    诱导设备向内部网络或云元数据服务发起携带凭据的请求；
 * 2. **主机注入**：S3 virtual-hosted 分支将未校验的桶名直接拼进 authority
 *    （`scheme://bucket.host/key`），注入 `x@evil.com/`、`x#`、`x?` 即可改写真实目标主机，
 *    且 SigV4 canonicalHeaders 取自被注入后的 host → 签名自洽 → 向攻击者主机投递对其有效的签名。
 *
 * 防线分两层，均 fail-closed：
 * - **构造期（纯字符串/字面 IP 校验，零网络）**：桶名严格按 S3 命名正则校验杜绝 authority 注入；
 *   端点主机拒绝 userinfo 注入、本地/内网保留名与**字面 IP** 的内网/保留网段；
 * - **连接期（DNS 解析后校验）**：经 [SsrfGuardDns] 拦截主机名解析结果，任一解析地址落入
 *   内网/保留网段即整体拒绝——同时抵御 DNS 重绑定（校验用的解析结果即喂给实际连接）。
 *
 * 显式白名单豁免：[allowedHosts] 提供可审计的例外通道（默认空）；测试/本地联调另经
 * Provider 注入自定义 OkHttpClient 的生产旁路豁免（与既有强制 HTTPS 校验的 `client == null` 门控一致）。
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
     * 拒绝 userinfo 注入、本地/内网保留主机名与字面 IP 的内网/保留网段。
     *
     * @param rawEndpoint 用户填写的端点（可无 scheme，按 https 归一化）
     * @param allowedHosts 显式白名单豁免（默认空）
     */
    fun validateEndpointHost(rawEndpoint: String, allowedHosts: Set<String> = emptySet()) {
        val trimmed = rawEndpoint.trim()
        val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        // 用 OkHttp HttpUrl 解析：与实际发起连接所用解析器同源，杜绝「校验解析」与「连接解析」差异
        val url = normalized.toHttpUrlOrNull()
            ?: throw SyncException.InvalidEndpointError("同步端点 URL 非法，无法解析主机：\"$rawEndpoint\"")

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
        if (isHostAllowed(host, allowedHosts)) return
        assertHostNotBlocked(host)
    }

    /** 主机（含 virtual-host 组合后）是否命中显式白名单豁免 */
    private fun isHostAllowed(host: String, allowedHosts: Set<String>): Boolean {
        if (allowedHosts.isEmpty()) return false
        val lower = host.lowercase(Locale.US).removeSuffix(".")
        return allowedHosts.any { it.lowercase(Locale.US) == lower }
    }

    /**
     * 断言主机非本地/内网保留名、且非内网/保留网段的字面 IP。仅对字面 IP 做网段判定，
     * 主机名的解析后判定由连接期 [SsrfGuardDns] 承担（避免构造期阻塞式 DNS）。
     */
    private fun assertHostNotBlocked(host: String) {
        val lower = host.lowercase(Locale.US).removeSuffix(".")
        if (lower == "localhost" ||
            lower.endsWith(".localhost") ||
            lower.endsWith(".local") ||
            lower.endsWith(".internal")
        ) {
            throw SyncException.InvalidEndpointError(
                "同步端点主机 \"$host\" 属本地/内网保留名，已拒绝（SSRF 防护：本应用仅支持公网商业云）"
            )
        }
        val literal = parseIpLiteral(lower)
        if (literal != null && isBlockedAddress(literal)) {
            throw SyncException.InvalidEndpointError(
                "同步端点主机 \"$host\" 为内网/保留网段地址，已拒绝（SSRF 防护：禁止直连内网段与云元数据端点）"
            )
        }
    }

    /**
     * 将主机解析为 IP **字面量**（不触发 DNS）；非字面量返回 null。
     * [InetAddress.getByName] 对合法 IP 字面量仅做本地解析，不发起网络查询。
     */
    private fun parseIpLiteral(host: String): InetAddress? {
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
        if (b0 == 0xFC || b0 == 0xFD) return true
        // ::ffff:0:0/96 IPv4-mapped：抽取内嵌 IPv4 递归判定，杜绝以映射地址绕过 IPv4 网段检查
        val isMapped = (0..9).all { b[it].toInt() == 0 } &&
            (b[10].toInt() and 0xFF) == 0xFF &&
            (b[11].toInt() and 0xFF) == 0xFF
        if (isMapped) {
            val v4 = byteArrayOf(b[12], b[13], b[14], b[15])
            return runCatching { isBlockedAddress(InetAddress.getByAddress(v4)) }.getOrDefault(true)
        }
        return false
    }
}

/**
 * SSRF 防护 DNS：包装系统 DNS，在**连接期**对主机名解析结果逐一判定，
 * 任一解析地址落入内网/保留网段即整体拒绝（fail-closed，不做「过滤后放行」以免
 * 攻击者以「公网 IP + 内网 IP」混合应答实施 DNS 重绑定绕过）。
 *
 * 校验用的解析结果即喂给实际连接，故对 DNS 重绑定同样有效。仅装配于生产客户端
 * （[SyncHttpClientFactory]）；测试/本地联调经注入自定义客户端旁路。
 */
class SsrfGuardDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowedHosts: Set<String> = emptySet()
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val lower = hostname.lowercase(Locale.US).removeSuffix(".")
        val exempt = allowedHosts.any { it.lowercase(Locale.US) == lower }
        if (exempt) return addresses

        val blocked = addresses.firstOrNull { SyncEndpointGuard.isBlockedAddress(it) }
        if (blocked != null) {
            throw UnknownHostException(
                "SSRF 防护：主机 \"$hostname\" 解析到内网/保留网段地址（${blocked.hostAddress}），已拒绝连接"
            )
        }
        return addresses
    }
}
