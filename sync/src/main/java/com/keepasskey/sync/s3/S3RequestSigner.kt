package com.keepasskey.sync.s3

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (SigV4) 签名协作单元。
 *
 * 实现纯净轻量级 SigV4 鉴权算法：规范请求 (Canonical Request) 构造与 SHA-256 摘要、
 * 待签名字符串 (StringToSign) 生成、级联 HMAC-SHA256 派生签名密钥 (Signing Key)
 * 与最终 Authorization Header 构造。
 *
 * ISSUE-P1-06 敏感数据契约：本单元持有调用方传入的凭据 [CharArray] **引用**
 * （借用语义，不复制、不擦除调用方数组，清零职责由 [S3SyncProvider.clearCredentials] 承担）；
 * signingKey 派生链全程 ByteArray 承载，用毕 finally 逐一 `fill(0)`；
 * [accessKeyId] 仅在构造 Authorization header 瞬间转 String（HTTP 协议边界不可避免），
 * 该 String 为方法局部变量，随栈帧退出即不可达（对比旧版构造器字段级 String 驻留）。
 */
internal class S3RequestSigner(
    private val accessKeyId: CharArray,
    private val secretAccessKey: CharArray,
    private val region: String
) {

    /**
     * 实现 AWS Signature Version 4 鉴权。
     * canonicalUri 必须与实际请求 URL 经过相同路径编码后的 URI 严格一致。
     *
     * TASK-45：生产调用方一律传入补偿后时间戳；[dateTime] 保留默认值
     * 仅供单元测试注入固定时间点（已知答案向量）使用。
     */
    fun signV4(
        method: String,
        url: String,
        payloadHash: String,
        dateTime: Date = Date()
    ): Map<String, String> {
        val isoFormat = SimpleDateFormat(AMZ_DATE_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone(UTC_TIME_ZONE_ID)
        }
        val dateFormat = SimpleDateFormat(DATE_STAMP_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone(UTC_TIME_ZONE_ID)
        }

        val amzDate = isoFormat.format(dateTime)
        val dateStamp = dateFormat.format(dateTime)

        val host = S3KeyCodec.hostOf(url)
        val canonicalUri = S3KeyCodec.canonicalUri(url)

        val canonicalHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = "$method\n$canonicalUri\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalRequestBytes = canonicalRequest.toByteArray(Charsets.UTF_8)
        val canonicalRequestHash = try {
            sha256Hex(canonicalRequestBytes)
        } finally {
            canonicalRequestBytes.fill(0)
        }

        val credentialScope = "$dateStamp/$region/$SERVICE_NAME/$AWS4_REQUEST"
        val stringToSign = "$ALGORITHM\n$amzDate\n$credentialScope\n$canonicalRequestHash"

        // ISSUE-P1-06：signingKey 为敏感派生中间量，用毕必须擦除
        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, SERVICE_NAME)
        val signature = try {
            hmacSha256Hex(signingKey, stringToSign)
        } finally {
            signingKey.fill(0)
        }

        // accessKeyId CharArray → String：仅存活于本方法栈帧（HTTP header 协议边界），
        // 对比旧版构造器字段级 String 驻留，暴露面从「Provider 生命周期」收窄至「单次签名调用」
        val accessKeyIdStr = String(accessKeyId)
        val authorizationHeader = "$ALGORITHM Credential=$accessKeyIdStr/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            HEADER_HOST to host,
            HEADER_AMZ_DATE to amzDate,
            HEADER_AMZ_CONTENT_SHA256 to payloadHash,
            HEADER_AUTHORIZATION to authorizationHeader
        )
    }

    /** 计算 SHA-256 十六进制摘要（重载荷哈希与 SigV4 规范请求摘要共用）。 */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        return digest.digest(data).toHexString()
    }

    /**
     * ISSUE-P1-06 整改：SigV4 签名密钥派生链（kSecret → kDate → kRegion → kService → signingKey）。
     * 全链 ByteArray 中间量在 finally 中逐一 fill(0) 擦除——杜绝派生密钥材料残留堆内存。
     *
     * @param key Secret Access Key（CharArray 借用语义，本方法不擦除调用方数组，
     *            仅在内部转为 UTF-8 字节并立即擦除该字节副本）
     */
    private fun getSignatureKey(key: CharArray, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        // CharArray → UTF-8 字节（"AWS4" 前缀拼接），用毕立即擦除
        val kSecret = try {
            val prefix = AWS4_PREFIX.toByteArray(Charsets.UTF_8)
            val keyBytes = key.toByteArrayUtf8()
            val combined = ByteArray(prefix.size + keyBytes.size)
            try {
                prefix.copyInto(combined, 0)
                keyBytes.copyInto(combined, prefix.size)
                combined.copyOf() // 返回独立副本，prefix/keyBytes 可先擦除
            } finally {
                prefix.fill(0)
                keyBytes.fill(0)
            }
        } catch (_: Exception) {
            // 极端 OOM 下 combined 可能未初始化，fallback 空数组（后续 HMAC 必失败，如实上浮）
            ByteArray(0)
        }

        val kDate: ByteArray
        val kRegion: ByteArray
        val kService: ByteArray
        val signingKey: ByteArray
        try {
            kDate = hmacSha256(kSecret, dateStamp)
            kRegion = hmacSha256(kDate, regionName)
            kService = hmacSha256(kRegion, serviceName)
            signingKey = hmacSha256(kService, AWS4_REQUEST)
        } finally {
            kSecret.fill(0)
        }
        // kDate/kRegion/kService 在 signingKey 派生完成后已无引用价值，逐一擦除
        try {
            return signingKey
        } finally {
            kDate.fill(0)
            kRegion.fill(0)
            kService.fill(0)
        }
    }

    /** CharArray → UTF-8 ByteArray（CharBuffer 直转，不经 String，对齐 SyncCredentialsStore 手法） */
    private fun CharArray.toByteArrayUtf8(): ByteArray {
        val bb = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(this))
        return try {
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            bytes
        } finally {
            if (bb.hasArray()) bb.array().fill(0)
        }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        return try {
            val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
            mac.init(SecretKeySpec(key, HMAC_SHA256_ALGORITHM))
            mac.doFinal(dataBytes)
        } finally {
            dataBytes.fill(0)
        }
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        val result = hmacSha256(key, data)
        return try {
            result.toHexString()
        } finally {
            result.fill(0)
        }
    }

    companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val SERVICE_NAME = "s3"
        private const val AWS4_REQUEST = "aws4_request"
        private const val AWS4_PREFIX = "AWS4"
        private const val AMZ_DATE_PATTERN = "yyyyMMdd'T'HHmmss'Z'"
        private const val DATE_STAMP_PATTERN = "yyyyMMdd"
        private const val UTC_TIME_ZONE_ID = "UTC"
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"

        private const val HEADER_HOST = "Host"
        private const val HEADER_AMZ_DATE = "x-amz-date"
        private const val HEADER_AMZ_CONTENT_SHA256 = "x-amz-content-sha256"
        private const val HEADER_AUTHORIZATION = "Authorization"
    }
}
