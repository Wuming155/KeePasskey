package com.keepasskey.sync.s3

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS S3 兼容协议客户端实现。
 * 兼容 AWS S3、Cloudflare R2、MinIO、阿里云 OSS、Backblaze B2 等标准 S3 兼容存储。
 * 内部实现纯净轻量级 AWS Signature Version 4 (SigV4) 鉴权算法：
 * 1. 规范请求 (Canonical Request) 构造与 SHA-256 摘要；
 * 2. 待签名字符串 (StringToSign) 生成；
 * 3. 级联 HMAC-SHA256 派生签名密钥 (Signing Key) 与最终 Authorization Header 构造；
 * 4. 对象键 URL 编码与 SigV4 canonicalUri 严格一致；
 * 5. 首传 If-None-Match: * 原子创建与覆盖 If-Match 条件写并发控制（无 TOCTOU 竞争窗口）。
 */
class S3SyncProvider(
    private val endpoint: String,
    private val bucketName: String,
    private val region: String = "us-east-1",
    private val accessKeyId: String,
    private val secretAccessKey: String,
    /**
     * 寻址风格：false = virtual-host 风格（`bucket.endpoint`，AWS S3 等默认）；
     * true = path 风格（`endpoint/bucket`，Cloudflare R2、IP 直连端点等商业云场景开启）。
     */
    private val usePathStyle: Boolean = false,
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

    init {
        // Wave 14 全站强制 HTTPS（生产路径 fail-fast）：显式 http:// 端点在构造期即拒绝并抛
        // 类型化 InvalidEndpointError；无 scheme 输入由 buildUrl 自动补 https://；
        // 仅测试回环（显式注入 HTTP 客户端）豁免
        if (client == null) {
            val trimmedEndpoint = endpoint.trim()
            if (trimmedEndpoint.contains("://") && !trimmedEndpoint.startsWith("https://", ignoreCase = true)) {
                throw SyncException.InvalidEndpointError(
                    "S3 端点必须使用 HTTPS（当前协议为 \"${trimmedEndpoint.substringBefore("://")}://\"）。" +
                        "明文 HTTP 已被禁止以保护凭据与密码库传输，请填写 https:// 开头的商业云服务地址"
                )
            }
        }
    }

    private fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    private fun buildUrl(remotePath: String): String {
        val cleanEndpoint = endpoint.trimEnd('/')
        val cleanKey = encodePath(remotePath.trimStart('/'))
        val base = if (cleanEndpoint.contains("://")) cleanEndpoint else "https://$cleanEndpoint"
        return if (usePathStyle) {
            "$base/$bucketName/$cleanKey"
        } else {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://")
            "$scheme://$bucketName.$host/$cleanKey"
        }
    }

    private fun getHost(url: String): String {
        val withoutScheme = url.substringAfter("://")
        return withoutScheme.substringBefore('/')
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl("")
            val headers = signV4(
                method = "HEAD",
                url = url,
                payloadHash = EMPTY_SHA256
            )

            val requestBuilder = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 404 -> Unit
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                    else -> throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(remotePath)
            val headers = signV4(
                method = "HEAD",
                url = url,
                payloadHash = EMPTY_SHA256
            )

            val requestBuilder = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 404 -> throw SyncException.FileNotFound("S3 对象不存在: $remotePath")
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                    !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                }

                val etag = response.header("ETag").orEmpty().cleanEtag()
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                val lastModifiedStr = response.header("Last-Modified").orEmpty()
                val lastModified = parseHttpDate(lastModifiedStr)

                RemoteFileMetadata(
                    path = remotePath,
                    etag = etag,
                    contentLength = contentLength,
                    lastModifiedMillis = lastModified,
                    isDirectory = false
                )
            }
        }
    }

    override suspend fun download(remotePath: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(remotePath)
            val headers = signV4(
                method = "GET",
                url = url,
                payloadHash = EMPTY_SHA256
            )

            val requestBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 404 -> throw SyncException.FileNotFound("S3 对象不存在: $remotePath")
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                    !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                }

                response.body?.bytes() ?: throw SyncException.NetworkError("S3 响应为空")
            }
        }
    }

    /**
     * 上传本地数据库文件至 S3。
     *
     * 并发安全与条件写策略（条件写全闭环，无 TOCTOU 竞争窗口）：
     * 1. 首传场景 (expectedEtag == null 且远端不存在)：
     *    PUT 附带 `If-None-Match: *` 请求头，实现 S3 协议级原子创建；
     *    遭遇并发创建冲突时 S3 返回 HTTP 412 Precondition Failed。
     * 2. 覆盖更新场景：
     *    PUT 附带 `If-Match: "<expectedEtag>"` 条件头（AWS S3 及支持条件写的兼容存储
     *    在服务端原子校验），远端 ETag 与期望不符（含 HEAD 探测后被并发修改）时
     *    S3 返回 HTTP 412，映射为 [SyncException.ConflictError]。
     *    HEAD 预检仅作快速失败优化，正确性完全由 PUT 的 If-Match 服务端校验保证。
     * 3. 兼容性降级说明：少数未支持条件覆写的 S3 兼容存储可能忽略 If-Match 头，
     *    此时语义退化为「HEAD 预检 + 无条件 PUT」，行为与旧版一致，不会更差。
     */
    override suspend fun upload(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val isFirstUpload: Boolean
            var precheckEtag: String? = null
            if (expectedEtag.isNullOrBlank()) {
                val metaResult = getMetadata(remotePath)
                if (metaResult.isFailure && metaResult.exceptionOrNull() is SyncException.FileNotFound) {
                    isFirstUpload = true
                } else {
                    isFirstUpload = false
                    // 远端已存在却未声明期望 ETag：锁定 HEAD 所见版本，保证「覆盖的即所见」
                    precheckEtag = metaResult.getOrNull()?.etag
                }
            } else {
                isFirstUpload = false
                val currentMeta = getMetadata(remotePath).getOrNull()
                if (currentMeta != null && currentMeta.etag != cleanEtag(expectedEtag)) {
                    throw SyncException.ConflictError(
                        remoteEtag = currentMeta.etag,
                        localExpectedEtag = expectedEtag,
                        message = "S3 远端文件已被其他人更新 (ETag 不匹配)"
                    )
                }
                precheckEtag = expectedEtag
            }

            val url = buildUrl(remotePath)
            val payloadHash = sha256Hex(data)
            val headers = signV4(
                method = "PUT",
                url = url,
                payloadHash = payloadHash
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .put(data.toRequestBody("application/octet-stream".toMediaType()))

            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            if (isFirstUpload) {
                requestBuilder.header("If-None-Match", "*")
            } else {
                precheckEtag?.takeIf { it.isNotBlank() }?.let { conditionEtag ->
                    requestBuilder.header("If-Match", "\"${cleanEtag(conditionEtag)}\"")
                }
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 412 -> {
                        val currentMeta = getMetadata(remotePath).getOrNull()
                        throw SyncException.ConflictError(
                            remoteEtag = currentMeta?.etag.orEmpty(),
                            localExpectedEtag = expectedEtag.orEmpty(),
                            message = "S3 对象并发创建冲突或已被其他人修改 (HTTP 412 Precondition Failed)"
                        )
                    }
                    response.code == 401 || response.code == 403 ->
                        throw SyncException.AuthenticationError("S3 鉴权失败 (${response.code})")
                    !response.isSuccessful -> throw SyncException.ProtocolError(response.code, response.message)
                }

                response.header("ETag").orEmpty().cleanEtag().ifBlank {
                    getMetadata(remotePath).getOrThrow().etag
                }
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(remotePath)
            val headers = signV4(
                method = "DELETE",
                url = url,
                payloadHash = EMPTY_SHA256
            )

            val requestBuilder = Request.Builder().url(url).delete()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    /**
     * 实现 AWS Signature Version 4 鉴权。
     * canonicalUri 必须与实际请求 URL 经过相同路径编码后的 URI 严格一致。
     */
    internal fun signV4(
        method: String,
        url: String,
        payloadHash: String,
        dateTime: Date = Date()
    ): Map<String, String> {
        val isoFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val amzDate = isoFormat.format(dateTime)
        val dateStamp = dateFormat.format(dateTime)

        val host = getHost(url)
        val canonicalUri = "/" + url.substringAfter("://").substringAfter('/').substringBefore('?')

        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = "$method\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))

        val credentialScope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n$canonicalRequestHash"

        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorizationHeader = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "Host" to host,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to authorizationHeader
        )
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4$key").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            return sdf.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            return 0L
        }
    }

    companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
