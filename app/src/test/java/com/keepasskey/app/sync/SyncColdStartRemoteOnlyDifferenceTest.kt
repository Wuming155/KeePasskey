package com.keepasskey.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.vault.VaultListSyncController
import kotlinx.coroutines.channels.Channel
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.security.AutoLockHoldoff
import com.keepasskey.app.security.AutoLockSessionGuard
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
import com.keepasskey.database.session.StableDatabaseFileDigest
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
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
 * 持久化摘要检查点 `SyncLocalFileCheckpoint`（本地密文 SHA-256，成功周期收尾记录）：
 * 占位态且「本地库文件自上次成功同步以来未被重写」⇒ 差异只能来自远端 ⇒ 走既有
 * **远端接管**尾段（零上传 + `UpdatedCachedFileOnLoad` 诚实文案）；本地确有改动
 * （`isDirty` 或文件已被重写）⇒ 维持既有合并路径，F1 两类覆盖面不回退。
 *
 * ## 与缓存存活路径的关系（为何本机制只补占位态）
 *
 * 缓存存活时，`resolveLocalContentChanged` 比对快照即可确证 `UNCHANGED`；
 * 锁库清缓存后改用持久化摘要检查点，进程重启亦可恢复证明。覆盖 File 与 SAF 流式通道，
 * 并验证真实本地编辑、同步窗口内编辑及配置撤销不会误用旧证明。
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
        val checkpoint: SyncLocalFileCheckpoint,
        val holdoff: AutoLockHoldoff?,
        val autoLockGuard: AutoLockSessionGuard?
    )

    private fun newFixture(
        key: String,
        withAutoLockGuard: Boolean = false,
        prefs: InMemorySharedPreferences = InMemorySharedPreferences()
    ): Fixture {
        val cacheDir = File(tempFolder.root, "$key-cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "$key-files").apply { mkdirs() }
        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.prefs
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }

        val databaseSession = DatabaseSession()
        val debugLog = DebugLogBuffer()
        val holdoff = if (withAutoLockGuard) AutoLockHoldoff() else null
        val autoLockGuard = holdoff?.let {
            AutoLockSessionGuard(databaseSession, FakeSettingsRepository(), holdoff = it)
        }
        val session = SyncSessionState()
        val preferences = SyncPreferences(null)
        val credentialsStore = SyncCredentialsStore(fakeContext, null)
        credentialsStore.customEncryptor = { plain -> Pair(byteArrayOf(1), plain) }
        credentialsStore.customDecryptor = { _, cipher -> cipher }
        val providerResolver = SyncProviderResolver(credentialsStore, preferences)
        val codec = SyncDatabaseCodec(databaseSession)
        val conflicts = SyncConflictController(databaseSession, codec, REMOTE_DIFF_STRINGS, session)
        val changes = SyncContentChangeDetector(codec, session)
        val checkpoint = SyncLocalFileCheckpoint(fakeContext)
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
            localFileCheckpoint = checkpoint,
            autoLockGuard = autoLockGuard
        )
        val provider = RemoteChangeProbeProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        val syncCache = SyncCache(File(cacheDir, SyncCache.CACHE_DIR_NAME).apply { mkdirs() })
        return Fixture(
            databaseSession, session, cycle, syncCache, provider, remotePath,
            File(tempFolder.root, "$key.kdbx"), checkpoint, holdoff, autoLockGuard
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

    private suspend fun checkpointMatchesCurrentRevision(fx: Fixture): Boolean {
        val database = fx.databaseSession.databaseFlow.value ?: return false
        val revision = fx.databaseSession.writeIntents.digestStableFileFor(database)
        return fx.checkpoint.isUnchangedSinceSync(fx.remotePath, "test-provider:${fx.remotePath}", revision)
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
            checkpointMatchesCurrentRevision(fx)
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
        assertTrue("接管后检查点应已前移到接管后的文件", checkpointMatchesCurrentRevision(fx))
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
            checkpointMatchesCurrentRevision(fx)
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
                checkpointMatchesCurrentRevision(fx)
            )
            assertEquals("远端必须仍是注入后的他端版本", remoteAfterInject.toList(), fx.provider.remoteBytes(fx.remotePath).toList())
        }
    }

    private suspend fun reopenVault(fx: Fixture, stream: Boolean) {
        val opened = if (stream) {
            fx.databaseSession.openStream(
                pathIdentifier = "content://test/vault/${fx.vaultFile.name}",
                inputStreamProvider = { fx.vaultFile.inputStream() },
                saveWriter = { fx.vaultFile.writeBytes(it) },
                passwordChars = PROBE_PASSWORD
            )
        } else {
            fx.databaseSession.open(fx.vaultFile, PROBE_PASSWORD)
        }
        assertTrue("重新打开密码库失败: $opened", opened is KdbxResult.Success)
    }

    @Test
    fun `锁库后重启进程仍能证明本地未变且远端更新零上传`() = runTest(testDispatcher) {
        for (stream in listOf(false, true)) {
            val key = "restart-remote-$stream"
            val prefs = InMemorySharedPreferences()
            val original = newFixture(key, prefs = prefs)
            createVault(original, key)
            original.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
            assertTrue(original.databaseSession.save() is KdbxResult.Success)
            if (stream) {
                original.databaseSession.close()
                reopenVault(original, stream = true)
            }
            assertEquals(SyncOutcome.UploadedLocal, original.cycle.runSyncCycle())
            assertTrue(checkpointMatchesCurrentRevision(original))
            pushRemoteOnlyEntry(original)
            val remoteBytes = original.provider.remoteBytes(original.remotePath).copyOf()
            simulateLockAndColdStart(original)
            original.databaseSession.close()

            val restarted = newFixture(key, prefs = prefs)
            restarted.provider.inject(restarted.remotePath, remoteBytes, "etag-peer-update")
            reopenVault(restarted, stream)
            assertTrue("新进程应读回已同步的本地版本证明", checkpointMatchesCurrentRevision(restarted))
            assertEquals(SyncOutcome.UpToDate, restarted.cycle.runSyncCycle())
            assertEquals("只有远端更新不能重新上传整库", 0, restarted.provider.uploadCount)
            assertTrue(remoteBytes.contentEquals(restarted.provider.remoteBytes(restarted.remotePath)))
            assertEquals(setOf("基线值", "他端新增"), notesOf(restarted.databaseSession.databaseFlow.value))
            assertTrue(restarted.session.lastSyncEngine!!.events.replayCache.any {
                it is SyncCacheEvent.UpdatedCachedFileOnLoad
            })
            assertEquals(SyncOutcome.UpToDate, restarted.cycle.runSyncCycle())
            assertEquals("重建基线后的再次同步仍不得上传", 0, restarted.provider.uploadCount)
            restarted.databaseSession.close()
        }
    }

    @Test
    fun `重启后真实本地编辑使持久检查点失配且不能被远端覆盖`() = runTest(testDispatcher) {
        for (stream in listOf(false, true)) {
            val key = "restart-edited-$stream"
            val prefs = InMemorySharedPreferences()
            val original = newFixture(key, prefs = prefs)
            createVault(original, key)
            original.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
            assertTrue(original.databaseSession.save() is KdbxResult.Success)
            if (stream) {
                original.databaseSession.close()
                reopenVault(original, stream = true)
            }
            assertEquals(SyncOutcome.UploadedLocal, original.cycle.runSyncCycle())
            pushRemoteOnlyEntry(original)
            val remoteBytes = original.provider.remoteBytes(original.remotePath).copyOf()
            original.databaseSession.saveEntry(entry(LOCAL_ID, "本地尚未上传"))
            assertTrue(original.databaseSession.save() is KdbxResult.Success)
            simulateLockAndColdStart(original)
            original.databaseSession.close()

            val restarted = newFixture(key, prefs = prefs)
            restarted.provider.inject(restarted.remotePath, remoteBytes, "etag-peer-update")
            reopenVault(restarted, stream)
            assertFalse("新保存的本地文件不能沿用旧同步证明", checkpointMatchesCurrentRevision(restarted))
            val outcome = restarted.cycle.runSyncCycle()
            assertTrue("本地编辑必须进入合并或冲突: $outcome",
                outcome is SyncOutcome.MergedAndUploaded || outcome is SyncOutcome.ConflictNeedsUser)
            assertTrue("不能丢弃本地未上传内容", "本地尚未上传" in notesOf(restarted.databaseSession.databaseFlow.value))
            restarted.databaseSession.close()
        }
    }

    @Test
    fun `列表提示区分本地首传与缺失证明后的合并上传`() = runTest(testDispatcher) {
        val fx = newFixture("sync-feedback")
        createVault(fx, "sync-feedback")
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
        assertTrue(fx.databaseSession.save() is KdbxResult.Success)
        val credentials = SyncCredentialsStore(fx.cycle.context, null).apply {
            customEncryptor = { plain -> Pair(byteArrayOf(1), plain) }
            customDecryptor = { _, cipher -> cipher }
        }
        assertTrue(credentials.saveWebDavConfig(
            "https://sync.example.test", "fake-user", "fake-password".toCharArray(), fx.remotePath
        ))
        val coordinator = SyncCoordinator(
            context = fx.cycle.context,
            databaseSession = fx.databaseSession,
            syncCredentialsStore = credentials,
            strings = REMOTE_DIFF_STRINGS,
            syncSession = fx.session,
            syncProviderResolver = fx.cycle.providerResolver,
            syncConflicts = fx.cycle.conflicts,
            syncCycle = fx.cycle)
        val messages = Channel<UiMessage>(Channel.UNLIMITED)
        val controller = VaultListSyncController(
            coordinator, this, REMOTE_DIFF_STRINGS
        ) { messages.trySend(it) }
        controller.triggerPullRefresh()
        val uploaded = messages.receive()
        assertEquals(R.string.vault_sync_uploaded, uploaded.resId)
        assertEquals(UiMessage.SYNC_STATUS_DURATION_MS, uploaded.durationMillis)
        pushRemoteOnlyEntry(fx)
        fx.checkpoint.clear()
        simulateLockAndColdStart(fx)
        controller.triggerPullRefresh()
        val merged = messages.receive()
        assertEquals(R.string.vault_sync_merged, merged.resId)
        assertEquals(UiMessage.SYNC_STATUS_DURATION_MS, merged.durationMillis)
        fx.databaseSession.close()
        messages.close()
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
    fun `同步结算后检查点前保存的新版本不会被误记为已同步`() = runTest(testDispatcher) {
        val fx = newFixture("post-settlement-save")
        createVault(fx, "post-settlement-save")
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "基线值"))
        assertTrue("夹具落盘应成功", fx.databaseSession.save() is KdbxResult.Success)
        val baseline = fx.cycle.runSyncCycle()
        assertTrue("首轮应首传建基线: $baseline", baseline is SyncOutcome.UploadedLocal)

        val peerFile = File(tempFolder.root, "post-settlement-peer.kdbx")
        peerFile.writeBytes(fx.provider.remoteBytes(fx.remotePath))
        val peer = DatabaseSession()
        val remoteC = try {
            val opened = peer.open(peerFile, PROBE_PASSWORD)
            assertTrue("远端基线副本应可打开: $opened", opened is KdbxResult.Success)
            peer.saveEntry(entry(REMOTE_ONLY_ID, "云端更新"))
            (peer.exportToBytes() as KdbxResult.Success).data
        } finally {
            peer.close()
        }

        val checkpointBoundaryReached = CompletableDeferred<Unit>()
        val continueCheckpoint = CompletableDeferred<Unit>()
        fx.cycle.beforeCheckpointRecord = {
            checkpointBoundaryReached.complete(Unit)
            continueCheckpoint.await()
        }
        val sync = async { fx.cycle.runSyncCycle() }
        checkpointBoundaryReached.await()

        // A 已与远端结算；在检查点摘要前保存 B，确定性覆盖登记窗口。
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "本地新编辑"))
        assertTrue("新编辑应保存成功", fx.databaseSession.save() is KdbxResult.Success)
        continueCheckpoint.complete(Unit)
        assertEquals(SyncOutcome.UpToDate, sync.await())
        fx.cycle.beforeCheckpointRecord = null

        assertFalse(
            "结算后提交的 B 不得被记录成 A 已同步",
            checkpointMatchesCurrentRevision(fx)
        )

        // 锁库清除同步缓存后，远端 C 只含 A + 他端编辑；B 必须进入冲突/合并并保留。
        fx.provider.inject(fx.remotePath, remoteC, "etag-remote-c")
        simulateLockAndColdStart(fx)
        val recovery = fx.cycle.runSyncCycle()
        assertTrue(
            "远端 C 到达时应保留本地 B 并进入合并/冲突，而非直接接管: $recovery",
            recovery is SyncOutcome.ConflictNeedsUser || recovery is SyncOutcome.MergedAndUploaded
        )
        assertTrue(
            "本地 B 必须保留: ${notesOf(fx.databaseSession.databaseFlow.value)}",
            "本地新编辑" in notesOf(fx.databaseSession.databaseFlow.value)
        )
        val remoteUpdateIsActive = "云端更新" in notesOf(fx.databaseSession.databaseFlow.value)
        val remoteUpdateIsPending = fx.cycle.conflicts.pendingMergedRoot
            ?.allEntries()
            ?.any { it.id == REMOTE_ONLY_ID } == true
        assertTrue(
            "云端 C 的独有修改必须在活动树或待决合并产物中保留: outcome=$recovery, " +
                "local=${notesOf(fx.databaseSession.databaseFlow.value)}",
            remoteUpdateIsActive || remoteUpdateIsPending
        )
    }

    @Test
    fun `等待同步互斥锁期间自动锁定不被挂起`() = runTest(testDispatcher) {
        val fx = newFixture("lock-while-sync-queued", withAutoLockGuard = true)
        createVault(fx, "lock-while-sync-queued")
        val holdoff = requireNotNull(fx.holdoff)
        val guard = requireNotNull(fx.autoLockGuard)

        fx.session.mutex.lock()
        val queuedSync = async { fx.cycle.runSyncCycle() }
        yield()
        assertFalse("排队等待同步 mutex 时不能开启锁定挂锁", holdoff.isHolding)
        val lockOutcome = guard.triggerLock("队列中的安全锁定")
        assertTrue("排队期间锁定应立即完成", lockOutcome != com.keepasskey.app.security.LockOutcome.Deferred)
        assertEquals(DatabaseSession.SessionState.LOCKED, fx.databaseSession.state.value)
        fx.session.mutex.unlock()
        assertTrue(queuedSync.await() is SyncOutcome.Error)
    }

    @Test
    fun `慢网络等待期间自动锁定不被挂起`() = runTest(testDispatcher) {
        val fx = newFixture("lock-during-network", withAutoLockGuard = true)
        createVault(fx, "lock-during-network")
        fx.databaseSession.saveEntry(entry(LOCAL_ID, "本地内容"))
        assertTrue(fx.databaseSession.save() is KdbxResult.Success)

        val networkStarted = CompletableDeferred<Unit>()
        val continueNetwork = CompletableDeferred<Unit>()
        fx.provider.metadataStarted = networkStarted
        fx.provider.continueMetadata = continueNetwork

        val sync = async { fx.cycle.runSyncCycle() }
        networkStarted.await()
        assertFalse("元数据请求等待期间不得持有安全锁定挂锁", requireNotNull(fx.holdoff).isHolding)

        val lockOutcome = requireNotNull(fx.autoLockGuard).triggerLock("慢网络期间的安全锁定")
        assertTrue("网络等待期间锁定应立即执行", lockOutcome != com.keepasskey.app.security.LockOutcome.Deferred)
        assertEquals(DatabaseSession.SessionState.LOCKED, fx.databaseSession.state.value)
        continueNetwork.complete(Unit)
        assertTrue(sync.await() is SyncOutcome.LocalPendingChanges)
    }

    @Test
    fun `本地快照挂锁预算耗尽后中止并释放锁定挂锁`() = runTest(testDispatcher) {
        val fx = newFixture("snapshot-lock-budget", withAutoLockGuard = true)
        createVault(fx, "snapshot-lock-budget")
        fx.cycle.localSnapshotHoldoffBudgetMillis = 1_000L
        val entered = CompletableDeferred<Unit>()
        fx.cycle.beforeLocalSnapshot = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        val sync = async { fx.cycle.runSyncCycle() }
        entered.await()
        assertTrue("本地快照准备期间应临时持有挂锁", requireNotNull(fx.holdoff).isHolding)
        assertEquals(
            "快照段中的锁定请求仅可等待本地预算",
            com.keepasskey.app.security.LockOutcome.Deferred,
            requireNotNull(fx.autoLockGuard).triggerLock("快照准备期间请求锁定")
        )

        assertTrue("超时后应返回可见错误", sync.await() is SyncOutcome.Error)
        assertFalse("超时必须释放挂锁", requireNotNull(fx.holdoff).isHolding)
        assertEquals("预算结束后必须补执行暂存锁定", DatabaseSession.SessionState.LOCKED, fx.databaseSession.state.value)
        fx.cycle.beforeLocalSnapshot = null
    }

    @Test
    fun `检查点绑定云端目标身份`() = runTest {
        val checkpoint = SyncLocalFileCheckpoint(InMemorySharedPreferences().context())
        val revision = StableDatabaseFileDigest("vault-id", "C:/vault.kdbx", "aabbcc")
        checkpoint.record("/vault.kdbx", "endpoint-A", revision)

        assertTrue(checkpoint.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", revision))
        assertFalse("同一路径改绑端点后不得复用旧证明", checkpoint.isUnchangedSinceSync("/vault.kdbx", "endpoint-B", revision))
        assertFalse("缺少目标身份时不得放行", checkpoint.isUnchangedSinceSync("/vault.kdbx", null, revision))
    }

    @Test
    fun `检查点跨实例按库隔离且单库及全部撤销配置都会清除`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = prefs.context()
        val first = StableDatabaseFileDigest("C:/first.kdbx", "C:/first.kdbx", "digest-first")
        val second = StableDatabaseFileDigest("C:/second.kdbx", "C:/second.kdbx", "digest-second")
        val checkpoint = SyncLocalFileCheckpoint(context)
        checkpoint.record("/vault.kdbx", "endpoint-A", first)
        checkpoint.record("/vault.kdbx", "endpoint-A", second)

        val restarted = SyncLocalFileCheckpoint(context)
        assertTrue(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", first))
        assertTrue(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", second))
        assertFalse(restarted.isUnchangedSinceSync("/other.kdbx", "endpoint-A", first))
        assertFalse(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", first.copy(sha256 = "edited")))
        assertFalse(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", first.copy(filePath = "C:/other.kdbx")))
        assertFalse(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", null))
        assertTrue("持久化键值只包含版本摘要", prefs.storage.values.all { (it as String).matches(Regex("[0-9a-f]{64}")) })

        SyncCredentialKeys.removeScoped(prefs.prefs, first.pathIdentifier)
        assertFalse(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", first))
        assertTrue(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", second))
        SyncCredentialsStore(context, null).clear()
        assertFalse(restarted.isUnchangedSinceSync("/vault.kdbx", "endpoint-A", second))
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

        var metadataStarted: CompletableDeferred<Unit>? = null
        var continueMetadata: CompletableDeferred<Unit>? = null

        var uploadCount: Int = 0
            private set

        fun inject(remotePath: String, bytes: ByteArray, etag: String) {
            remote[remotePath] = bytes to etag
        }

        fun remoteBytes(remotePath: String): ByteArray = remote.getValue(remotePath).first

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            metadataStarted?.complete(Unit)
            continueMetadata?.await()
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
