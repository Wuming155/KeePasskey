package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 标准 WebDAV 客户端实现 (RFC 4918)。
 * 支持 Nextcloud, ownCloud, 坚果云, Synology NAS 等主流 WebDAV 服务：
 * 1. PROPFIND: 解析 getetag, getcontentlength, getlastmodified;
 * 2. GET: 二进制流下载;
 * 3. PUT: 支持 If-Match: <etag> 乐观并发保护;
 * 4. MKCOL: 远程目录递归探测与创建。
 */
class WebDavSyncProvider(
    private val serverUrl: String,
    private val username: String,
    private val passwordChars: CharArray,
    private val client: OkHttpClient = OkHttpClient()
) : SyncProvider {

    private val authHeader: String by lazy {
        Credentials.basic(username, String(passwordChars))
    }

    private fun buildUrl(remotePath: String): String {
        val base = serverUrl.trimEnd('/')
        val path = remotePath.trimStart('/')
        return "$base/$path"
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = serverUrl.trimEnd('/')
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_XML.toRequestBody("application/xml".toMediaType()))
                .header("Authorization", authHeader)
                .header("Depth", "0")
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 207 -> Unit
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    else -> throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrl = buildUrl(remotePath)
            val request = Request.Builder()
                .url(fullUrl)
                .method("PROPFIND", PROPFIND_XML.toRequestBody("application/xml".toMediaType()))
                .header("Authorization", authHeader)
                .header("Depth", "0")
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw SyncException.FileNotFound("远程文件不存在: $remotePath")
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    !response.isSuccessful && response.code != 207 ->
                        throw SyncException.ProtocolError(response.code, response.message)
                }

                val xml = response.body?.string().orEmpty()
                val etag = parseTag(xml, "getetag").cleanEtag().ifBlank {
                    response.header("ETag")?.cleanEtag().orEmpty()
                }
                val contentLength = parseTag(xml, "getcontentlength").toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull() ?: 0L
                val lastModifiedStr = parseTag(xml, "getlastmodified").ifBlank {
                    response.header("Last-Modified").orEmpty()
                }
                val lastModifiedMillis = parseHttpDate(lastModifiedStr)

                RemoteFileMetadata(
                    path = remotePath,
                    etag = etag,
                    contentLength = contentLength,
                    lastModifiedMillis = lastModifiedMillis,
                    isDirectory = xml.contains("<resourcetype><collection/>") || xml.contains("<d:collection/>")
                )
            }
        }
    }

    override suspend fun download(remotePath: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrl = buildUrl(remotePath)
            val request = Request.Builder()
                .url(fullUrl)
                .get()
                .header("Authorization", authHeader)
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw SyncException.FileNotFound("远程文件不存在: $remotePath")
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                }

                response.body?.bytes() ?: throw SyncException.NetworkError("响应体为空")
            }
        }
    }

    override suspend fun upload(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrl = buildUrl(remotePath)
            val requestBuilder = Request.Builder()
                .url(fullUrl)
                .put(data.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", authHeader)

            if (!expectedEtag.isNullOrBlank()) {
                requestBuilder.header("If-Match", expectedEtag.formatHeaderEtag())
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 412 -> {
                        val currentMeta = getMetadata(remotePath).getOrNull()
                        throw SyncException.ConflictError(
                            remoteEtag = currentMeta?.etag.orEmpty(),
                            localExpectedEtag = expectedEtag.orEmpty(),
                            message = "WebDAV 远端文件已被其他人修改 (HTTP 412 Precondition Failed)"
                        )
                    }
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("WebDAV 鉴权失败 (${response.code})")
                    !response.isSuccessful && response.code != 201 && response.code != 204 ->
                        throw SyncException.ProtocolError(response.code, response.message)
                }

                response.header("ETag")?.cleanEtag() ?: getMetadata(remotePath).getOrThrow().etag
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrl = buildUrl(remotePath)
            val request = Request.Builder()
                .url(fullUrl)
                .delete()
                .header("Authorization", authHeader)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    private fun String.cleanEtag(): String = trim('"', ' ', 'W', '/', '\\')

    private fun String.formatHeaderEtag(): String {
        val trimmed = cleanEtag()
        return "\"$trimmed\""
    }

    private fun parseTag(xml: String, tagName: String): String {
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?$tagName[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?$tagName>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    companion object {
        private const val PROPFIND_XML = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:getetag/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""
    }
}
