package com.keepasskey.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.provider.SyncProvider
import dagger.hilt.android.EntryPointAccessors
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁态周期探测失败的可见性（`ISSUE-P2` A7 设备侧验收）。
 *
 * 链路：401 → `AuthenticationRequired` → `runPeriodicSync` 写入 `lastOutcome = Error`
 * （`SyncFailureNotifier` 据此亮通知）→ 归档出现「锁态探测失败」；探测恢复后结论回到
 * 非 Error（通知撤销）。通知渲染本身由 `SyncFailureSignalHandler` 的 JVM 用例覆盖。
 */
@RunWith(AndroidJUnit4::class)
class PeriodicSyncProbeFailureDeviceTest {

    @Test
    fun `锁态探测401写入可通知结论与归档且恢复后撤销`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(
            context,
            PeriodicSyncWorker.PeriodicSyncEntryPoint::class.java
        )
        val coordinator = entry.syncCoordinator()
        val databaseSession = entry.databaseSession()
        val activeIdStore = ActiveDatabaseIdStore(context)
        val previousActiveId = activeIdStore.load()

        databaseSession.lock()
        activeIdStore.save(TEST_ACTIVE_ID)
        coordinator.testRemotePath = REMOTE_PATH
        try {
            coordinator.testSyncProvider = AuthFailingProvider()
            val failure = coordinator.runPeriodicSync()

            assertTrue("401 探测须归类为鉴权失败：$failure", failure is PeriodicSyncWorkResult.AuthenticationRequired)
            assertTrue(
                "通知所订阅的结论须为 Error：${coordinator.lastOutcome.value}",
                coordinator.lastOutcome.value is SyncOutcome.Error
            )
            val archived = DebugLogBuffer().exportSanitizedText()
            assertTrue(
                "归档须有可检索的探测失败行：$archived",
                archived.contains("锁态探测失败: AuthenticationRequired")
            )

            coordinator.testSyncProvider = MetadataOnlyProvider()
            val recovered = coordinator.runPeriodicSync()
            assertTrue("探测恢复须回到非失败结论：$recovered", recovered is PeriodicSyncWorkResult.NeedsUnlock)
            assertTrue(
                "恢复后通知所订阅的结论不得仍是 Error：${coordinator.lastOutcome.value}",
                coordinator.lastOutcome.value !is SyncOutcome.Error
            )
        } finally {
            coordinator.testSyncProvider = null
            coordinator.testRemotePath = null
            activeIdStore.save(previousActiveId)
            databaseSession.lock()
        }
    }

    /** 元数据请求返回 401：只覆写探测真正调用的入口，其余操作一律失败。 */
    private class AuthFailingProvider : SyncProvider {
        override suspend fun testConnection(): Result<Unit> =
            Result.failure(SyncException.AuthenticationError("401"))

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.failure(SyncException.AuthenticationError("401"))

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(SyncException.AuthenticationError("401"))

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> = Result.failure(SyncException.AuthenticationError("401"))

        override suspend fun delete(remotePath: String): Result<Unit> =
            Result.failure(SyncException.AuthenticationError("401"))
    }

    /** 恢复场景：元数据可达但无基线（首次观测），结论应为「待解锁」而非失败。 */
    private class MetadataOnlyProvider : SyncProvider {
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = 0L,
                    lastModifiedMillis = 0L,
                    etag = "etag-recovered"
                )
            )

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(SyncException.ProtocolError(501, "not supported"))

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> = Result.failure(SyncException.ProtocolError(501, "not supported"))

        override suspend fun delete(remotePath: String): Result<Unit> =
            Result.failure(SyncException.ProtocolError(501, "not supported"))
    }

    private companion object {
        const val TEST_ACTIVE_ID = "content://keepasskey.test/periodic-probe"
        const val REMOTE_PATH = "/periodic/probe.kdbx"
    }
}
