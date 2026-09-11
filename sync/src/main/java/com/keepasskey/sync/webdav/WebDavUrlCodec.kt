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

    /** 把裸 ETag 收敛为 HTTP 头形态（去引号后重新加引号）。 */
    fun formatHeaderEtag(etag: String): String {
        val trimmed = cleanEtag(etag)
        return "\"$trimmed\""
    }
}
