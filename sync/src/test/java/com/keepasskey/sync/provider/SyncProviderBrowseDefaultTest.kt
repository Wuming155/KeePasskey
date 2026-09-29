package com.keepasskey.sync.provider

import com.keepasskey.sync.model.RemoteListPage
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-387：SyncProvider 默认浏览成员 fail-closed。
 *
 * 未覆写 [SyncProvider.listRemoteDirectory] 的协议必须返回失败，
 * **不得**返回空列表成功（与「真实空目录」不可区分）。
 */
class SyncProviderBrowseDefaultTest {

    private class NoBrowseProvider : SyncProvider {
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.success(
                RemoteFileMetadata(remotePath, "", 0, 0)
            )
        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.success(Unit)
        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> =
            Result.success("e")
        override suspend fun delete(remotePath: String): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun `未实现浏览的协议返回失败而非空页`() = runTest {
        val result = NoBrowseProvider().listRemoteDirectory("/")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncException.ProtocolError)
    }

    @Test
    fun `默认页大小常量非空且有界`() {
        assertTrue(SyncProvider.DEFAULT_REMOTE_PAGE_SIZE in 1..1000)
    }
}
