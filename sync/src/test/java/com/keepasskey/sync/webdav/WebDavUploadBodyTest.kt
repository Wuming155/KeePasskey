package com.keepasskey.sync.webdav

import com.keepasskey.sync.network.SyncTransferOptions
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ISSUE-P3-03 (43a)：WebDAV PUT 请求体形态选择单测。
 *
 * 验收要点：
 * 1. 分块开关关闭（默认）→ 定长单次写出，`Content-Length` 明确（接线前行为零变更）；
 * 2. 数据量不超过单块阈值 → 即便开关开启也保持定长写出（不为小文件退化协议形态）；
 * 3. 开关开启且数据量超阈值 → 长度未知（`Transfer-Encoding: chunked`）；
 * 4. 分块写出内容必须与原始字节逐字节一致（不得因分块丢失或重排）。
 */
class WebDavUploadBodyTest {

    /** 最小合法分块（1 MiB）——构造期闸门保证不会出现更小值 */
    private val minChunkBytes = SyncTransferOptions.MIN_CHUNK_SIZE_BYTES

    private val chunkedOn = SyncTransferOptions(
        chunkedUploadEnabled = true,
        chunkSizeBytes = minChunkBytes
    )

    private val chunkedOff = SyncTransferOptions(
        chunkedUploadEnabled = false,
        chunkSizeBytes = minChunkBytes
    )

    @Test
    fun `分块关闭时使用定长请求体`() {
        val data = ByteArray(64) { it.toByte() }

        val body = WebDavUploadBody.create(data, chunkedOff)

        assertEquals(64L, body.contentLength())
        assertNotNull(body.contentType())
        assertEquals("application/octet-stream", body.contentType().toString())
    }

    @Test
    fun `数据量不超过单块阈值时保持定长请求体`() {
        val data = ByteArray(minChunkBytes.toInt())

        val body = WebDavUploadBody.create(data, chunkedOn)

        assertEquals("不大于阈值不得切换协议形态", minChunkBytes, body.contentLength())
    }

    @Test
    fun `超过单块阈值时切换为分块流式请求体`() {
        val data = ByteArray(minChunkBytes.toInt() + 1)

        val body = WebDavUploadBody.create(data, chunkedOn)

        assertEquals("长度未知即 Transfer-Encoding chunked", -1L, body.contentLength())
    }

    @Test
    fun `分块写出内容与原始字节逐字节一致`() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        // 直接构造流式体以使用小窗口验证逐窗口写出（工厂侧受 1 MiB 最小块闸门约束）
        val body = ChunkedStreamingBody(data, 7L, "application/octet-stream".toMediaType())

        assertEquals(-1L, body.contentLength())
        val buffer = Buffer()
        body.writeTo(buffer)

        assertArrayEquals(data, buffer.readByteArray())
    }

    @Test
    fun `分块写出在窗口大于数据量时同样完整`() {
        val data = "tiny".toByteArray()
        val body = ChunkedStreamingBody(data, 4096L, "application/octet-stream".toMediaType())

        val buffer = Buffer()
        body.writeTo(buffer)

        assertArrayEquals(data, buffer.readByteArray())
    }

    @Test
    fun `空数据在分块关闭时定长写出零长度`() {
        val body = WebDavUploadBody.create(ByteArray(0), chunkedOff)

        assertEquals(0L, body.contentLength())
    }
}
