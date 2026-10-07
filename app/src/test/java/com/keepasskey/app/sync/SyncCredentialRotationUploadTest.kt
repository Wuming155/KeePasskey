package com.keepasskey.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncColdStartBaselineMissingTest` 同口径）。 */
private val ROTATION_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P3-528`：主凭据轮换后，下一次同步必须**重新以当前凭据序列化并上传**。
 *
 * ## 锁定的缺陷
 *
 * 换密只改文件头（KDBX4 重生成 masterSeed / IV / KDF salt），**不改条目树**；而同步的
 * 「本地内容是否变过」判据只比树（`KdbxContentComparator`），且装配期判「无变化」时
 * **直接复用旧缓存字节**当本地侧 ⇒ 换密后的同步判「与云端一致（UpToDate）」，一次上传都不发生：
 * ① 换密回执承诺的「云端旧版本副本将在下次同步后被替换」落空；② 旧主密码对云端副本长期可用。
 *
 * ## 判据
 *
 * 用例 ① 用真实 `changeCredentials` 换密 + 置标记：同步后远端字节必须变化，
 * 且**能被当前（新）凭据解开、不能再被旧凭据解开**——这是「云端副本已替换」的端到端断言；
 * 用例 ② 是同内容、无标记的反校：不得因此演化成「每次同步都重传」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCredentialRotationUploadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private data class Fixture(
        val databaseSession: DatabaseSession,
        val session: SyncSessionState,
        val cycle: SyncCycleRunner,
        val codec: SyncDatabaseCodec,
        val syncCache: SyncCache,
        val provider: CountingProvider,
        val rotationStore: SyncCredentialRotationStore,
        val remotePath: String
    )

    private fun newFixture(key: String): Fixture {
        val cacheDir = File(tempFolder.root, "$key-cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "$key-files").apply { mkdirs() }
        val prefs = InMemorySharedPreferences()
        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.prefs
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }

        val databaseSession = DatabaseSession()
        val debugLog = DebugLogBuffer()
        val session = SyncSessionState()
        val preferences = SyncPreferences(debugLog, null)
        val credentialsStore = SyncCredentialsStore(fakeContext, null)
        credentialsStore.customEncryptor = { plain -> Pair(byteArrayOf(1), plain) }
        credentialsStore.customDecryptor = { _, cipher -> cipher }
        val providerResolver = SyncProviderResolver(credentialsStore, preferences, debugLog)
        val codec = SyncDatabaseCodec(databaseSession, debugLog)
        val conflicts = SyncConflictController(databaseSession, codec, ROTATION_TEST_STRINGS, session, debugLog)
        val changes = SyncContentChangeDetector(codec, session)
        val rotationStore = SyncCredentialRotationStore(fakeContext)
        val cycle = SyncCycleRunner(
            context = fakeContext,
            databaseSession = databaseSession,
            session = session,
            providerResolver = providerResolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = ROTATION_TEST_STRINGS,
            credentialRotationStore = rotationStore
        )
        val provider = CountingProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        val syncCache = SyncCache(File(cacheDir, SyncCache.CACHE_DIR_NAME).apply { mkdirs() })
        return Fixture(databaseSession, session, cycle, codec, syncCache, provider, rotationStore, remotePath)
    }

    private suspend fun createVault(fx: Fixture, key: String) {
        val created = fx.databaseSession.create(
            file = File(tempFolder.root, "$key.kdbx"),
            name = key,
            passwordChars = VAULT_PASSWORD,
            useArgon2 = false
        )
        assertTrue("夹具建库失败: $created", created is KdbxResult.Success)
    }

    private fun uploadsOf(fx: Fixture) = fx.provider.uploadCount

    /**
     * 换密前置：先建远端基线（首传），再以**新主密码**重写本地文件——树内容不变，
     * 仅文件头凭据变化，正是本条的缺陷前提。
     */
    private suspend fun baselineThenRotateCredentials(fx: Fixture): ByteArray {
        assertTrue("首轮应首传建基线", fx.cycle.runSyncCycle() is SyncOutcome.UploadedLocal)
        val remoteBeforeRotation = fx.provider.remoteBytes(fx.remotePath).copyOf()
        val rotated = fx.databaseSession.changeCredentials(NEW_PASSWORD)
        assertTrue("夹具换密应成功: $rotated", rotated is KdbxResult.Success)
        return remoteBeforeRotation
    }

    @Test
    fun `换密置标记后下一次同步必须上传，且远端副本改由新口令可解`() = runTest(testDispatcher) {
        val fx = newFixture("rotate")
        createVault(fx, "rotate")
        val remoteBeforeRotation = baselineThenRotateCredentials(fx)
        val uploadsAtBaseline = uploadsOf(fx)

        // 断言前提：换密后的远端字节仍是**旧口令**版本（不能被当前会话凭据解开）
        assertNull(
            "前提：换密前上传到云端的字节必须已不可用当前（新）凭据解开",
            fx.codec.parseKdbxBytes(remoteBeforeRotation)
        )

        // 待替换标记置位（生产由 SettingsMasterKeyChangeController → SyncCoordinator 落位）
        fx.rotationStore.markRecrypted(fx.remotePath)

        val outcome = fx.cycle.runSyncCycle()

        assertTrue(
            "换密后的同步必须真正上传（不得判 UpToDate）: $outcome",
            uploadsOf(fx) > uploadsAtBaseline
        )
        val remoteAfter = fx.provider.remoteBytes(fx.remotePath)
        assertEquals(
            "远端字节必须已被新凭据版本替换（KDBX4 随机 IV ⇒ 字节必然不同）",
            false,
            remoteBeforeRotation.contentEquals(remoteAfter)
        )
        val parsed = fx.codec.parseKdbxBytes(remoteAfter)
        assertNotNull("云端副本必须能被当前（新）凭据解开", parsed)
        parsed?.clearSensitiveData()
        assertTrue("标记消费后必须已销（否则每轮同步都被当作有本地修改）", !fx.rotationStore.isRecrypted(fx.remotePath))
    }

    @Test
    fun `无轮换标记时同内容同步仍不上传`() = runTest(testDispatcher) {
        val fx = newFixture("norotate")
        createVault(fx, "norotate")
        val remoteBefore = baselineThenRotateCredentials(fx)
        val uploadsAtBaseline = uploadsOf(fx)

        // 反校：换密但**未置标记**（模拟整改前口径 / 非本机制触发路径）——不得因此重传
        val outcome = fx.cycle.runSyncCycle()

        assertEquals("同内容、未置标记仍须判「与云端一致」: $outcome", SyncOutcome.UpToDate, outcome)
        assertEquals("不得触发任何上传（防「每次同步都重传」的另一面回归）", uploadsAtBaseline, uploadsOf(fx))
        assertEquals(
            "远端字节必须逐字节不变",
            remoteBefore.toList(),
            fx.provider.remoteBytes(fx.remotePath).toList()
        )
    }

    @Test
    fun `轮换标记按远端路径隔离，不波及其它库`() = runTest(testDispatcher) {
        val fx = newFixture("isolated")
        createVault(fx, "isolated")
        baselineThenRotateCredentials(fx)
        val uploadsAtBaseline = uploadsOf(fx)

        fx.rotationStore.markRecrypted("/remote/另一个库.kdbx")

        val outcome = fx.cycle.runSyncCycle()

        assertEquals("他库标记不得让本库重传: $outcome", SyncOutcome.UpToDate, outcome)
        assertEquals("不得触发任何上传", uploadsAtBaseline, uploadsOf(fx))
    }

    /** 进程内假 Provider：ETag 随内容变化，支持上传计数（与 `SyncColdStartBaselineMissingTest` 同形）。 */
    private class CountingProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

        var uploadCount: Int = 0
            private set

        fun remoteBytes(remotePath: String): ByteArray = remote.getValue(remotePath).first

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val item = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("远端不存在: $remotePath"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = System.currentTimeMillis(),
                    etag = item.second
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            val item = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("远端不存在: $remotePath"))
            sink.write(item.first)
            return Result.success(Unit)
        }

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            uploadCount += 1
            val existing = remote[remotePath]
            if (expectedEtag != null && existing != null && existing.second != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing.second,
                        localExpectedEtag = expectedEtag,
                        message = "预条件失败"
                    )
                )
            }
            val etag = "etag_${data.size}_${data.fold(0) { acc, b -> (acc + b) and 0x7FFF }}"
            remote[remotePath] = Pair(data, etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }

    private companion object {
        val VAULT_PASSWORD = "Rotation#Old#1".toCharArray()
        val NEW_PASSWORD = "Rotation#New#2".toCharArray()
    }
}
