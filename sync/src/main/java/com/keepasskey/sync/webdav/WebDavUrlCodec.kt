package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.cleanEtag
import java.net.URLEncoder

/**
 * WebDAV 路径 ↔ URL 编码纯函数集合：
 * 逐段 UTF-8 编码（空格转 %20）并附带路径遍历防护。
 */
internal object WebDavUrlCodec {

    fun encodePath(path: String): String {
        // P3-15 纵深防御：剔除 "." 与 ".." 段，杜绝路径遍历序列直达服务器
        return path.split('/')
            .filter { segment -> segment != "." && segment != ".." }
            .joinToString("/") { segment ->
                if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
    }

    fun buildUrl(serverUrl: String, remotePath: String): String {
        val base = serverUrl.trimEnd('/')
        val encodedPath = encodePath(remotePath.trimStart('/'))
        return "$base/$encodedPath"
    }

    /**
     * 把规范化 ETag 收敛为 HTTP 头形态（ISSUE-P1-275 AC②）。
     * 弱校验 ETag 保留 `W/` 标记（`W/"abc"`），强 ETag 补引号（`"abc"`）——禁止剥掉弱标记后
     * 以强形态发送：RFC 7232 §2.3 的强比较要求两侧均非弱，剥标记发出的强形态在弱存储标签的
     * 服务器上永不匹配（恒 412）。RFC 4918 §10.4.9 的 `If` 头示例明示 `[W/"..."]` 为合法形态。
     */
    fun formatHeaderEtag(etag: String): String {
        val canonical = cleanEtag(etag)
        return if (canonical.startsWith("W/")) {
            "W/\"${canonical.substring(2)}\""
        } else {
            "\"$canonical\""
        }
    }
}
