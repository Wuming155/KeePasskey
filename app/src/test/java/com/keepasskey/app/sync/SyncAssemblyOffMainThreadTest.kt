package com.keepasskey.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncCoordinatorTest` 同口径）。 */
private val TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * 「主线程零 IO」守卫（`ISSUE-P2-277` AC③）。
 *
 * ## 锁定的缺陷
 *
 * `SyncCycleRunner.setupCycleContext`（同步周期装配段）内的每一步都是**普通非 suspend 调用**：
 * 凭据 Keystore 解密（`SyncProviderResolver.resolveProvider` / `resolveRemotePath`）、整库缓存读
 * （`SyncCache.readCache` / `readBaseContent`）、全库逐字段比较（`KdbxContentComparator`）、
 * 整库密文写 + `fd.sync()` + 原子 rename（`SyncCache.writeCache`）。整改前它们全部在**调用方线程**
 * 上执行，而前台入口（下拉刷新 / 解锁后自动同步 / 设置页触发）持 `viewModelScope`＝`Main.immediate`
 * ⇒ 大库下主线程被阻塞数百毫秒至秒级，并与凭据提供者的应答预算争用同一条主线程队列。
 *
 * ## 判据设计（四类各一个探针，缺一即红）
 *
 * | 类别 | 探针落点 | 观测方式 |
 * |---|---|---|
 * | 凭据读取 | `SyncCredentialSealer.decrypt` | 已有 `@VisibleForTesting` 钩子 `customDecryptor` 记录线程 |
 * | 整库缓存读 | `SyncCache.readCache`（`:68`，本就 `open`） | 记录型子类经 `syncCacheFactory` 注入 |
 * | 内容比较 | `KdbxContentComparator.changed` 的**首条判据** `current.deletedObjects != reference.deletedObjects` | 记录型 `List` 替身挂在 `deletedObjects` 上，其 `equals` 即比较执行点 |
 * | 原子写 | `SyncCache.writeCache`（`:112`，本批 `open` 化） | 同记录型子类 |
 *
 * ## 两条断言，缺一不可
 *
 * ① **非空性**：五类探针都必须被观测到，且**归因于装配段**（栈上存在 `SyncCycleRunner.setupCycleContext`）
 * ——否则断言会在「装配段根本没跑到那一步」的情况下空转通过；
 * ② **零主线程 IO**：所有记录（含引擎侧经同一 `SyncCache` 实例的同名调用）的执行线程都**不得**等于
 * 本用例的主线程。
 *
 * ## 主线程口径与判别力
 *
 * `Dispatchers.setMain(StandardTestDispatcher)` 后，用 `withContext(Dispatchers.Main) { Thread.currentThread() }`
 * 取到**真实**的 Main 执行线程作为基准——不依赖「测试主体恰好就是主线程」这一假设。
 * 判别力：撤销 `SyncCycleRunner` 的 `withContext(Dispatchers.IO)` 下沉后，装配段的调用会落回该基准线程，
 * 断言②立即转红（实测见批次正文的判别力实验）。
 *
 * ## 无网络声明
 *
 * 第一轮用假 Provider 建立基线与缓存；第二轮撤掉测试 Provider **以便真实的凭据链真正执行**（这是
 * 「凭据读取」探针成立的前提），同时置离线模式 ⇒ 引擎在装配段之后的判定只读本地缓存
 * （`SyncEngine.openRemote` 的 `isOffline` 早退分支），全程不触网。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncAssemblyOffMainThreadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    /** 全用例共用的探针记录（第二轮前清空，使归因只覆盖装配段真正执行的那一轮）。 */
    private val records = mutableListOf<ProbeRecord>()

    private data class ProbeRecord(val probe: String, val fromAssemblySegment: Boolean, val thread: Thread)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // Main 采用「只装不卸」口径（ISSUE-P3-189 路线①）：不调 resetMain，收尾走守卫
        MainDispatcherGuard.tearDown()
        records.clear()
    }

    @Test
    fun `装配段四类重活一律不得在主线程执行（ISSUE-P2-277 AC③）`() = runTest(testDispatcher) {
        val mainThread = withContext(Dispatchers.Main) { Thread.currentThread() }

        val cacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "files").apply { mkdirs() }
        val prefs = InMemorySharedPreferences()
        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.prefs
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }

        val password = "AssemblyOffMain#2026".toCharArray()
        val databaseSession = DatabaseSession()
        assertTrue(
            "夹具建库失败",
            databaseSession.create(
                file = File(filesDir, "assembly_off_main.kdbx"),
                name = "AssemblyOffMain",
                passwordChars = password,
                useArgon2 = false
            ) is KdbxResult.Success
        )

        val debugLog = DebugLogBuffer()
        val session = SyncSessionState()
        val preferences = SyncPreferences(debugLog, null)
        val credentialsStore = SyncCredentialsStore(fakeContext, null)
        credentialsStore.customEncryptor = { plain -> Pair(byteArrayOf(1), plain) }
        credentialsStore.customDecryptor = { _, cipher ->
            record(PROBE_CREDENTIAL)
            cipher
        }
        val providerResolver = SyncProviderResolver(credentialsStore, preferences, debugLog)
        val codec = SyncDatabaseCodec(databaseSession, debugLog)
        val conflicts = SyncConflictController(databaseSession, codec, TEST_STRINGS, session, debugLog)
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
            strings = TEST_STRINGS
        )
        cycle.syncCacheFactory = { dir -> RecordingCache(dir) { probe -> record(probe) } }

        val remotePath = "/remote/assembly_off_main.kdbx"

        // ── 第一轮：经假 Provider 建立远端基线与本地缓存（无网络） ──
        val provider = InMemoryProvider()
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        val baseline = cycle.runSyncCycle()
        assertTrue("首轮应在远端建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)
        assertTrue("首轮后应已建立会话内存基线（内容比较探针的前提）", session.lastSyncedDb != null)

        // ── 第二轮：撤掉测试 Provider / 测试路径 ⇒ 真实凭据链执行（凭据读取探针成立）；
        //            制造两处内容变更 ⇒ 内容比较与 writeCache 分支均被驱动 ──
        records.clear()
        session.testSyncProvider = null
        session.testRemotePath = null
        assertTrue(
            "预置 WebDAV 凭据失败",
            credentialsStore.saveWebDavConfig(
                // 必须是**公网** https 端点：WebDavSyncProvider 构造期带 SSRF 防护，
                // 内网 / 保留网段（含 127.0.0.1）直接被拒；本用例置离线模式，构造后不触网
                url = "https://dav.example.com/dav",
                username = "tester",
                password = "placeholder#1".toCharArray(),
                remotePath = remotePath
            )
        )
        databaseSession.saveEntry(
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("本地新增", false))
            )
        )
        databaseSession.save()
        // 注入「内容比较」探针。**实测踩坑（不这么做则断言恒红）**：`databaseFlow` 背后的
        // `MutableStateFlow` 按 `equals` 合并取值，而 `KdbxDatabase` 是 data class ⇒ 若替身 List
        // 与原有空列表「按内容相等」，整个赋值会被静默吞掉、探针根本进不了库。故注入**一条真实墓碑**：
        // 内容确实不同 ⇒ 赋值落地；比较器首条判据也随之必然命中（正是探针要测的那一行）。
        val changed = databaseSession.databaseFlow.value!!
        val injected = RecordingDeletedObjects(
            delegate = listOf(DeletedObject(id = KdbxUuid.random())),
            onCompared = { record(PROBE_COMPARISON) }
        )
        databaseSession.setDatabaseForTesting(changed.copy(deletedObjects = injected))
        assertTrue(
            "内容比较探针注入未生效: ${databaseSession.databaseFlow.value?.deletedObjects?.javaClass?.simpleName}",
            databaseSession.databaseFlow.value?.deletedObjects === injected
        )
        // 离线模式：装配段之后的引擎判定早退（只读缓存），本用例全程不触网
        session.isOfflineMode = true

        val second = cycle.runSyncCycle()
        assertTrue("离线且已缓存时本轮应为 Offline: $second", second is SyncOutcome.Offline)

        // ① 非空性 + 归因：五类探针都必须来自装配段，否则断言②会空转通过
        val diagnostics = "deletedObjects=${databaseSession.databaseFlow.value?.deletedObjects?.javaClass?.simpleName}" +
            ", lastSyncedDb=${session.lastSyncedDb != null}" +
            ", refDeleted=${session.lastSyncedDb?.deletedObjects?.javaClass?.simpleName}"
        PROBES.forEach { probe ->
            assertTrue(
                "探针「$probe」未在装配段内被观测到，本用例对该类不成立: $records || $diagnostics",
                records.any { it.probe == probe && it.fromAssemblySegment }
            )
        }

        // ② 主线程零 IO（含引擎侧经同一 SyncCache 实例的同名调用）
        val onMainThread = records.filter { it.thread === mainThread }
        assertTrue(
            "装配段/引擎的同步调用落在主线程上（ISSUE-P2-277 回归）: $onMainThread",
            onMainThread.isEmpty()
        )
    }

    private fun record(probe: String) {
        records += ProbeRecord(probe, inAssemblySegment(), Thread.currentThread())
    }

    /**
     * 归因：栈上存在 `SyncCycleRunner.setupCycleContext` 即视为来自装配段。
     * 引擎侧的同名缓存调用经 `SyncCycleRunner.handleOpenRemote` 发起，栈上**没有** `setupCycleContext`，
     * 故不会被误判为装配段。
     */
    private fun inAssemblySegment(): Boolean =
        Thread.currentThread().stackTrace.any {
            it.className == SYNC_CYCLE_RUNNER && it.methodName == SETUP_CYCLE_CONTEXT
        }

    /** 整库缓存读 / 基准内容读 / 原子写的记录型替身（三类共用同一注入点）。 */
    private class RecordingCache(
        dir: File,
        private val onProbe: (String) -> Unit
    ) : SyncCache(dir) {

        init {
            onProbe(PROBE_CACHE_CONSTRUCT)
        }

        override fun readCache(remotePath: String): ByteArray? {
            onProbe(PROBE_CACHE_READ)
            return super.readCache(remotePath)
        }

        override fun readBaseContent(remotePath: String): ByteArray? {
            onProbe(PROBE_BASE_READ)
            return super.readBaseContent(remotePath)
        }

        override fun writeCache(
            remotePath: String,
            data: ByteArray,
            updateVersion: Boolean,
            precomputedDigest: String?
        ): String {
            onProbe(PROBE_CACHE_WRITE)
            return super.writeCache(remotePath, data, updateVersion, precomputedDigest)
        }
    }

    /**
     * 「内容比较」探针：`KdbxContentComparator.changed` 首条判据即
     * `current.deletedObjects != reference.deletedObjects` ⇒ 挂在该字段上的 [equals] 的执行线程
     * **就是**全库内容比较的执行线程。
     *
     * [equals] 只旁路记录、判定结果仍由 `super.equals` 给出（两侧均为空列表 ⇒ 相等 ⇒ 继续走
     * `groupChanged`），不改变比较语义。
     */
    private class RecordingDeletedObjects(
        private val delegate: List<DeletedObject>,
        private val onCompared: () -> Unit
    ) : AbstractList<DeletedObject>() {

        override val size: Int get() = delegate.size

        override fun get(index: Int): DeletedObject = delegate[index]

        override fun equals(other: Any?): Boolean {
            onCompared()
            return super.equals(other)
        }

        override fun hashCode(): Int = delegate.hashCode()
    }

    /** 进程内假 Provider（无网络；仅覆盖本用例走到的方法面）。 */
    private class InMemoryProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

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
            val etag = "etag_${data.size}"
            remote[remotePath] = Pair(data, etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }

    private companion object {
        const val SYNC_CYCLE_RUNNER = "com.keepasskey.app.sync.SyncCycleRunner"
        const val SETUP_CYCLE_CONTEXT = "setupCycleContext"

        const val PROBE_CREDENTIAL = "凭据读取（Keystore 解密）"
        const val PROBE_CACHE_CONSTRUCT = "缓存实例构造"
        const val PROBE_CACHE_READ = "整库缓存读 readCache"
        const val PROBE_BASE_READ = "基准内容读 readBaseContent"
        const val PROBE_COMPARISON = "内容比较 KdbxContentComparator"
        const val PROBE_CACHE_WRITE = "原子写 writeCache"

        /** 四类锁定对应的必测探针（构造探针只作参考，不参与必测集）。 */
        val PROBES = listOf(
            PROBE_CREDENTIAL,
            PROBE_CACHE_READ,
            PROBE_BASE_READ,
            PROBE_COMPARISON,
            PROBE_CACHE_WRITE
        )
    }
}
