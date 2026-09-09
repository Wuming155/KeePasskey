package com.keepasskey.sync.model

/**
 * 远程云存储文件元数据
 */
data class RemoteFileMetadata(
    /**
     * 文件路径或 URL 标识
     */
    val path: String,

    /**
     * HTTP ETag 或 S3 ETag (带引号或无引号，统一处理)
     */
    val etag: String,

    /**
     * 文件大小 (字节)
     */
    val contentLength: Long,

    /**
     * 远端最后修改时间戳 (毫秒)
     */
    val lastModifiedMillis: Long,

    /**
     * 是否为目录
     */
    val isDirectory: Boolean = false
)

/**
 * 云同步异常模型分类体系
 */
sealed class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * 网络连接失败 / 超时
     */
    class NetworkError(message: String, cause: Throwable? = null) : SyncException(message, cause)

    /**
     * 鉴权失败 (HTTP 401 / 403 / S3 签名拒绝)
     */
    class AuthenticationError(message: String, cause: Throwable? = null) : SyncException(message, cause)

    /**
     * 远程文件不存在 (HTTP 404 / S3 NoSuchKey)
     */
    class FileNotFound(message: String, cause: Throwable? = null) : SyncException(message, cause)

    /**
     * 并发冲突 (HTTP 412 Precondition Failed / ETag 不匹配)
     */
    class ConflictError(
        val remoteEtag: String,
        val localExpectedEtag: String,
        message: String
    ) : SyncException(message)

    /**
     * 未知或不可恢复协议错误
     */
    class ProtocolError(val statusCode: Int, message: String) : SyncException("HTTP $statusCode: $message")

    /**
     * 端点配置非法（Wave 14 全站强制 HTTPS）：显式 http:// 端点在 Provider 构造期即拒绝，
     * 实现 fail-fast，而非在网络层以晦涩错误失败。本应用仅支持正规公网商业云服务，
     * 证书验证完全依赖系统默认 CA 链，明文 HTTP 一律不放行。
     */
    class InvalidEndpointError(message: String) : SyncException(message)

    /**
     * 本地同步缓存损坏 / 读取失败。
     *
     * Android 官方文档明确：cacheDir 在设备存储不足时会被系统自动回收
     * （优先删除较旧文件），读取前必须检查文件存在性且不得依赖缓存存放必需数据。
     * 当 isCached 与实际读取之间出现状态不一致（系统回收、并发清理、外部删除）时抛出；
     * 同步引擎遇此异常必须整体终止本次同步，绝不允许以空字节数组继续参与决策——
     * 空数组一旦命中「本地赢自动上传」路径会把远端全库覆盖为空（数据丢失级故障）。
     */
    class CacheCorruptedError(message: String) : SyncException(message)
}

/**
 * 规范化 ETag：去除前后空白、可选的弱校验前缀 `W/` 与成对包裹引号。
 * 仅做结构级剥离，不会误伤以 W、/ 等字符开头或结尾的合法不透明 ETag 值。
 */
fun cleanEtag(etag: String?): String {
    var s = etag?.trim().orEmpty()
    if (s.length >= 2 && s.startsWith("W/", ignoreCase = true)) {
        s = s.substring(2).trim()
    }
    if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
        s = s.substring(1, s.length - 1).trim()
    }
    return s
}

@JvmName("cleanEtagExtension")
fun String?.cleanEtag(): String = cleanEtag(this)

