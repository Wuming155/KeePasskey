package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncCacheEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存监督事件的日志描述不得带出远端路径原文（目录、库名、主机）。
 *
 * 导出日志是用户可外发的取证材料；远端路径只允许以稳定短摘要出现，用于同一目标的关联。
 */
class SyncCacheEventDescriberTest {

    private val remotePath = "/work/dept/vault.kdbx"

    private val events = listOf(
        SyncCacheEvent.UpdatedCachedFileOnLoad(remotePath),
        SyncCacheEvent.UpdatedRemoteFileOnLoad(remotePath),
        SyncCacheEvent.OpenedFromLocalDueToConflict(remotePath),
        SyncCacheEvent.LoadedFromRemoteInSync(remotePath),
        SyncCacheEvent.CouldntSaveToRemote(remotePath, cause = null),
        SyncCacheEvent.CouldntOpenFromRemote(remotePath, cause = null)
    )

    @Test
    fun `普通与详细描述都不输出远端路径原文`() {
        events.forEach { event ->
            val text = SyncCacheEventDescriber.describe(event) +
                SyncCacheEventDescriber.describeVerbose(event)

            assertFalse("不得残留目录名：$text", text.contains("/work"))
            assertFalse("不得残留目录名：$text", text.contains("dept"))
            assertFalse("不得残留库名：$text", text.contains("vault.kdbx"))
            assertTrue("须保留可关联的路径摘要：$text", text.contains("path="))
        }
    }

    @Test
    fun `路径摘要对同一目标稳定且可区分不同目标`() {
        val digest = RemotePathDigest.of(remotePath)

        assertEquals("同一路径的摘要必须稳定", digest, RemotePathDigest.of(remotePath))
        assertNotEquals("不同路径应可区分", digest, RemotePathDigest.of("/work/dept/other.kdbx"))
        assertEquals("摘要保持 8 字节十六进制", 16, digest.length)
    }
}
