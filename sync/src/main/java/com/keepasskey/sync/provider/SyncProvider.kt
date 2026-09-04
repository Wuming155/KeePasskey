package com.keepasskey.sync.provider

import com.keepasskey.sync.model.RemoteFileMetadata
import java.io.InputStream

/**
 * 通用云存储协议提供者契约接口。
 * 遵循本地优先 (Local-First) 与乐观并发控制 (Optimistic Concurrency Control)：
 * 支持 WebDAV、AWS S3 兼容对象存储等协议。
 */
interface SyncProvider {

    /**
     * 测试并建立远程服务器连接
     */
    suspend fun testConnection(): Result<Unit>

    /**
     * 获取指定远程文件的元数据（包含 ETag、大小与最后修改时间）
     */
    suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata>

    /**
     * 下载远程文件流
     */
    suspend fun download(remotePath: String): Result<ByteArray>

    /**
     * 上传本地数据库文件至远程。
     * @param remotePath 远程文件相对或绝对路径
     * @param data 文件二进制内容
     * @param expectedEtag 上次同步记录的期望 ETag；若远端已发生他人修改，触发 412 ConflictError 乐观锁保护
     * @return 新上传成功后远端返回的最新 ETag
     */
    suspend fun upload(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String? = null
    ): Result<String>

    /**
     * 删除远程文件
     */
    suspend fun delete(remotePath: String): Result<Unit>
}
