package com.keepasskey.app.passkey

/**
 * 主机名的字面量形态。
 *
 * 三个消费点必须共用同一判定，否则会出现「一个入口把点分数字当分层域名、另一个不当」的分歧
 * （ENG-20261028-40 的缺陷形态）：
 * - [DomainMatcher.isDomainMatch]：IP 字面量只允许精确相等，不参与后缀匹配；
 * - [PublicSuffixList.isRegistrableDomain] 与 [PublicSuffixList.registrableDomain]：
 *   IP 与 `localhost` 都不是可注册域名，两处结论必须一致。
 *
 * 入参要求：已归一化（小写、无 scheme / 端口 / 方括号、已去尾部根点），与两个调用方的归一器一致。
 */
internal enum class HostShape {
    /** 常规域名（含 punycode，以及 `app.localhost` 这类多标签本地名） */
    Domain,

    /** 根区域的 `localhost` 单标签本地名（本地 RP 场景，不是可注册域名） */
    Localhost,

    /**
     * 全数字点分形态：IPv4 及浏览器按 IPv4 解析的多段数字主机
     * （如 `192.168.1.1`、`168.1.1`）。不要求段数为 4 或各段 ≤ 255——
     * 只要看起来是数字主机，就不得再按标签层级做后缀匹配。
     */
    Ipv4Like,

    /** 含冒号的 IPv6 字面量（调用方已去方括号与端口） */
    Ipv6
}

/** [HostShape] 的唯一判定实现（纯函数、无缓存；输入长度即单个主机名，开销可忽略） */
internal object HostShapes {

    private const val LOCALHOST = "localhost"

    fun of(host: String): HostShape {
        if (host.contains(':')) return HostShape.Ipv6
        if (host.equals(LOCALHOST, ignoreCase = true)) return HostShape.Localhost
        val labels = host.split('.')
        val numeric = labels.size >= 2 && labels.all { label ->
            label.isNotEmpty() && label.all { it in '0'..'9' }
        }
        return if (numeric) HostShape.Ipv4Like else HostShape.Domain
    }
}
