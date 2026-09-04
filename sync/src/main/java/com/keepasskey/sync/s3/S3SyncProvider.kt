package com.keepasskey.sync.s3

import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * 3. 级联 HMAC-SHA256 派生签名密钥 (Signing Key) 与最终 Authorization Header 构造。
 */
class S3SyncProvider(
    private val endpoint: String,
    private val bucketName: String,
    private val region: String = "us-east-1",
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val client: OkHttpClient = OkHttpClient()
) : SyncProvider {

    private fun buildUrl(remotePath: String): String {
        val cleanEndpoint = endpoint.trimEnd('/')
        val cleanKey = remotePath.trimStart('/')
        return if (cleanEndpoint.contains("://")) {
            val scheme = cleanEndpoint.substringBefore("://")
            val host = cleanEndpoint.substringAfter("://")
            "$scheme://$bucketName.$host/$cleanKey"
        } else {
            "https://$bucketName.$cleanEndpoint/$cleanKey"
        }
    }

    private fun getHost(url: String): String {
        val withoutScheme = url.substringAfter("://")
        return withoutScheme.substringBefore('/')
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 通过 HEAD 请求根路径或测试对象检查存储桶连通性
            val url = buildUrl("")
            val headers = signV4(
                method = "HEAD",
                url = url,
                payloadHash = EMPTY_SHA256
            )

            val requestBuilder = Request.Builder().url(url).head()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            client.newCall(requestBuilder.build()).execute().use { response ->
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

            client.newCall(requestBuilder.build()).execute().use { response ->
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

            client.newCall(requestBuilder.build()).execute().use { response ->
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

    override suspend fun upload(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // 若携带 expectedEtag，先做 HEAD 检查避免并发覆盖
            if (!expectedEtag.isNullOrBlank()) {
                val currentMeta = getMetadata(remotePath).getOrNull()
                if (currentMeta != null && currentMeta.etag != expectedEtag.cleanEtag()) {
                    throw SyncException.ConflictError(
                        remoteEtag = currentMeta.etag,
                        localExpectedEtag = expectedEtag,
                        message = "S3 远端文件已被其他人更新 (ETag 不匹配)"
                    )
                }
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

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
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

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    throw SyncException.ProtocolError(response.code, response.message)
                }
            }
        }
    }

    /**
     * 实现 AWS Signature Version 4 鉴权
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

    private fun String.cleanEtag(): String = trim('"', ' ', 'W', '/', '\\')

    private fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            return sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            return System.currentTimeMillis()
        }
    }

    companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
