package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Node
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 标准 WebDAV 客户端实现 (RFC 4918)。
 * 支持 Nextcloud, ownCloud, 坚果云, Synology NAS 等主流 WebDAV 服务：
 * 1. PROPFIND: 基于 DOM 解析 getetag, getcontentlength, getlastmodified, resourcetype;
 * 2. GET: 二进制流下载;
 * 3. PUT: 支持 If-Match: <etag> 乐观并发保护;
 * 4. 事务写 (uploadAtomic): PUT 唯一随机名 .kpktmp -> MOVE 覆盖 -> 失败重试/回滚;
 * 5. URL 编码：对路径段执行逐段 UTF-8 编码。
 */
class WebDavSyncProvider(
    private val serverUrl: String,
    private val username: String,
    passwordChars: CharArray,
    private val client: OkHttpClient = OkHttpClient()
) : SyncProvider {

    // L4 整改：Basic 认证头在构造时立即计算——调用方（SyncCoordinator）在构造返回后
    // 会立即显式清零传入的密码 CharArray，此前的 by lazy 首请求延迟求值会在清零后
    // 才读取密码，导致实际以空密码认证、同步必然失败。
    private val authHeader: String

    init {
        authHeader = Credentials.basic(username, String(passwordChars))
        passwordChars.fill('0')
    }

    private fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    private fun buildUrl(remotePath: String): String {
        val base = serverUrl.trimEnd('/')
        val encodedPath = encodePath(remotePath.trimStart('/'))
        return "$base/$encodedPath"
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
                val parsed = parsePropfindXml(xml)

                val etag = parsed.etag.ifBlank {
                    response.header("ETag")?.cleanEtag().orEmpty()
                }
                val contentLength = if (parsed.contentLength > 0L) {
                    parsed.contentLength
                } else {
                    response.header("Content-Length")?.toLongOrNull() ?: 0L
                }
                val lastModifiedMillis = if (parsed.lastModifiedMillis > 0L) {
                    parsed.lastModifiedMillis
                } else {
                    parseHttpDate(response.header("Last-Modified").orEmpty())
                }

                RemoteFileMetadata(
                    path = remotePath,
                    etag = etag,
                    contentLength = contentLength,
                    lastModifiedMillis = lastModifiedMillis,
                    isDirectory = parsed.isDirectory
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

    /**
     * 事务性原子上传 (P2-16)。
     * 流程：
     * 1. 上传至 `<remotePath>.<随机UUID>.kpktmp` 临时文件——临时名含每次操作的
     *    随机成分：若多客户端共用固定临时名，A 的 MOVE 可能搬运到 B 刚覆盖写入的
     *    临时内容（If 预条件只约束 MOVE 目标，不约束源临时文件），造成数据交叉污染；
     * 2. 发送 WebDAV MOVE 命令（Destination: 目标完整 URL，Overwrite: T）；
     *    对远端目标文件的 ETag 预条件使用 RFC 4918 `If` 头 tagged list 语法
     *    （`If: <destUrl> (["etag"])`）——`If-Match` 默认仅作用于请求-URI（即源临时文件），
     *    对 MOVE 目标无约束效力；
     * 3. MOVE 失败重试 1 次；
     * 4. 仍失败则 DELETE 清除临时文件并抛错回滚。
     */
    override suspend fun uploadAtomic(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tmpPath = "$remotePath.${UUID.randomUUID()}$ATOMIC_TMP_SUFFIX"
            val tmpUploadResult = upload(tmpPath, data, expectedEtag = null)
            if (tmpUploadResult.isFailure) {
                throw tmpUploadResult.exceptionOrNull() ?: SyncException.NetworkError("上传临时文件失败")
            }

            val sourceUrl = buildUrl(tmpPath)
            val destUrl = buildUrl(remotePath)

            fun createMoveRequest(): Request {
                val moveBuilder = Request.Builder()
                    .url(sourceUrl)
                    .method("MOVE", null)
                    .header("Authorization", authHeader)
                    .header("Destination", destUrl)
                    .header("Overwrite", "T")

                if (!expectedEtag.isNullOrBlank()) {
                    // RFC 4918 Section 10.4: tagged list If 头把 ETag 预条件绑定到 MOVE 目标资源
                    moveBuilder.header("If", "<$destUrl> ([\"${cleanEtag(expectedEtag)}\"])")
                }
                return moveBuilder.build()
            }

            var moveResponse: Response? = null
            var moveSuccess = false
            var conflictError: SyncException.ConflictError? = null

            for (attempt in 0..1) {
                try {
                    val resp = client.newCall(createMoveRequest()).execute()
                    if (resp.code == 412) {
                        val currentMeta = getMetadata(remotePath).getOrNull()
                        conflictError = SyncException.ConflictError(
                            remoteEtag = currentMeta?.etag.orEmpty(),
                            localExpectedEtag = expectedEtag.orEmpty(),
                            message = "WebDAV 原子写入 MOVE 失败：远端已被其他人修改 (HTTP 412)"
                        )
                        resp.close()
                        break
                    }
                    if (resp.isSuccessful || resp.code == 201 || resp.code == 204) {
                        moveResponse = resp
                        moveSuccess = true
                        break
                    } else {
                        resp.close()
                    }
                } catch (e: Exception) {
                    if (attempt == 1) throw e
                }
            }

            if (!moveSuccess) {
                // 回滚并清理临时文件
                delete(tmpPath)
                if (conflictError != null) {
                    throw conflictError
                }
                throw SyncException.ProtocolError(500, "WebDAV 原子写入 MOVE 失败，已清理临时文件")
            }

            val finalEtag = moveResponse?.header("ETag")?.cleanEtag()
            moveResponse?.close()

            finalEtag?.ifBlank { null } ?: getMetadata(remotePath).getOrThrow().etag
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

    private fun String.formatHeaderEtag(): String {
        val trimmed = cleanEtag(this)
        return "\"$trimmed\""
    }

    private data class ParsedPropfind(
        val etag: String,
        val contentLength: Long,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean
    )

    private fun parsePropfindXml(xml: String): ParsedPropfind {
        if (xml.isBlank()) {
            return ParsedPropfind("", 0L, 0L, false)
        }
        return try {
            // L2 整改：与 KdbxXmlParser 同级的 XXE 纵深防御——禁用 DTD 与外部实体，
            // 防御恶意/被劫持的 WebDAV 服务端返回带 XXE payload 的 PROPFIND 响应
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())
            val root = doc.documentElement

            fun findNodes(node: Node, targetLocalName: String, results: MutableList<Node>) {
                val name = node.localName ?: node.nodeName.substringAfter(':')
                if (name.equals(targetLocalName, ignoreCase = true)) {
                    results.add(node)
                }
                val children = node.childNodes
                for (i in 0 until children.length) {
                    findNodes(children.item(i), targetLocalName, results)
                }
            }

            val etagNodes = mutableListOf<Node>()
            findNodes(root, "getetag", etagNodes)
            val etag = etagNodes.firstOrNull()?.textContent?.trim().orEmpty().cleanEtag()

            val lengthNodes = mutableListOf<Node>()
            findNodes(root, "getcontentlength", lengthNodes)
            val contentLength = lengthNodes.firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: 0L

            val modNodes = mutableListOf<Node>()
            findNodes(root, "getlastmodified", modNodes)
            val lastModifiedStr = modNodes.firstOrNull()?.textContent?.trim().orEmpty()
            val lastModifiedMillis = parseHttpDate(lastModifiedStr)

            val collectionNodes = mutableListOf<Node>()
            findNodes(root, "collection", collectionNodes)
            val isDirectory = collectionNodes.isNotEmpty()

            ParsedPropfind(etag, contentLength, lastModifiedMillis, isDirectory)
        } catch (_: Exception) {
            ParsedPropfind("", 0L, 0L, false)
        }
    }

    private fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
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
        return 0L
    }

    companion object {
        const val ATOMIC_TMP_SUFFIX = ".kpktmp"

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
