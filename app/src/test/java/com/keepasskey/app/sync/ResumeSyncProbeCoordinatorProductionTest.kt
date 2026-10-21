package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** ISSUE-P3-628：验证恢复探测从持久云配置进入生产 Provider resolver 的拥有式作用域。 */
class ResumeSyncProbeCoordinatorProductionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `网络恢复探测使用持久配置Provider并提示ETag变化`() = runTest {
        val fixture = Fixture()
        fixture.initializeVault()
        fixture.provider.metadata = { path ->
            Result.success(RemoteFileMetadata(path, "etag-new", 0L, 0L))
        }
        val target = requireNotNull(fixture.coordinator.resolveProbeTarget())
        fixture.coordinator.rememberRemoteEtagForProbe("etag-old", target.identity)

        fixture.triggerProbe()

        assertEquals("remote-changed", fixture.awaitNotice())
        assertNull("探测必须不依赖测试 Provider 覆写", fixture.coordinator.testSyncProvider)
        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.provider.metadataCalls)
        assertEquals("vault/object.kdbx", fixture.provider.requestedPaths.single())
        assertEquals(1, fixture.factory.releaseCalls)
        assertTrue("生产配置凭据克隆应在 Provider 释放后清零", fixture.factory.accessKeyClone!!.all { it == '0' })
        assertTrue(fixture.factory.secretKeyClone!!.all { it == '0' })
    }

    @Test
    fun `目标切换后丢弃在途元数据结果并释放Provider`() = runTest {
        val fixture = Fixture()
        fixture.initializeVault()
        val started = CompletableDeferred<Unit>()
        val finishMetadata = CompletableDeferred<Result<RemoteFileMetadata>>()
        fixture.provider.metadata = {
            started.complete(Unit)
            finishMetadata.await()
        }

        fixture.triggerProbe()
        // 探测走真实 Dispatchers.IO（生产协程边界），虚拟时间无法推进它 ⇒ 必须在真实线程上等待。
        withContext(Dispatchers.Default) {
            withTimeout(WAIT_MILLIS) { started.await() }
        }
        fixture.saveS3(objectKey = "vault/another.kdbx")
        finishMetadata.complete(Result.success(RemoteFileMetadata("vault/object.kdbx", "etag-late", 0L, 0L)))

        fixture.awaitCompletion()
        assertNull("切换目标后旧请求结果不得产生提示", fixture.probe.notice.value)
        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.factory.releaseCalls)
        assertEquals(1, fixture.provider.metadataCalls)
    }

    @Test
    fun `生产Provider元数据失败按异常类型分类`() = runTest {
        val fixture = Fixture()
        fixture.initializeVault()
        fixture.provider.metadata = { Result.failure(IllegalStateException("synthetic endpoint detail")) }

        fixture.triggerProbe()

        assertEquals("failed:IllegalStateException", fixture.awaitNotice())
        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.factory.releaseCalls)
        assertEquals(1, fixture.provider.metadataCalls)
    }

    private inner class Fixture {
        private val prefs = InMemorySharedPreferences()
        private val cacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        private val filesDir = File(tempFolder.root, "files").apply { mkdirs() }
        val context: Context = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = prefs.prefs
        }
        val settings = FakeSettingsRepository()
        val debugLog = DebugLogBuffer()
        val databaseSession = DatabaseSession()
        val credentials = SyncCredentialsStore(context, keystoreManager = null)
        val factory = RecordingFactory()
        val provider = factory.provider
        private val resolver: SyncProviderResolver
        val coordinator: SyncCoordinator
        val probe: ResumeSyncProbeCoordinator

        init {
            credentials.customEncryptor = { plaintext ->
                plaintext.clone().let { clear ->
                    Pair(byteArrayOf(1, 2, 3), clear.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray())
                }
            }
            credentials.customDecryptor = { _, ciphertext ->
                ciphertext.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
            }
            saveS3()
            resolver = SyncProviderResolver(credentials, SyncPreferences(debugLog, null), debugLog, factory)
            coordinator = SyncCoordinator(
                context,
                databaseSession,
                credentials,
                debugLog,
                STRINGS,
                syncProviderResolver = resolver
            )
            val lockGuard = AutoLockSessionGuard(databaseSession, settings, debugLog)
            val lockManager = AutoLockManager(context, lockGuard, settings)
            probe = ResumeSyncProbeCoordinator(context, settings, coordinator, lockManager, STRINGS)
        }

        suspend fun initializeVault() {
            val createResult = databaseSession.create(
                file = File(filesDir, "resume-probe-vault.kdbx"),
                name = "ResumeProbeVault",
                passwordChars = "fake-test-password".toCharArray(),
                useArgon2 = false
            )
            check(createResult is KdbxResult.Success) { "test database setup failed: $createResult" }
        }

        fun saveS3(objectKey: String = "vault/object.kdbx") {
            check(
                credentials.saveS3Config(
                    endpoint = "https://s3.example.test",
                    bucket = "bucket",
                    region = "auto",
                    accessKey = "fake-access".toCharArray(),
                    secretKey = "fake-secret".toCharArray(),
                    objectKey = objectKey
                )
            )
            credentials.saveProvider(CloudSyncProvider.S3_COMPATIBLE)
        }

        fun triggerProbe() {
            check(coordinator.testSyncProvider == null)
            probe.onNetworkAvailable()
        }

        suspend fun awaitNotice(): String = withContext(Dispatchers.Default) {
            withTimeout(WAIT_MILLIS) { probe.notice.first { it != null }!! }
        }

        suspend fun awaitCompletion() {
            withContext(Dispatchers.Default) {
                withTimeout(WAIT_MILLIS) {
                    while (factory.releaseCalls == 0) kotlinx.coroutines.delay(POLL_MILLIS)
                }
            }
        }
    }

    private class RecordingFactory : SyncProviderFactory {
        val provider = RecordingProvider()
        var createCalls = 0
        var releaseCalls = 0
        var accessKeyClone: CharArray? = null
        var secretKeyClone: CharArray? = null

        override fun createWebDav(
            serverUrl: String,
            username: String,
            passwordChars: CharArray,
            networkOptions: SyncNetworkOptions,
            transferOptions: SyncTransferOptions
        ): SyncProvider = error("unexpected WebDAV provider construction")

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
        ): SyncProvider {
            createCalls++
            accessKeyClone = accessKeyId
            secretKeyClone = secretAccessKey
            return provider
        }

        override fun releaseOwned(provider: SyncProvider) {
            releaseCalls++
            accessKeyClone?.fill('0')
            secretKeyClone?.fill('0')
        }
    }

    private class RecordingProvider : SyncProvider {
        var metadataCalls = 0
        val requestedPaths = mutableListOf<String>()
        var metadata: suspend (String) -> Result<RemoteFileMetadata> = {
            Result.success(RemoteFileMetadata(it, "etag-default", 0L, 0L))
        }

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            metadataCalls++
            requestedPaths += remotePath
            return metadata(remotePath)
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> =
            Result.failure(UnsupportedOperationException())

        override suspend fun delete(remotePath: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    }

    private companion object {
        const val XOR_MASK = 0x5A
        const val WAIT_MILLIS = 3_000L
        const val POLL_MILLIS = 5L
        val STRINGS = StringsProvider { id, args ->
            when (id) {
                com.keepasskey.app.R.string.sync_probe_remote_changed -> "remote-changed"
                com.keepasskey.app.R.string.sync_probe_unchanged -> "unchanged"
                com.keepasskey.app.R.string.sync_probe_failed -> "failed:${args.singleOrNull()}"
                else -> "string:$id"
            }
        }
    }
}
