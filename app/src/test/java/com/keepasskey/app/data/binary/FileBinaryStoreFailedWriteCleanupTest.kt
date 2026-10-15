package com.keepasskey.app.data.binary

import com.keepasskey.sync.engine.SyncCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** ISSUE-P3-622：key 尚未从 storeFromStream 交付时发生失败，FileBinaryStore 自行回收已提交文件。 */
class FileBinaryStoreFailedWriteCleanupTest {

    private class CommitThenFailSyncCache(cacheDir: File) : SyncCache(cacheDir) {
        var failAfterCommit = true

        override fun writeCacheStreaming(remotePath: String, input: java.io.InputStream, size: Long): String {
            val digest = super.writeCacheStreaming(remotePath, input, size)
            if (failAfterCommit) throw IOException("synthetic failure after cache commit")
            return digest
        }
    }

    @Test
    fun `流式写入在返回key前失败时删除已提交文件`() {
        val root = Files.createTempDirectory("p3-622-file-binary-store").toFile()
        try {
            val cacheDir = File(root, "attachments")
            val syncCache = CommitThenFailSyncCache(cacheDir)
            val store = FileBinaryStore(syncCache, Unit)
            val payload = ByteArray(4096) { (it % 239).toByte() }

            val failure = runCatching {
                store.storeFromStream(ByteArrayInputStream(payload), payload.size.toLong())
            }.exceptionOrNull()

            assertTrue("原始写入异常必须继续向上传播", failure is IOException)
            assertTrue("无 key 交付时不得留 cache 文件", cacheDir.listFiles().orEmpty().isEmpty())

            syncCache.failAfterCommit = false
            val key = store.storeFromStream(ByteArrayInputStream(payload), payload.size.toLong())
            assertEquals(payload.size.toLong(), store.sizeOf(key))
            assertArrayEquals(payload, store.load(key))
            store.delete(key)
            assertEquals(0L, store.sizeOf(key))
        } finally {
            root.deleteRecursively()
        }
    }
}
