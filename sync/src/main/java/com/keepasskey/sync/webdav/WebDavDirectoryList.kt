package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.RemoteListEntry
import com.keepasskey.sync.model.RemoteListPage
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncDownloadLimits
import com.keepasskey.sync.network.runCatchingCancellable
import java.net.URLDecoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ISSUE-P3-387：WebDAV 目录列举编排（自 [WebDavSyncProvider.listRemoteDirectory] 拆出，零行为变更）。
 *
 * 保守降级：PROPFIND 失败 / 解析失败 / 非 207 一律 failure，不伪装空目录。
 */
internal object WebDavDirectoryList {

    internal const val MAX_BROWSE_PAGE: Int = 200

    /**
     * @param execute 传输执行入口（Provider 的瞬时重试封装）
     * @param serverUrl 服务器根 URL（用于 href → 相对路径还原）
     * @param authHeader 已构造的 Authorization 头
     */
    suspend fun list(
        serverUrl: String,
        authHeader: String,
        remotePath: String,
        cursor: String?,
        pageSize: Int,
        execute: suspend (Request) -> Response
    ): Result<RemoteListPage> = withContext(Dispatchers.IO) {
        // ISSUE-P3-570：execute 为挂起 lambda（provider 的瞬时重试封装含挂起点），
        // 裸 runCatching 会把协程取消归一为 Result.failure——换用不吞取消的同型包装
        runCatchingCancellable {
            val dirPath = normalizeBrowsePath(remotePath)
            val fullUrl = WebDavUrlCodec.buildUrl(serverUrl, dirPath)
            val request = Request.Builder()
                .url(fullUrl)
                .method("PROPFIND", PROPFIND_XML.toRequestBody("application/xml".toMediaType()))
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .build()

            execute(request).use { response ->
                when {
                    response.code == 404 ->
                        throw SyncException.FileNotFound("远程目录不存在: $dirPath")
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    !response.isSuccessful && response.code != 207 ->
                        throw SyncException.ProtocolError(response.code, response.message)
                }

                val xml = String(
                    SyncDownloadLimits.readBounded(
                        input = response.body.byteStream(),
                        declaredLength = response.body.contentLength(),
                        maxBytes = SyncDownloadLimits.MAX_PROPFIND_BYTES,
                        label = "PROPFIND-list"
                    ),
                    Charsets.UTF_8
                )
                val children = WebDavPropfindParser.parseChildren(xml)
                    ?: throw SyncException.ProtocolError(
                        response.code,
                        "WebDAV 目录列举响应无法解析（已拒绝伪装空目录）"
                    )

                val selfHref = fullUrl.trimEnd('/')
                val entries = children
                    .mapNotNull { child -> toListEntry(child.href, child, selfHref, dirPath, serverUrl) }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))

                val afterCursor = if (cursor.isNullOrBlank()) {
                    entries
                } else {
                    entries.dropWhile { it.name <= cursor }
                }
                val pageItems = afterCursor.take(pageSize.coerceIn(1, MAX_BROWSE_PAGE))
                val truncated = afterCursor.size > pageItems.size
                RemoteListPage(
                    entries = pageItems,
                    nextCursor = if (truncated) pageItems.lastOrNull()?.name else null,
                    truncated = truncated
                )
            }
        }
    }

    internal fun toListEntry(
        href: String,
        child: WebDavPropfindParser.ParsedPropfindChild,
        selfHref: String,
        dirPath: String,
        serverUrl: String
    ): RemoteListEntry? {
        if (href.isBlank()) return null
        val decoded = try {
            URLDecoder.decode(href, "UTF-8")
        } catch (_: Exception) {
            href
        }
        val selfDecoded = try {
            URLDecoder.decode(selfHref, "UTF-8")
        } catch (_: Exception) {
            selfHref
        }
        if (decoded.trimEnd('/') == selfDecoded.trimEnd('/')) return null

        val path = relativeResourcePath(decoded, serverUrl, dirPath) ?: return null
        if (path.isBlank()) return null
        val name = path.substringAfterLast('/')
        if (name.isBlank()) return null
        return RemoteListEntry(
            path = path,
            name = name,
            isDirectory = child.isDirectory || path.endsWith("/"),
            contentLength = child.contentLength.coerceAtLeast(0L),
            lastModifiedMillis = child.lastModifiedMillis.coerceAtLeast(0L)
        )
    }

    internal fun normalizeBrowsePath(remotePath: String): String =
        remotePath.trim().trim('/')

    /**
     * 把 PROPFIND href 还原为**相对 WebDAV 端点**的资源路径。
     *
     * 服务器常见三种形态：
     * - 完整 URL：`https://host/dav/files/user/vaults/`
     * - 绝对路径（含端点 path 前缀）：`/remote.php/dav/files/user/vaults/`
     * - 相对集合：`vaults/`
     *
     * 绝对路径若未剥离端点 path 前缀，会与 `WebDavUrlCodec.buildUrl` 再拼一次 ⇒
     * 下钻 / 选中 / 同步全部 404（ISSUE-P3-396）。
     */
    internal fun relativeResourcePath(href: String, serverUrl: String, dirPath: String): String? {
        val decoded = href.trim()
        if (decoded.isEmpty()) return null
        val base = serverUrl.trimEnd('/')
        if (decoded.startsWith(base)) {
            return normalizeBrowsePath(decoded.substring(base.length))
        }
        if (decoded.startsWith("/")) {
            val withoutSlashes = decoded.trim('/')
            val sp = serverUrlPath(serverUrl)?.trim('/')
            if (!sp.isNullOrEmpty()) {
                if (withoutSlashes == sp) return ""
                if (withoutSlashes.startsWith("$sp/")) {
                    return normalizeBrowsePath(withoutSlashes.substring(sp.length + 1))
                }
            }
            return normalizeBrowsePath(withoutSlashes)
        }
        return normalizeBrowsePath(if (dirPath.isEmpty()) decoded else "$dirPath/$decoded")
    }

    /** 从 serverUrl 取 path 组件（如 `/remote.php/dav/files/user`）；解析失败返回 null。 */
    internal fun serverUrlPath(serverUrl: String): String? {
        return try {
            java.net.URI(serverUrl).path?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** Depth:1 PROPFIND 报文（与 Provider 原文一致）。 */
    internal const val PROPFIND_XML: String = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getetag/>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>
"""
}
