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
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncMidCycleEditGuardTest` 同口径）。 */
private val SNAPSHOT_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * `ISSUE-P1-591`（AUD-03）：同步提交的**快照一致性**回归。
 *
 * ## 锁定的缺陷
 *
 * 各提交路径把基线（`SyncSessionState.lastSyncedDb`）赋为**结算时刻的**
 * `databaseSession.databaseFlow.value`，而非**实际上传 / 已确认的版本**。上传 A 的网络等待期间
 * 用户保存 B ⇒ B 被记为 A 的基线：下一轮内容变化检测（`SyncContentChangeDetector` 以该基线
 * 判据）认为「无变化」，B 永不上传、并可能被后续远端内容静默覆盖。
 *
 * ## 判据（与 `ACTIVE_ISSUES` 的 591 交接一致）
 *
 * - 上传 A 期间保存 B：**远端为 A**、**B 仍待同步**（基线不前移）⇒ 第二轮上传 B；
 * - 窗口内的新版本**不得**被记成本轮的已收敛版本（`isInSyncWithRemote()` 为 false）。
 *
 * ## 注入点
 *
 * 复用 `SyncMidCycleEditGuardTest` 的「窗口编辑」钩子：记录型 Provider 在 `upload()` 落库**之后**
 * 真实执行一次 `saveEntry + save`——这正是生产上窗口内发生的事件序列。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncUploadSnapshotConsistencyTest {

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
        val session: SyncSessionState,
        val provider: WindowEditProvider,
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
        val conflicts = SyncConflictController(databaseSession, codec, SNAPSHOT_TEST_STRINGS, session, debugLog)
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
            strings = SNAPSHOT_TEST_STRINGS
        )
        val provider = WindowEditProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        return Fixture(databaseSession, cycle, codec, session, provider, remotePath)
    }

    private suspend fun createVault(fx: Fixture, key: String) {
        val created = fx.databaseSession.create(
            file = File(tempFolder.root, "$key.kdbx"),
            name = key,
            passwordChars = "UploadSnapshot#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue("夹具建库失败: $created", created is KdbxResult.Success)
    }

    private fun entry(title: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false))
    )

    private fun titlesOf(db: KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.TITLE]?.readString() }
            .toSet()

    private fun remoteTitles(fx: Fixture): Set<String> =
        titlesOf(
            com.keepasskey.database.file.KdbxFile.load(
                fx.provider.remoteBytes(fx.remotePath).inputStream(),
                "UploadSnapshot#2026".toCharArray(),
                null
            )
        )

    @Test
    fun `上传 A 期间保存 B：远端为 A、B 仍待同步、第二轮上传 B（AC 第 1 行）`() =
        runTest(testDispatcher) {
            val fx = newFixture("fastdiverged")
            createVault(fx, "fastdiverged")

            // 第一轮：建立远端基线（L0）
            val baseline = fx.cycle.runSyncCycle()
            assertTrue("首轮应建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)
            val l0 = fx.databaseSession.databaseFlow.value!!

            // 本地未落盘修改 A（保持 DIRTY ⇒ 第二轮走快速提交路径）＋ 上传落库后注入「窗口内保存 B」
            fx.databaseSession.saveEntry(entry("本地A"))
            val bEntry = entry("窗口内B")
            fx.provider.onNextUpload = {
                fx.databaseSession.saveEntry(bEntry)
                fx.databaseSession.save()
            }
            val second = fx.cycle.runSyncCycle()

            assertTrue(
                "上传窗口内又保存出新版本时本轮不得谎报已收敛: $second",
                second is SyncOutcome.LocalPendingChanges
            )
            assertFalse(
                "LocalPendingChanges 不得被当作已收敛（否则会记错本地文件检查点）",
                second.isInSyncWithRemote()
            )
            val remoteSecond = remoteTitles(fx)
            assertTrue("远端应为实际上传的 A（含「本地A」）: $remoteSecond", "本地A" in remoteSecond)
            assertFalse("远端不得含窗口内保存的 B: $remoteSecond", "窗口内B" in remoteSecond)
            val sessionTitles = titlesOf(fx.databaseSession.databaseFlow.value)
            assertTrue("B 必须仍在会话树中: $sessionTitles", "窗口内B" in sessionTitles)
            assertNotSame(
                "基线不得前移到 B（否则下一轮内容变化检测会误判「无变化」而永不上传 B）",
                fx.databaseSession.databaseFlow.value,
                fx.session.lastSyncedDb
            )
            assertSame("基线仍应是 L0（本轮未前移）", l0, fx.session.lastSyncedDb)

            // 第三轮：B 必须被上传（远端收敛到含 B 的版本）
            val third = fx.cycle.runSyncCycle()
            assertTrue(
                "第三轮应收敛成功（合并或本地赢上传）: $third",
                third is SyncOutcome.MergedAndUploaded || third is SyncOutcome.UploadedLocal
            )
            val remoteThird = remoteTitles(fx)
            assertTrue("第三轮后远端必须含 B: $remoteThird", "窗口内B" in remoteThird)
            assertTrue("第三轮后远端必须含 A: $remoteThird", "本地A" in remoteThird)
        }

    @Test
    fun `首传窗口内保存 B：首传路径同样不把 B 记为基线（AC 第 3 行）`() = runTest(testDispatcher) {
        val fx = newFixture("firstuploaddiverged")
        createVault(fx, "firstuploaddiverged")

        // 首传（establishRemoteBaselineIfMissing）落库后注入「窗口内保存 B」
        fx.provider.onNextUpload = {
            fx.databaseSession.saveEntry(entry("首传窗口内B"))
            fx.databaseSession.save()
        }
        val first = fx.cycle.runSyncCycle()

        assertTrue("首传窗口内换版本须如实回报待同步: $first", first is SyncOutcome.LocalPendingChanges)
        assertFalse("首传分叉不得记「已收敛」", first.isInSyncWithRemote())
        assertTrue("B 必须仍在会话树中", "首传窗口内B" in titlesOf(fx.databaseSession.databaseFlow.value))
        assertNotSame(
            "首传基线不得前移到 B（须停在实际上传的版本）",
            fx.databaseSession.databaseFlow.value,
            fx.session.lastSyncedDb
        )
    }

    @Test
    fun `两端一致（未上传）窗口内保存 B：不谎报 UpToDate（AC 第 3 行常规路径）`() =
        runTest(testDispatcher) {
            val fx = newFixture("synceddiverged")
            createVault(fx, "synceddiverged")

            val baseline = fx.cycle.runSyncCycle()
            assertTrue("首轮应建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)
            val l0 = fx.databaseSession.databaseFlow.value!!

            // 第二轮：本地无改动、远端无变化 ⇒ 引擎走 RemoteSynced 的「内容一致」支
            // （远端 ETag == 缓存 ETag，零下载）。注入点在 openRemote 的 getMetadata 返回前。
            fx.provider.onNextMetadata = {
                fx.databaseSession.saveEntry(entry("一致分支窗口内B"))
                fx.databaseSession.save()
            }
            val second = fx.cycle.runSyncCycle()

            assertTrue(
                "比对窗口内出现新版本时不得谎报 UpToDate: $second",
                second is SyncOutcome.LocalPendingChanges
            )
            assertFalse(second.isInSyncWithRemote())
            assertSame("基线保持 L0 不前移", l0, fx.session.lastSyncedDb)
            assertTrue(
                "窗口内编辑必须仍在会话树中",
                "一致分支窗口内B" in titlesOf(fx.databaseSession.databaseFlow.value)
            )
        }

    @Test
    fun `settleSyncedVersion 只在活动树与上传版本同一实例时前移基线`() {
        val session = SyncSessionState()
        val uploaded = newDb("上传版本")

        // 同一实例（无窗口内编辑）⇒ 前移
        assertTrue(
            "活动树 == 上传版本时必须前移",
            settleSyncedVersion(session, uploaded, uploaded) == UploadSettlement.CONVERGED
        )
        assertSame(uploaded, session.lastSyncedDb)

        // 不同实例（窗口内换版本 / 换库）⇒ 不前移，且不得持有非活动树引用
        val other = newDb("分叉版本")
        assertTrue(
            "活动树 != 上传版本时不得前移",
            settleSyncedVersion(session, other, uploaded) == UploadSettlement.LOCAL_DIVERGED
        )
        assertSame("基线必须仍停在原处（不持有分叉树）", uploaded, session.lastSyncedDb)

        // 无活动库（锁定 / 关闭）⇒ 同样不前移
        assertTrue(
            "无活动库时不得前移",
            settleSyncedVersion(session, other, null) == UploadSettlement.LOCAL_DIVERGED
        )
        assertSame("无活动库时基线也不得改为分叉树", uploaded, session.lastSyncedDb)
    }

    private fun newDb(name: String): KdbxDatabase = KdbxDatabase(
        header = com.keepasskey.database.file.KdbxHeader.createDefault(useArgon2 = false),
        rootGroup = com.keepasskey.core.model.KdbxGroup(id = KdbxUuid.random(), name = name)
    )

    /** 进程内假 Provider：支持「下一次下载 / 上传窗口内注入一次动作」的钩子。 */
    private class WindowEditProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

        var onNextDownload: (suspend () -> Unit)? = null

        /** 非 null 时在下一次 upload 落库**之后**执行一次并自动清除（模拟上传窗口内用户保存）。 */
        var onNextUpload: (suspend () -> Unit)? = null

        /** 非 null 时在下一次 getMetadata 返回**之前**执行一次并自动清除（模拟比对窗口内用户保存）。 */
        var onNextMetadata: (suspend () -> Unit)? = null

        fun remoteBytes(remotePath: String): ByteArray = remote.getValue(remotePath).first

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            onNextMetadata?.let { hook ->
                onNextMetadata = null
                hook()
            }
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
            onNextDownload?.let { hook ->
                onNextDownload = null
                hook()
            }
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
            onNextUpload?.let { hook ->
                onNextUpload = null
                hook()
            }
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }
}
