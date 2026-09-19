package com.keepasskey.sync.provider

import com.keepasskey.sync.model.RemoteFileMetadata
import java.io.OutputStream

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
     * 下载远程文件流（ISSUE-P3-206 **流式契约**）。
     *
     * 内容**边读边写**进 [sink]（网络流 → 固定缓冲 → [sink]），下载期不在堆上整份物化
     * （原 `Result<ByteArray>` 契约使接受路径以 `ByteArrayOutputStream` 累积 + `toByteArray()`
     * 复制，下载期峰值 ~2×S、chunked 声明缺失可达 ~3×S）。
     * 实现内部经 `SyncDownloadLimits.copyBounded` 强制「声明尺寸预检 + 累计封顶」双重守卫
     * （ISSUE-P0-09 语义不变），超限即抛 [com.keepasskey.sync.model.SyncException.ProtocolError]
     * 并中止写入。
     *
     * 生命周期归调用方：实现只写不关 [sink]；sink 的载体（如缓存 tmp 文件）由调用方在
     * 失败 / 重放拒绝时负责清除——**不遗留半成品文件**是调用方的义务，本契约只保证
     * 失败时不再继续写入。
     */
    suspend fun download(remotePath: String, sink: OutputStream): Result<Unit>

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
     *
     * @param expectedEtag 远端目标的期望 ETag；null 表示不实施乐观锁（首传 / 强制覆盖）
     * @param remoteExists `ISSUE-P3-180`：调用方**已探明**的「远端目标是否已存在」结论。
     *   `null` 表示未知——需要该结论的实现（WebDAV 的 `Overwrite` 头）须自行探测；
     *   非 null 时实现**不得**重复探测：首传路径的调用方
     *   （`SyncCycleRunner.establishRemoteBaselineIfMissing`）已经用一次 `getMetadata`
     *   得出「不存在」，原实现会在同一路径上再探一次（整条路径多一个 RTT）。
     */
    suspend fun uploadAtomic(
        remotePath: String,
        data: ByteArray,
        expectedEtag: String? = null,
        remoteExists: Boolean? = null
    ): Result<String> = upload(remotePath, data, expectedEtag)

    /**
     * 删除远程文件
     */
    suspend fun delete(remotePath: String): Result<Unit>
}
