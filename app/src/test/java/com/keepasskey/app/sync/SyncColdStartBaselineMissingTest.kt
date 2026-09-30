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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncBaseSnapshotFallbackTest` 同口径）。 */
private val COLD_START_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P2-404`：锁库清缓存（`ISSUE-P1-07`）后冷启动自动同步，本地与远端**内容一致**
 * 不得被误判为「本地有修改」而整库重传。
 *
 * ## 锁定的缺陷
 *
 * 基线与缓存双缺失时 `resolveLocalContentChanged` 的旧 `Boolean` 口径返回保守 `true`，
 * 而 `handleRemoteSynced` 的字节级短路（`remoteBytes.contentEquals(localBytes)`）在 KDBX4
 * 随机 IV 下对内容相同的两次独立序列化**恒不命中** ⇒ 每次冷启动都落入合并上传路径，
 * `autoMergeAndUpload` 无条件序列化上传并报「本地修改已上传至云端」——用户零编辑也每次触发。
 *
 * ## 判据（修复后）
 *
 * 三态化后 `BASELINE_MISSING` 只是占位：远端字节已下载在手时先做内容级比较（复用
 * `KdbxContentComparator`）——一致走「两端一致」收尾（远端字节交付缓存重建基线，零上传，
 * 用例 ① 锁定）；确有差异才落入既有合并路径（F1「已落盘未同步」的覆盖面不得回退，
 * 用例 ② 锁定）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncColdStartBaselineMissingTest {

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
        val syncCache: SyncCache,
        val provider: CountingProvider,
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
        val conflicts = SyncConflictController(databaseSession, codec, COLD_START_TEST_STRINGS, session, debugLog)
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
            strings = COLD_START_TEST_STRINGS
        )
        val provider = CountingProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        // 与 setupCycleContext 同一落点（夹具未注入 vaultBindingStore ⇒ 无库身份命名空间）
        val syncCache = SyncCache(File(cacheDir, SyncCache.CACHE_DIR_NAME).apply { mkdirs() })
        return Fixture(databaseSession, session, cycle, syncCache, provider, remotePath)
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

    private fun notesOf(db: KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.NOTES]?.readString() }
            .toSet()

    /**
     * 模拟「锁库 → 冷启动」：同步缓存全量销毁（`SyncCacheEvictor.evictAll` 同面）+
     * 进程内存基线随进程死亡丢失（`lastSyncedDb = null`）。会话树仍持有磁盘库内容
     * （生产上由解锁时重新解析磁盘文件得到，内容等价）。
     */
    private suspend fun simulateLockAndColdStart(fx: Fixture) {
        assertTrue("前提：清缓存应成功", fx.syncCache.clearAll())
        fx.session.lastSyncedDb = null
    }

    @Test
    fun `锁库清缓存后冷启动同步，本地与远端内容一致不触发上传且基线重建`() = runTest(testDispatcher) {
        val fx = newFixture("coldinsync")
        createVault(fx, "coldinsync")
        fx.databaseSession.saveEntry(sharedEntry("基线值"))

        // 第一轮：建立远端基线（首传）
        val baselineOutcome = fx.cycle.runSyncCycle()
        assertTrue("首轮应首传建基线: $baselineOutcome", baselineOutcome is SyncOutcome.UploadedLocal)
        val uploadsAtBaseline = fx.provider.uploadCount
        val remoteBefore = fx.provider.remoteBytes(fx.remotePath)
        assertTrue("远端应有基线内容", remoteBefore.isNotEmpty())

        // 锁库 → 冷启动：缓存与内存基线双消失，磁盘库内容与远端一致（无任何编辑）。
        // save() 使会话回到 OPENED（生产冷启动会话恒非 DIRTY，兜底分支的 !isDirty 前提）
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)
        simulateLockAndColdStart(fx)

        val outcome = fx.cycle.runSyncCycle()

        assertEquals(
            "内容一致必须判「两端一致」，不得误报本地修改: $outcome",
            SyncOutcome.UpToDate, outcome
        )
        assertEquals(
            "内容一致不得触发任何上传（ISSUE-P2-404 的核心断言）",
            uploadsAtBaseline, fx.provider.uploadCount
        )
        assertEquals(
            "远端字节必须逐字节不变（字节不变强于计数不变，排除任何覆盖写）",
            remoteBefore.toList(), fx.provider.remoteBytes(fx.remotePath).toList()
        )
        assertTrue(
            "远端字节应经采纳交付缓存、基线三步重建（下次同步免重复下载）",
            fx.syncCache.isCached(fx.remotePath)
        )
        assertTrue("基线应已前移", fx.session.lastSyncedDb != null)
    }

    @Test
    fun `锁库清缓存后冷启动同步，本地与远端内容确有差异仍走合并路径不丢编辑`() = runTest(testDispatcher) {
        val fx = newFixture("colddiff")
        createVault(fx, "colddiff")
        fx.databaseSession.saveEntry(sharedEntry("基线值"))

        val baselineOutcome = fx.cycle.runSyncCycle()
        assertTrue("首轮应首传建基线: $baselineOutcome", baselineOutcome is SyncOutcome.UploadedLocal)
        val uploadsAtBaseline = fx.provider.uploadCount
        val remoteBefore = fx.provider.remoteBytes(fx.remotePath)

        // 本地（磁盘库）确有未同步修改：编辑后落盘（会话回到 OPENED，非 DIRTY——
        // 这正是「已落盘未同步」的 F1 场景，绝不能被内容级兜底短路成 UpToDate）
        fx.databaseSession.saveEntry(sharedEntry("本地修改"))
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)
        simulateLockAndColdStart(fx)

        val outcome = fx.cycle.runSyncCycle()

        assertTrue(
            "内容确有差异必须走合并路径（同字段分叉交用户决策）: $outcome",
            outcome is SyncOutcome.ConflictNeedsUser
        )
        assertEquals(
            "冲突未裁决前不得把任何产物推上云端",
            uploadsAtBaseline, fx.provider.uploadCount
        )
        assertEquals(
            "远端必须仍是基线内容",
            remoteBefore.toList(), fx.provider.remoteBytes(fx.remotePath).toList()
        )
        val sessionNotes = notesOf(fx.databaseSession.databaseFlow.value)
        assertTrue("本地编辑必须仍在会话树中: $sessionNotes", "本地修改" in sessionNotes)
    }

    /** 进程内假 Provider：ETag 随内容变化，支持 out-of-band 推进远端版本 + 上传计数。 */
    private class CountingProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

        /** 上传调用计数（含首传 / 快速提交 / 合并上传全部入口） */
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
        val VAULT_PASSWORD = "ColdStart#2026".toCharArray()
        val SHARED_ID = KdbxUuid.random()
    }
}
