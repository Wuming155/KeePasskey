package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.sync.SyncProviderFactory
import com.keepasskey.app.sync.SyncProviderResolver
import com.keepasskey.app.sync.SyncPreferences
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** ISSUE-P2-652: 验证一次保存回执贯穿测试与同步，途中改存 B 也不得漂移。 */
class SavedSyncConfigSnapshotFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `A保存后测试挂起期间改存B仍只向A测试并同步`() = runTest {
        val filesDir = File(tempFolder.root, "files").apply { mkdirs() }
        val cacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        val preferences = InMemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences.prefs
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
            override fun getApplicationContext(): Context = this
        }
        val databaseSession = DatabaseSession()
        assertTrue(
            "测试库必须创建成功",
            databaseSession.create(
                file = File(filesDir, "snapshot-flow.kdbx"),
                name = "SnapshotFlow",
                passwordChars = "fake-vault-password".toCharArray(),
                useArgon2 = false
            ) is KdbxResult.Success
        )

        val debugLog = DebugLogBuffer()
        val credentials = SyncCredentialsStore(context, keystoreManager = null)
        credentials.customEncryptor = { plaintext ->
            Pair(byteArrayOf(1, 2, 3), plaintext.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray())
        }
        credentials.customDecryptor = { _, cipher ->
            cipher.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
        }
        val factory = SnapshotRecordingFactory()
        val resolver = SyncProviderResolver(credentials, SyncPreferences(debugLog, null), debugLog, factory)
        val coordinator = SyncCoordinator(
            context = context,
            databaseSession = databaseSession,
            syncCredentialsStore = credentials,
            debugLog = debugLog,
            strings = { id, _ -> "s$id" },
            syncProviderResolver = resolver
        )
        val controller = SettingsSyncController(
            credentials,
            coordinator,
            ExtendedSettingsStore(null),
            { id, _ -> "s$id" },
            backgroundScope
        )

        try {
            controller.setSyncProvider(CloudSyncProvider.WEBDAV)
            assertTrue(
                "保存 A 并取得回执",
                controller.updateWebDavConfig(
                    "https://dav-a.example.test/sync",
                    "fake-user-a",
                    "fake-password-a".toCharArray(),
                    "/saved-a.kdbx"
                )
            )
            val aTestEntered = CompletableDeferred<Unit>()
            val releaseATest = CompletableDeferred<Unit>()
            factory.installFirstTestGate(aTestEntered, releaseATest)

            controller.verifyConnectionThenSync()
            testScheduler.runCurrent()
            aTestEntered.await()

            assertTrue(
                "测试期间并发保存 B",
                controller.updateWebDavConfig(
                    "https://dav-b.example.test/sync",
                    "fake-user-b",
                    "fake-password-b".toCharArray(),
                    "/saved-b.kdbx"
                )
            )
            releaseATest.complete(Unit)

            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) {
                    while (controller.state.value.isSyncing) delay(1)
                }
            }

            assertEquals(
                listOf("https://dav-a.example.test/sync", "https://dav-a.example.test/sync"),
                factory.createdEndpoints
            )
            assertEquals(
                listOf("fake-password-a", "fake-password-a"),
                factory.createdPasswords.map { String(it) }
            )
            assertTrue("连接测试和同步都应有远端请求", factory.requests.any { it.operation == "test" })
            assertTrue("同步必须使用保存 A 时的对象路径", factory.requests.any { it.operation == "metadata" && it.remotePath == "/saved-a.kdbx" })
            assertTrue("保存 A 对应的同步应上传成功", factory.requests.any { it.operation == "upload" && it.remotePath == "/saved-a.kdbx" })
            assertTrue(
                "所有请求都必须绑定 A 的 endpoint / user / credential",
                factory.requests.isNotEmpty() && factory.requests.all {
                    it.endpoint == "https://dav-a.example.test/sync" &&
                        it.username == "fake-user-a" &&
                        it.password == "fake-password-a"
                }
            )
            assertFalse("迟到的 A 测试结果不得把已保存 B 标记为已验证", controller.state.value.isConnectionVerified)
            assertTrue("测试与同步 Provider 各自释放", factory.releasedProviders == 2)
        } finally {
            factory.createdPasswords.forEach { it.fill('0') }
            databaseSession.close()
        }
    }

    private class SnapshotRecordingFactory : SyncProviderFactory {
        data class Request(
            val operation: String,
            val endpoint: String,
            val username: String,
            val password: String,
            val remotePath: String
        )

        private val firstTestGateUsed = AtomicBoolean(false)
        private var enteredGate: CompletableDeferred<Unit>? = null
        private var releaseGate: CompletableDeferred<Unit>? = null
        private val remoteFiles = mutableMapOf<String, MutableMap<String, Pair<ByteArray, String>>>()

        val createdEndpoints = mutableListOf<String>()
        val createdPasswords = mutableListOf<CharArray>()
        val requests = mutableListOf<Request>()
        var releasedProviders = 0
            private set

        fun installFirstTestGate(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
            enteredGate = entered
            releaseGate = release
        }

        override fun createWebDav(
            serverUrl: String,
            username: String,
            passwordChars: CharArray,
            networkOptions: SyncNetworkOptions,
            transferOptions: SyncTransferOptions
        ): SyncProvider {
            createdEndpoints += serverUrl
            createdPasswords += passwordChars.copyOf()
            val passwordCopy = passwordChars.copyOf()
            return RecordingProvider(serverUrl, username, passwordCopy)
        }

        override fun createS3(
            endpoint: String,
            bucketName: String,
            region: String,
            accessKeyId: CharArray,
            secretAccessKey: CharArray,
            usePathStyle: Boolean,
            networkOptions: SyncNetworkOptions,
            initialClockOffsetMillis: Long,
            clockOffsetUpdater: (Long) -> Unit
        ): SyncProvider = error("This WebDAV flow must not construct an S3 provider")

        override fun releaseOwned(provider: SyncProvider) {
            releasedProviders++
            (provider as RecordingProvider).clear()
        }

        private inner class RecordingProvider(
            private val endpoint: String,
            private val username: String,
            private val password: CharArray
        ) : SyncProvider {
            private val remote = remoteFiles.getOrPut(endpoint) { mutableMapOf() }

            override suspend fun testConnection(): Result<Unit> {
                requests += Request("test", endpoint, username, String(password), "")
                if (firstTestGateUsed.compareAndSet(false, true)) {
                    enteredGate?.complete(Unit)
                    releaseGate?.await()
                }
                return Result.success(Unit)
            }

            override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
                requests += Request("metadata", endpoint, username, String(password), remotePath)
                val item = remote[remotePath]
                    ?: return Result.failure(SyncException.FileNotFound("fake missing"))
                return Result.success(
                    RemoteFileMetadata(
                        path = remotePath,
                        contentLength = item.first.size.toLong(),
                        lastModifiedMillis = 1L,
                        etag = item.second
                    )
                )
            }

            override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
                requests += Request("download", endpoint, username, String(password), remotePath)
                val item = remote[remotePath]
                    ?: return Result.failure(SyncException.FileNotFound("fake missing"))
                sink.write(item.first)
                return Result.success(Unit)
            }

            override suspend fun upload(
                remotePath: String,
                data: ByteArray,
                expectedEtag: String?
            ): Result<String> {
                requests += Request("upload", endpoint, username, String(password), remotePath)
                val etag = "fake-etag-${data.size}"
                remote[remotePath] = data.copyOf() to etag
                return Result.success(etag)
            }

            override suspend fun delete(remotePath: String): Result<Unit> {
                requests += Request("delete", endpoint, username, String(password), remotePath)
                remote.remove(remotePath)
                return Result.success(Unit)
            }

            fun clear() {
                password.fill('0')
            }
        }
    }

    private companion object {
        const val XOR_MASK = 0x5A
    }
}
