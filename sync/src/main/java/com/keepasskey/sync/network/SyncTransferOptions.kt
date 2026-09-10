package com.keepasskey.sync.network

/**
 * 同步传输选项（ISSUE-P3-03 43a 接线：`webdavChunkedUpload` / `webdavChunkSizeMb`）。
 *
 * 设计约束（依赖倒置）：`sync` 模块**不感知** app 层的偏好对象（`ExtendedSettings` 等），
 * 传输语义由 app 层归一到本纯数据契约后经构造参数传入，维持 `app → sync → core` 单向依赖。
 *
 * 分块语义（对齐 keepass2android `WebDavStorage` 的 `setChunkedStreamingMode(chunkSize)`）：
 * 仅在请求体大于单块阈值时启用 **HTTP 分块传输编码**（`Transfer-Encoding: chunked`，
 * 无 `Content-Length`），每个窗口写出后立即 flush，形成独立 HTTP chunk；
 * 数据量不超过单块阈值或开关关闭时保持单次定长写法（与接线前行为逐字节一致）。
 * 已知限界：本项只影响**上传**；下载分块由服务端响应决定，客户端无对应开关。
 */
data class SyncTransferOptions(
    /** 是否启用分块流式上传 */
    val chunkedUploadEnabled: Boolean = DEFAULT_CHUNKED_UPLOAD_ENABLED,
    /** 单块字节数（恒在 [MIN_CHUNK_SIZE_BYTES]..[MAX_CHUNK_SIZE_BYTES] 闭区间内） */
    val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_MB * BYTES_PER_MIB
) {
    init {
        require(chunkSizeBytes in MIN_CHUNK_SIZE_BYTES..MAX_CHUNK_SIZE_BYTES) {
            "分块大小越界: $chunkSizeBytes 字节（合法区间 " +
                "$MIN_CHUNK_SIZE_BYTES..$MAX_CHUNK_SIZE_BYTES）"
        }
    }

    companion object {
        const val BYTES_PER_MIB = 1024L * 1024L

        /** 合法分块大小区间（MB）。下界 1 MiB 保证 chunk 化收益，上界 512 MiB 防误填溢出 */
        const val MIN_CHUNK_SIZE_MB = 1
        const val MAX_CHUNK_SIZE_MB = 512

        const val MIN_CHUNK_SIZE_BYTES = MIN_CHUNK_SIZE_MB * BYTES_PER_MIB
        const val MAX_CHUNK_SIZE_BYTES = MAX_CHUNK_SIZE_MB * BYTES_PER_MIB

        /** 默认分块大小（与设置页默认值 10 MB 同源） */
        const val DEFAULT_CHUNK_SIZE_MB = 10

        /** 分块上传默认关闭：仅用户显式开启后改变传输形态 */
        const val DEFAULT_CHUNKED_UPLOAD_ENABLED = false

        /** 关闭分块传输、仅承载分块大小的选项（S3 等不使用分块流的协议） */
        val DISABLED: SyncTransferOptions = SyncTransferOptions()

        /**
         * 把用户填写的 MB 值归一到合法区间。
         * 偏好来自 UI 输入，越界值只做**钳制**而不抛异常——传输参数不应让同步周期崩溃。
         */
        fun clampChunkSizeMb(sizeMb: Int): Int =
            sizeMb.coerceIn(MIN_CHUNK_SIZE_MB, MAX_CHUNK_SIZE_MB)

        /** 由 app 层偏好组装传输选项（钳制 + 单位换算的唯一入口，杜绝魔法数字外溢） */
        fun fromPreferences(chunkedUploadEnabled: Boolean, chunkSizeMb: Int): SyncTransferOptions =
            SyncTransferOptions(
                chunkedUploadEnabled = chunkedUploadEnabled,
                chunkSizeBytes = clampChunkSizeMb(chunkSizeMb) * BYTES_PER_MIB
            )
    }
}
