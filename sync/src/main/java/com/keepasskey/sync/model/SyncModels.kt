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
}
