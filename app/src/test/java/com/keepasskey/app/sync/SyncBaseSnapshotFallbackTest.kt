package com.keepasskey.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncMidCycleEditGuardTest` 同口径）。 */
private val FALLBACK_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P2-309`：三方合并的 base **绝不可**由工作副本（`<hash>.cache`）兜底。
 *
 * ## 锁定的缺陷
 *
 * `SyncCycleSetup.buildCycleContext` 原先写作 `readBaseContent(path) ?: cachedSnapshotBytes`，
 * 与紧邻注释「本地缓存会被工作副本反复覆盖，绝不能再兼任 base 内容来源」直接矛盾。
 * `.basecache` 被 cacheDir 回收而本地确有编辑时，共同祖先被**上一版本地内容**顶替 ⇒
 * 该编辑在合并器眼里成为「local == base，本地未改」⇒ 同字段分叉判远端全胜 ⇒ 本地编辑静默丢弃。
 *
 * `resolveTrustedBase` 的字节级污染判据**救不了**这条：KDBX4 每次保存重生成 masterSeed / IV /
 * KDF salt ⇒ 同一内容的两次序列化字节必然不同，`contentEquals` 无从命中。
 *
 * ## 判据（修复后）
 *
 * base 缺失即以空库充当（双方并集 + 同字段分叉进冲突清单交用户决策），宁多冲突不丢数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncBaseSnapshotFallbackTest {

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
        val cycle: SyncCycleRunner,
        val codec: SyncDatabaseCodec,
        val syncCache: SyncCache,
        val provider: InProcessProvider,
        val remotePath: String,
        val cacheDir: File
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
        val conflicts = SyncConflictController(databaseSession, codec, FALLBACK_TEST_STRINGS, session, debugLog)
        val changes = SyncContentChangeDetector(codec, session)
        val cycle = SyncCycleRunner(
            context = fakeContext,
            databaseSession = databaseSession,
            session = session,
            providerResolver = providerResolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = FALLBACK_TEST_STRINGS
        )
        val provider = InProcessProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        // 与 setupCycleContext 同一落点（夹具未注入 vaultBindingStore ⇒ 无库身份命名空间）
        val syncCache = SyncCache(File(cacheDir, SyncCache.CACHE_DIR_NAME).apply { mkdirs() })
        return Fixture(databaseSession, cycle, codec, syncCache, provider, remotePath, cacheDir)
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

    private fun sharedEntry(notes: String) = KdbxEntry(
        id = SHARED_ID,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("共享条目", false),
            KdbxConstants.Fields.NOTES to ProtectedString(notes, false)
        )
    )

    /** 整库备注替换（用于构造「对端独立推进」的远端版本，条目身份不变）。 */
    private fun KdbxDatabase.withNotes(notes: String): KdbxDatabase = copy(
        rootGroup = rootGroup.copy(
            entries = rootGroup.entries.map { entry ->
                if (entry.id == SHARED_ID) sharedEntry(notes) else entry
            }
        )
    )

    private fun notesOf(db: KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.NOTES]?.readString() }
            .toSet()

    /** 删除 `.basecache`（模拟 cacheDir 回收）；返回确实删掉了一份。 */
    private fun deleteBasecache(fx: Fixture): Boolean =
        fx.cacheDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".basecache") }
            .toList()
            .also { assertEquals("前提：应恰好存在一份 basecache", 1, it.size) }
            .all { it.delete() }

    @Test
    fun `basecache 缺失时不得用工作副本充当合并基准，本地编辑不被静默丢弃`() = runTest(testDispatcher) {
        val fx = newFixture("basefallback")
        createVault(fx, "basefallback")
        fx.databaseSession.saveEntry(sharedEntry("基线值"))

        // 第一轮：远端 / 工作副本 / basecache 三者同建于基线
        val baselineOutcome = fx.cycle.runSyncCycle()
        assertTrue("首轮应建立远端基线: $baselineOutcome", baselineOutcome is SyncOutcome.UploadedLocal)
        val baseline = fx.databaseSession.databaseFlow.value!!

        // ① 本地把「基线值」改成「本地修改」并保持 DIRTY（未落盘）
        fx.databaseSession.saveEntry(sharedEntry("本地修改"))
        val localBytes = fx.codec.serializeLocalDatabase(fx.databaseSession.databaseFlow.value!!)!!
        // ② 工作副本已被本轮本地内容覆盖（生产上由装配段 writeCache 完成），basecache 仍停在基线
        fx.syncCache.writeCache(fx.remotePath, localBytes)
        // ③ 对端自基线起独立推进为「远端修改」
        val remoteBytes = fx.codec.serializeLocalDatabase(baseline.withNotes("远端修改"))!!
        assertTrue("夹具推进远端失败", fx.provider.upload(fx.remotePath, remoteBytes, null).isSuccess)
        // ④ `.basecache` 被系统回收——唯一缺失的基准来源
        assertTrue("夹具未造出 basecache，本用例不构成证据", deleteBasecache(fx))

        val outcome = fx.cycle.runSyncCycle()

        assertTrue(
            "base 缺失必须退化为「空库并集 + 同字段分叉交用户决策」，不得自动合并判远端全胜: $outcome",
            outcome is SyncOutcome.ConflictNeedsUser
        )
        val sessionNotes = notesOf(fx.databaseSession.databaseFlow.value)
        assertTrue("本地编辑必须仍在会话树中: $sessionNotes", "本地修改" in sessionNotes)
        assertArrayEquals(
            "冲突未裁决前不得把任何合并产物推上云端",
            remoteBytes,
            fx.provider.remoteBytes(fx.remotePath)
        )
    }

    /** 进程内假 Provider：ETag 随内容变化，支持 out-of-band 推进远端版本。 */
    private class InProcessProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

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
        val VAULT_PASSWORD = "BaseFallback#2026".toCharArray()
        val SHARED_ID = KdbxUuid.random()
    }
}
