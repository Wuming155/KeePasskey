package com.keepasskey.sync.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-03 (43a)：分块传输选项（`webdavChunkedUpload` / `webdavChunkSizeMb` 的 sync 域契约）单测。
 *
 * 覆盖：MB → 字节换算、越界钳制、非法构造拒绝、默认值语义。
 */
class SyncTransferOptionsTest {

    @Test
    fun `默认关闭分块且块大小为十兆字节`() {
        val options = SyncTransferOptions()

        assertFalse(options.chunkedUploadEnabled)
        assertEquals(
            SyncTransferOptions.DEFAULT_CHUNK_SIZE_MB * SyncTransferOptions.BYTES_PER_MIB,
            options.chunkSizeBytes
        )
    }

    @Test
    fun `偏好映射按兆字节换算为字节`() {
        val options = SyncTransferOptions.fromPreferences(chunkedUploadEnabled = true, chunkSizeMb = 32)

        assertTrue(options.chunkedUploadEnabled)
        assertEquals(32L * SyncTransferOptions.BYTES_PER_MIB, options.chunkSizeBytes)
    }

    @Test
    fun `越界块大小被钳制到合法区间而不抛异常`() {
        assertEquals(
            SyncTransferOptions.MIN_CHUNK_SIZE_MB,
            SyncTransferOptions.clampChunkSizeMb(0)
        )
        assertEquals(
            SyncTransferOptions.MIN_CHUNK_SIZE_MB,
            SyncTransferOptions.clampChunkSizeMb(-5)
        )
        assertEquals(
            SyncTransferOptions.MAX_CHUNK_SIZE_MB,
            SyncTransferOptions.clampChunkSizeMb(Int.MAX_VALUE)
        )
        // 钳制后的偏好仍可安全构造（UI 误输入不得让同步周期崩溃）
        val clamped = SyncTransferOptions.fromPreferences(
            chunkedUploadEnabled = true,
            chunkSizeMb = Int.MAX_VALUE
        )
        assertEquals(SyncTransferOptions.MAX_CHUNK_SIZE_BYTES, clamped.chunkSizeBytes)
    }

    @Test
    fun `构造期拒绝越界字节数`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncTransferOptions(chunkedUploadEnabled = true, chunkSizeBytes = 0L)
        }
    }

    @Test
    fun `DISABLED 常量等价于默认关闭且不启用分块`() {
        assertFalse(SyncTransferOptions.DISABLED.chunkedUploadEnabled)
        assertEquals(
            SyncTransferOptions.DEFAULT_CHUNK_SIZE_MB * SyncTransferOptions.BYTES_PER_MIB,
            SyncTransferOptions.DISABLED.chunkSizeBytes
        )
    }
}
