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
import com.keepasskey.sync.engine.SyncCacheEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncColdStartBaselineMissingTest` 同口径）。 */
private val REMOTE_DIFF_STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P2-536`：§372（`ISSUE-P2-404`）只补上了占位态（BASELINE_MISSING）下「两端一致」
 * 那一半；**差异全在远端一侧**（他端改了云端、本地一行未改）时，占位投影出的
 * `hasLocalContentChanged = true` 仍被当作「本地已修改」的证据送进合并上传
 * ⇒ 用户零编辑却看到「本地修改已上传至云端」并整库重传（真机间歇复现的根因）。
 *
 * ## 判据
 *
 * 进程内检查点 `SyncLocalFileCheckpoint`（本地库文件字节 SHA-256，成功周期收尾记录）：
 * 占位态且「本地库文件自上次成功同步以来未被重写」⇒ 差异只能来自远端 ⇒ 走既有
 * **远端接管**尾段（零上传 + `UpdatedCachedFileOnLoad` 诚实文案）；本地确有改动
 * （`isDirty` 或文件已被重写）⇒ 维持既有合并路径，F1 两类覆盖面不回退。
 *
 * ## 与缓存存活路径的关系（为何本机制只补占位态）
 *
 * 进程被杀（无锁回调）时缓存存活，`resolveLocalContentChanged` 比对缓存快照即可确证
 * `UNCHANGED`，远端更新本就走远端接管——锁库清缓存（`ISSUE-P1-07`）把这份证据也销毁了，
 * 本检查点即补回它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncColdStartRemoteOnlyDifferenceTest {

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
        val provider: RemoteChangeProbeProvider,
        val remotePath: String,
        val vaultFile: File,
        val checkpoint: SyncLocalFileCheckpoint
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
        val conflicts = SyncConflictController(databaseSession, codec, REMOTE_DIFF_STRINGS, session, debugLog)
        val changes = SyncContentChangeDetector(codec, session)
        val checkpoint = SyncLocalFileCheckpoint()
        val cycle = SyncCycleRunner(
            context = fakeContext,
            databaseSession = databaseSession,
            session = session,
            providerResolver = providerResolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = REMOTE_DIFF_STRINGS,
            localFileCheckpoint = checkpoint
        )
        val provider = RemoteChangeProbeProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        val syncCache = SyncCache(File(cacheDir, SyncCache.CACHE_DIR_NAME).apply { mkdirs() })
        return Fixture(
            databaseSession, session, cycle, syncCache, provider, remotePath,
            File(tempFolder.root, "$key.kdbx"), checkpoint
        )
    }

    private suspend fun createVault(fx: Fixture, key: String) {
        val created = fx.databaseSession.create(
            file = fx.vaultFile,
            name = key,
            passwordChars = PROBE_PASSWORD,
            useArgon2 = false
        )
        assertTrue("夹具建库失败: $created", created is KdbxResult.Success)
    }

    private fun entry(id: KdbxUuid, notes: String) = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("共享条目", isProtected = false),
            KdbxConstants.Fields.NOTES to ProtectedString(notes, isProtected = false)
        )
    )

    private fun notesOf(db: KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.NOTES]?.readString() }
            .toSet()

    /** 模拟「他端改了云端」：第二会话（同密码）加一条远端独有条目，导出字节注入远端，不动本地文件。 */
    private suspend fun pushRemoteOnlyEntry(fx: Fixture) {
        val producer = DatabaseSession()
        val opened = producer.open(fx.vaultFile, PROBE_PASSWORD)
        assertTrue("第二会话打开失败: $opened", opened is KdbxResult.Success)
        producer.saveEntry(entry(REMOTE_ONLY_ID, "他端新增"))
        val remoteBytes = (producer.exportToBytes() as KdbxResult.Success).data
        fx.provider.inject(fx.remotePath, remoteBytes, "etag-remote-changed")
    }

    /** 锁库 → 冷启动：同步缓存全量销毁 + 进程内存基线丢失（磁盘库与本地文件保持原样）。 */
    private fun simulateLockAndColdStart(fx: Fixture) {
        assertTrue("前提：清缓存应成功", fx.syncCache.clearAll())
        fx.session.lastSyncedDb = null
    }

    @Test
    fun `远端侧更新且本地零编辑时，冷启动走远端接管零上传且如实告知`() = runTest(testDispatcher) {
        val fx = newFixture("remotetakeover")
        createVault(fx, "remotetakeover")
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)

        // 第一轮：首传建基线；收尾应记录检查点（本地文件此后未被重写）
        val baseline = fx.cycle.runSyncCycle()
        assertTrue("首轮应首传建基线: $baseline", baseline is SyncOutcome.UploadedLocal)
        val uploadsAtBaseline = fx.provider.uploadCount
        assertTrue(
            "成功周期收尾应记录检查点",
            fx.checkpoint.isUnchangedSinceSync(fx.remotePath, fx.vaultFile)
        )

        pushRemoteOnlyEntry(fx)
        val remoteAfterInject = fx.provider.remoteBytes(fx.remotePath)
        simulateLockAndColdStart(fx)

        val outcome = fx.cycle.runSyncCycle()

        assertEquals(
            "差异全在远端一侧必须判「两端已一致后的远端接管」，不得误报本地修改: $outcome",
            SyncOutcome.UpToDate, outcome
        )
        assertEquals(
            "远端接管不得触发任何上传",
            uploadsAtBaseline, fx.provider.uploadCount
        )
        assertEquals(
            "远端字节必须逐字节不变",
            remoteAfterInject.toList(), fx.provider.remoteBytes(fx.remotePath).toList()
        )
        assertEquals(
            "远端独有条目应已接管进本地会话树: ${notesOf(fx.databaseSession.databaseFlow.value)}",
            setOf("基线值", "他端新增"), notesOf(fx.databaseSession.databaseFlow.value)
        )
        val events = fx.session.lastSyncEngine!!.events.replayCache
        assertTrue(
            "远端接管必须经既有事件通道如实告知（列表页文案＝检测到云端已有更新）: $events",
            events.any { it is SyncCacheEvent.UpdatedCachedFileOnLoad }
        )
        assertTrue("接管后基线应已前移", fx.session.lastSyncedDb != null)
        assertTrue("接管后检查点应已前移到接管后的文件", fx.checkpoint.isUnchangedSinceSync(fx.remotePath, fx.vaultFile))
    }

    @Test
    fun `本地确有未同步编辑时，占位态仍走合并路径且编辑不丢`() = runTest(testDispatcher) {
        val fx = newFixture("localsynced")
        createVault(fx, "localsynced")
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)
        val baseline = fx.cycle.runSyncCycle()
        assertTrue("首轮应首传建基线: $baseline", baseline is SyncOutcome.UploadedLocal)
        val uploadsAtBaseline = fx.provider.uploadCount

        // 本地「已落盘未同步」编辑（F1 场景）：文件被重写 ⇒ 检查点失配 ⇒ 保守走合并
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "本地修改"))
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)
        assertFalse(
            "文件已被本地重写，检查点必须失配",
            fx.checkpoint.isUnchangedSinceSync(fx.remotePath, fx.vaultFile)
        )

        pushRemoteOnlyEntry(fx)
        val remoteAfterInject = fx.provider.remoteBytes(fx.remotePath)
        simulateLockAndColdStart(fx)

        val outcome = fx.cycle.runSyncCycle()

        assertTrue(
            "本地确有未同步修改必须维持既有合并路径: $outcome",
            outcome is SyncOutcome.ConflictNeedsUser || outcome is SyncOutcome.MergedAndUploaded
        )
        assertEquals(
            "本地会话树必须保留本地编辑: ${notesOf(fx.databaseSession.databaseFlow.value)}",
            setOf("本地修改", "他端新增"), notesOf(fx.databaseSession.databaseFlow.value)
        )
        if (outcome is SyncOutcome.ConflictNeedsUser) {
            // 冲突待决：不得把任何产物推上云端，检查点也不得记录（否则下一轮会丢编辑）
            assertEquals("冲突未裁决前不得上传", uploadsAtBaseline, fx.provider.uploadCount)
            assertFalse(
                "ConflictNeedsUser 不得记录检查点（下一轮会据此静默丢弃本地编辑）",
                fx.checkpoint.isUnchangedSinceSync(fx.remotePath, fx.vaultFile)
            )
            assertEquals("远端必须仍是注入后的他端版本", remoteAfterInject.toList(), fx.provider.remoteBytes(fx.remotePath).toList())
        }
    }

    @Test
    fun `检查点无记录时，占位态维持整改前的保守合并路径`() = runTest(testDispatcher) {
        val fx = newFixture("nocheckpoint")
        createVault(fx, "nocheckpoint")
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)
        val baseline = fx.cycle.runSyncCycle()
        assertTrue("首轮应首传建基线: $baseline", baseline is SyncOutcome.UploadedLocal)
        val uploadsAtBaseline = fx.provider.uploadCount

        // 显式清空检查点（等价「进程刚重启且缓存同时被回收」的保守残余组合）
        fx.checkpoint.clear()
        pushRemoteOnlyEntry(fx)
        simulateLockAndColdStart(fx)

        val outcome = fx.cycle.runSyncCycle()

        assertTrue(
            "无记录时必须保守维持既有合并/上传路径（不得据空证据接管远端）: $outcome",
            outcome is SyncOutcome.MergedAndUploaded || outcome is SyncOutcome.ConflictNeedsUser
        )
        assertTrue(
            "保守路径确有上传发生（与整改前行为一致）",
            fx.provider.uploadCount > uploadsAtBaseline || outcome is SyncOutcome.ConflictNeedsUser
        )
    }

    @Test
    fun `检查点记录判据只认已与远端收敛的结论`() {
        assertTrue(SyncOutcome.UpToDate.isInSyncWithRemote())
        assertTrue(SyncOutcome.UploadedLocal.isInSyncWithRemote())
        assertTrue(SyncOutcome.MergedAndUploaded.isInSyncWithRemote())
        assertFalse(SyncOutcome.Offline.isInSyncWithRemote())
        assertFalse(SyncOutcome.Error("x").isInSyncWithRemote())
        assertFalse(SyncOutcome.ConflictNeedsUser(emptyList()).isInSyncWithRemote())
        assertFalse(SyncOutcome.VaultBindingMismatch("/p").isInSyncWithRemote())
    }

    /** 进程内假 Provider：ETag 随内容变化，支持 out-of-band 注入远端版本 + 上传计数。 */
    private class RemoteChangeProbeProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

        var uploadCount: Int = 0
            private set

        fun inject(remotePath: String, bytes: ByteArray, etag: String) {
            remote[remotePath] = bytes to etag
        }

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
            remote[remotePath] = data to etag
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }

    private companion object {
        val PROBE_PASSWORD = "RemoteDiff#2026".toCharArray()
        val LOCAL_ID = KdbxUuid.random()
        val REMOTE_ONLY_ID = KdbxUuid.random()
    }
}
