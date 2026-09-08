package com.keepasskey.sync.provider

import com.keepasskey.sync.model.RemoteFileMetadata

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
     * 事务性原子上传：先写临时对象再原子替换目标，避免进程中断在远端留下半写文件。
     * 默认实现退化为普通 [upload]（协议不支持事务写时语义等价）；
     * 支持的协议（WebDAV PUT+MOVE、S3 条件写）应覆写本方法提供真正的原子保证。
     */
    suspend fun uploadAtomic(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String? = null
    ): Result<String> = upload(remotePath, data, expectedEtag)

    /**
     * 删除远程文件
     */
    suspend fun delete(remotePath: String): Result<Unit>
}
