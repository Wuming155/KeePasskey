package com.keepasskey.sync.s3

/**
 * S3 对象键与 URL 编解码纯函数协作单元（无状态，可自由复用）。
 *
 * 职责：
 * 1. 对象键分段 URL 编码，与 SigV4 canonicalUri 严格一致；
 * 2. 依据端点与寻址风格（virtual-host / path）拼装对象访问 URL；
 * 3. 从 URL 提取主机名与 canonicalUri。
 */
internal object S3KeyCodec {

    private const val SCHEME_SEPARATOR = "://"
    private const val DEFAULT_SCHEME = "https://"
    private const val PATH_SEPARATOR = "/"
    private const val PERCENT_SIGN = '%'
    private const val HEX_PAIR_FORMAT = "%02X"

    /**
     * TASK-26 整改：AWS SigV4 规范 URI 编码（对齐官方 `SignatureVersion4` 文档的
     * URI encode 规则）——保留集仅为 RFC 3986 unreserved 字符（`A-Za-z0-9 - _ . ~`），
     * 其余全部百分号编码（大写十六进制，空格编码为 %20 而非 +）。
     *
     * 此前实现使用 java.net.URLEncoder（表单编码语义）：`*` 属 URLEncoder 保留集不编码、
     * `~` 被强制编码为 %7E——两者均与 AWS 规范相反，含这两字符的对象键 canonicalUri
     * 与服务端期望不一致，签名必然不匹配（403 SignatureDoesNotMatch）。
     */
    fun encodePath(path: String): String {
        return path.split(PATH_SEPARATOR).joinToString(PATH_SEPARATOR) { segment ->
            awsUriEncode(segment)
        }
    }

    /**
     * 依据寻址风格拼装对象访问 URL：
     * path 风格为 `endpoint/bucket/key`，virtual-host 风格为 `scheme://bucket.host/key`。
     * 无 scheme 的端点自动补 https://（全站强制 HTTPS）。
     */
    fun buildUrl(endpoint: String, bucketName: String, usePathStyle: Boolean, remotePath: String): String {
        val cleanEndpoint = endpoint.trimEnd('/')
        val cleanKey = encodePath(remotePath.trimStart('/'))
        val base = if (cleanEndpoint.contains(SCHEME_SEPARATOR)) cleanEndpoint else DEFAULT_SCHEME + cleanEndpoint
        return if (usePathStyle) {
            "$base/$bucketName/$cleanKey"
        } else {
            val scheme = base.substringBefore(SCHEME_SEPARATOR)
            val host = base.substringAfter(SCHEME_SEPARATOR)
            "$scheme$SCHEME_SEPARATOR$bucketName.$host/$cleanKey"
        }
    }

    /** 提取 URL 主机名（含端口），用于 SigV4 canonicalHeaders 的 host 项。 */
    fun hostOf(url: String): String {
        return url.substringAfter(SCHEME_SEPARATOR).substringBefore('/')
    }

    /** 提取 SigV4 canonicalUri（不得含 query string）。 */
    fun canonicalUri(url: String): String {
        return "/" + url.substringAfter(SCHEME_SEPARATOR).substringAfter('/').substringBefore('?')
    }

    private fun awsUriEncode(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved = (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append(PERCENT_SIGN).append(HEX_PAIR_FORMAT.format(c))
            }
        }
        return sb.toString()
    }
}
