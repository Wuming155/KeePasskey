package com.keepasskey.sync.webdav

import android.util.Log
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.network.SyncEndpointGuard
import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Node
import java.net.URLEncoder
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 标准 WebDAV 客户端实现 (RFC 4918)。
 * 支持 Nextcloud、ownCloud、坚果云等主流公网商业云 WebDAV 服务（不支持自建内网服务器）：
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
    /**
     * Wave 14 传输安全网络选项（TLS-only 恒定 + 显式超时；证书固定已移除，走系统默认 CA 链）。
     * 由 app 层组装传入（纯数据契约，维持 sync 不依赖 app 的单向依赖）。
     */
    private val networkOptions: SyncNetworkOptions = SyncNetworkOptions(),
    // 测试注入口：HTTP 回环（MockWebServer）需显式传入默认规格客户端；生产恒为 null（走 TLS-only 工厂）
    client: OkHttpClient? = null
) : SyncProvider {

    // Wave 12/14 传输安全：默认经 TLS-only 工厂构建（排除 CLEARTEXT + 显式超时 + 系统 CA 链验证），
    // 仅 HTTP 回环测试需显式注入明文客户端
    private val httpClient: OkHttpClient = client ?: SyncHttpClientFactory.createSyncClient(networkOptions)

    // L4 整改：Basic 认证头在构造时立即计算——调用方（SyncCoordinator）在构造返回后
    // 会立即显式清零传入的密码 CharArray，此前的 by lazy 首请求延迟求值会在清零后
    // 才读取密码，导致实际以空密码认证、同步必然失败。
    private val authHeader: String

    init {
        // Wave 14 全站强制 HTTPS（生产路径 fail-fast）：显式 http:// 端点在构造期即拒绝并抛
        // 类型化 InvalidEndpointError（用户可理解提示），而非在网络层以晦涩错误失败；
        // 仅测试回环（显式注入 HTTP 客户端）豁免——MockWebServer 回环地址为 http://，不承载生产流量
        if (client == null) {
            val trimmedUrl = serverUrl.trim()
            if (trimmedUrl.contains("://") && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                throw SyncException.InvalidEndpointError(
                    "WebDAV 端点必须使用 HTTPS（当前协议为 \"${trimmedUrl.substringBefore("://")}://\"）。" +
                        "明文 HTTP 已被禁止以保护凭据与密码库传输，请填写 https:// 开头的商业云服务地址"
                )
            }
            // ISSUE-P1-05（ZT-05）SSRF 构造期防线：拒绝 userinfo 注入、本地/内网保留名与
            // 字面 IP 的内网/保留网段（含 169.254.169.254 云元数据端点）；主机名的解析后
            // 网段校验由连接期 SsrfGuardDns 承担。仅测试回环（注入客户端）豁免
            SyncEndpointGuard.validateEndpointHost(serverUrl, networkOptions.ssrfAllowedHosts)
        }
        authHeader = buildBasicAuthHeader(username, passwordChars)
        passwordChars.fill('0')
    }

    /**
     * 手工构造 Basic 认证头，密码全程不经 String（敏感数据铁律）。
     *
     * TASK-25 整改：凭据统一按 UTF-8 编码（TASK-25 前对齐 OkHttp Credentials.basic 的
     * RFC 7617 默认 charset ISO-8859-1——非 ASCII（中文）密码被错误转码，主流 WebDAV
     * 服务端（Nextcloud/ownCloud/坚果云等 sabre 系实现按 UTF-8 解码）鉴权必然 401）。
     * username 属非机密标识符可走 String；password 以 CharBuffer 直转 UTF-8 字节
     * 参与 "user:pass" 凭据拼接，Base64 编码后中间字节数组立即擦除。
     * （RFC 7617 §2.1 的 charset 参数仅存在于服务端 WWW-Authenticate 挑战侧，
     * 请求侧 Authorization 头无声明机制，故仅改编码不附参数。）
     * 已声明限界：最终 Authorization 头以 Base64 形态驻留 Provider 生命周期
     * （OkHttp header API 以 String 承载），与 S3 侧 SigV4 管线的 String 边界声明一致。
     */
    private fun buildBasicAuthHeader(username: String, passwordChars: CharArray): String {
        val passwordBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passwordChars))
        val encoded = try {
            val passwordBytes = ByteArray(passwordBuffer.remaining())
            passwordBuffer.get(passwordBytes)
            val combined = username.toByteArray(StandardCharsets.UTF_8) +
                    byteArrayOf(':'.code.toByte()) +
                    passwordBytes
            try {
                Base64.getEncoder().encodeToString(combined)
            } finally {
                combined.fill(0)
            }
        } finally {
            if (passwordBuffer.hasArray()) passwordBuffer.array().fill(0)
        }
        return "Basic $encoded"
    }

    private fun encodePath(path: String): String {
        // P3-15 纵深防御：剔除 "." 与 ".." 段，杜绝路径遍历序列直达服务器
        return path.split('/')
            .filter { segment -> segment != "." && segment != ".." }
            .joinToString("/") { segment ->
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

            httpClient.newCall(request).execute().use { response ->
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

            httpClient.newCall(request).execute().use { response ->
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
                val contentLength = if (parsed.contentLength >= 0L) {
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

            httpClient.newCall(request).execute().use { response ->
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

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
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
     * 2. 发送 WebDAV MOVE 命令（Destination: 目标完整 URL）；
     *    对远端目标文件的 ETag 预条件使用 RFC 4918 `If` 头 tagged list 语法
     *    （`If: <destUrl> (["etag"])`）——`If-Match` 默认仅作用于请求-URI（即源临时文件），
     *    对 MOVE 目标无约束效力；
     * 3. MOVE 失败重试 1 次；
     * 4. 仍失败则 DELETE 清除临时文件并抛错回滚。
     *
     * Overwrite 语义裁定（expectedEtag 为空时的两种形态必须区分）：
     * - **真首传**（远端目标不存在）→ `Overwrite: F` 保持原子创建保护，
     *   目标若被并发创建由服务端 412 转 ConflictError；
     * - **无 ETag 服务器的覆盖上传**（本地赢自动上传 / 冲突解决提交路径，目标必然已存在）
     *   → `Overwrite: T`，语义退化为最后写入者胜——无 ETag 服务器无法实施乐观锁，
     *   这是在此类服务器上唯一可行的写语义；恒用 `Overwrite: F` 会让 MOVE 对已存在目标
     *   恒定 412，上传路径陷入死锁（冲突解决提交同样 412，同步永久无法收敛）。
     *   目标存在性以 PROPFIND 探测判定，探测失败按「目标存在」的保守对侧处理为 `F`（fail-safe）。
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

            val overwriteFlag = if (!expectedEtag.isNullOrBlank()) {
                // If tagged list 已在服务端原子校验目标 ETag，Overwrite: T 仅表示允许替换
                "T"
            } else {
                // 无期望 ETag：探测目标存在性区分真首传与无 ETag 服务器的覆盖上传
                if (getMetadata(remotePath).isSuccess) "T" else "F"
            }

            fun createMoveRequest(): Request {
                val moveBuilder = Request.Builder()
                    .url(sourceUrl)
                    .method("MOVE", null)
                    .header("Authorization", authHeader)
                    .header("Destination", destUrl)

                if (!expectedEtag.isNullOrBlank()) {
                    // RFC 4918 Section 10.4: tagged list If 头把 ETag 预条件绑定到 MOVE 目标资源
                    moveBuilder.header("Overwrite", overwriteFlag)
                    moveBuilder.header("If", "<$destUrl> ([\"${cleanEtag(expectedEtag)}\"])")
                } else {
                    moveBuilder.header("Overwrite", overwriteFlag)
                }
                return moveBuilder.build()
            }

            var moveResponse: Response? = null
            var moveSuccess = false
            var conflictError: SyncException.ConflictError? = null

            for (attempt in 0..1) {
                try {
                    val resp = httpClient.newCall(createMoveRequest()).execute()
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

            httpClient.newCall(request).execute().use { response ->
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
            return ParsedPropfind("", -1L, 0L, false)
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
            // 节点缺失以 -1 哨兵标记（回退 HTTP 头）；节点存在（含 0，零字节文件）必须如实采信——
            // 207 响应的 HTTP Content-Length 是 XML 报文自身大小，误当文件大小会让零字节文件
            // 元数据撒谎并污染同步基线比较
            val contentLength = lengthNodes.firstOrNull()?.textContent?.trim()?.toLongOrNull() ?: -1L

            val modNodes = mutableListOf<Node>()
            findNodes(root, "getlastmodified", modNodes)
            val lastModifiedStr = modNodes.firstOrNull()?.textContent?.trim().orEmpty()
            val lastModifiedMillis = parseHttpDate(lastModifiedStr)

            val collectionNodes = mutableListOf<Node>()
            findNodes(root, "collection", collectionNodes)
            val isDirectory = collectionNodes.isNotEmpty()

            ParsedPropfind(etag, contentLength, lastModifiedMillis, isDirectory)
        } catch (e: Exception) {
            // P3-17 整改：解析失败至少落日志，不再静默吞掉（回退空元数据语义保留，
            // 由调用方按「缺失」回退 HTTP 头哨兵处理）
            Log.w(TAG, "PROPFIND 响应 XML 解析失败，回退空元数据", e)
            ParsedPropfind("", -1L, 0L, false)
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
        private const val TAG = "WebDavSyncProvider"

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