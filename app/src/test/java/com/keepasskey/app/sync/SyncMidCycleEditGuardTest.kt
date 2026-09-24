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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncCoordinatorTest` 同口径）。 */
private val GUARD_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * ISSUE-P2-278 AC③ 竞态用例：同步周期**起点之后**发生的本地编辑，严禁被
 * 「远端整库接管」或「三方合并落库」静默覆盖。
 *
 * ## 锁定的缺陷
 *
 * `isDirty` / `hasLocalContentChanged` 只在装配段取一次；UI 写路径不取本周期
 * `SyncSessionState.mutex` ⇒ 网络往返窗口内用户编辑并保存（DIRTY 被自身 `save()` 复位）
 * 后，旧判据仍放行走接管 / 合并，该编辑从内存与文件同时消失。
 *
 * ## 注入点
 *
 * 「窗口内编辑」由记录型 Provider 在 `download()`（接管侧）/ 冲突下载（合并侧）内
 * 真实执行 `saveEntry + save`——这正是生产上窗口内发生的事件序列；随后断言
 * 落库点经 `adoptDatabaseIfUnchanged` 校验后如实中止，且编辑仍在会话树中。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncMidCycleEditGuardTest {

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
        val provider: MidCycleEditProvider,
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
        val conflicts = SyncConflictController(databaseSession, codec, GUARD_TEST_STRINGS, session, debugLog)
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
            strings = GUARD_TEST_STRINGS
        )
        val provider = MidCycleEditProvider()
        val remotePath = "/remote/$key.kdbx"
        session.testSyncProvider = provider
        session.testRemotePath = remotePath
        return Fixture(databaseSession, cycle, codec, session, provider, remotePath)
    }

    private suspend fun createVault(fx: Fixture, key: String) {
        val created = fx.databaseSession.create(
            file = File(tempFolder.root, "$key.kdbx"),
            name = key,
            passwordChars = "MidCycleGuard#2026".toCharArray(),
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

    @Test
    fun `远端整库接管：下载窗口内的本地编辑不被覆盖（ISSUE-P2-278 AC③）`() = runTest(testDispatcher) {
        val fx = newFixture("takeover")
        createVault(fx, "takeover")

        // 第一轮：建立远端基线与本地缓存（远端内容 = L0）
        val baseline = fx.cycle.runSyncCycle()
        assertTrue("首轮应建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)

        // 远端推进到 L1（对端新增「远端新增」条目；会话树保持 L0 不动）
        val l0 = fx.databaseSession.databaseFlow.value!!
        val remoteEntry = entry("远端新增")
        val l1Bytes = fx.codec.serializeLocalDatabase(
            l0.copy(rootGroup = l0.rootGroup.copy(entries = l0.rootGroup.entries + remoteEntry))
        )!!
        fx.provider.upload(fx.remotePath, l1Bytes, null)

        // 第二轮：下载窗口内注入「用户编辑并保存」
        val midCycleEdit = entry("窗口内编辑")
        fx.provider.onNextDownload = {
            fx.databaseSession.saveEntry(midCycleEdit)
            fx.databaseSession.save()
        }
        val outcome = fx.cycle.runSyncCycle()

        assertTrue(
            "窗口内有本地编辑时必须如实中止（不得静默接管）: $outcome",
            outcome is SyncOutcome.Error
        )
        val titles = titlesOf(fx.databaseSession.databaseFlow.value)
        assertTrue("窗口内编辑必须仍在会话树中: $titles", "窗口内编辑" in titles)
        assertFalse("远端接管不得生效（会话不得含远端新增条目）: $titles", "远端新增" in titles)
    }

    @Test
    fun `三方合并落库：合并窗口内的本地编辑不被覆盖（ISSUE-P2-278 AC③ updateDatabaseMeta 侧）`() =
        runTest(testDispatcher) {
            val fx = newFixture("merge")
            createVault(fx, "merge")

            // 第一轮：建立远端基线（L0）
            val baseline = fx.cycle.runSyncCycle()
            assertTrue("首轮应建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)
            val l0 = fx.databaseSession.databaseFlow.value!!

            // 本地未同步修改（保持 DIRTY ⇒ 第二轮走快速提交的冲突支）＋ 远端独立推进
            val localEntry = entry("本地新增")
            fx.databaseSession.saveEntry(localEntry)
            val remoteEntry = entry("远端新增")
            val l1rBytes = fx.codec.serializeLocalDatabase(
                l0.copy(rootGroup = l0.rootGroup.copy(entries = l0.rootGroup.entries + remoteEntry))
            )!!
            fx.provider.upload(fx.remotePath, l1rBytes, null)

            // 第二轮：冲突下载窗口内再注入一次编辑（双侧无条目级冲突 ⇒ 直达自动合并落库点）
            val midCycleEdit = entry("合并窗口内编辑")
            fx.provider.onNextDownload = {
                fx.databaseSession.saveEntry(midCycleEdit)
                fx.databaseSession.save()
            }
            val outcome = fx.cycle.runSyncCycle()

            assertTrue(
                "合并落库前发现窗口内编辑必须如实中止: $outcome",
                outcome is SyncOutcome.Error
            )
            val titles = titlesOf(fx.databaseSession.databaseFlow.value)
            assertTrue("窗口内编辑必须仍在会话树中: $titles", "合并窗口内编辑" in titles)
            assertTrue("窗口前的本地修改必须仍在会话树中: $titles", "本地新增" in titles)
            assertFalse("过期合并产物不得落库（会话不得含远端新增条目）: $titles", "远端新增" in titles)
            val remoteTitles = titlesOf(
                com.keepasskey.database.file.KdbxFile.load(
                    fx.provider.remoteBytes(fx.remotePath).inputStream(),
                    "MidCycleGuard#2026".toCharArray(),
                    null
                )
            )
            assertFalse("中止时不得把过期合并产物推上云端: $remoteTitles", "本地新增" in remoteTitles)
        }

    @Test
    fun `采纳失败后第三轮重试：对端新增条目不被本地陈旧树覆盖（ISSUE-P2-308 AC②）`() =
        runTest(testDispatcher) {
            val fx = newFixture("adoptretry")
            createVault(fx, "adoptretry")

            // 第一轮：建立远端基线与本地缓存（远端内容 = L0）
            val baseline = fx.cycle.runSyncCycle()
            assertTrue("首轮应建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)

            // 远端推进到 L1（对端新增「远端新增」条目；会话树保持 L0 不动）
            val l0 = fx.databaseSession.databaseFlow.value!!
            val remoteEntry = entry("远端新增")
            val l1Bytes = fx.codec.serializeLocalDatabase(
                l0.copy(rootGroup = l0.rootGroup.copy(entries = l0.rootGroup.entries + remoteEntry))
            )!!
            fx.provider.upload(fx.remotePath, l1Bytes, null)

            // 第二轮：下载窗口内注入「用户编辑并保存」⇒ 校验-采用如实中止（SESSION_DIVERGED）
            val midCycleEdit = entry("窗口内编辑")
            fx.provider.onNextDownload = {
                fx.databaseSession.saveEntry(midCycleEdit)
                fx.databaseSession.save()
            }
            val diverged = fx.cycle.runSyncCycle()
            assertTrue("采纳失败必须如实中止: $diverged", diverged is SyncOutcome.Error)

            // 第三轮（AC②）：重试必须不丢失对端数据——整改前基线已在第二轮前移到远端内容，
            // 本轮会以「本地陈旧树 + 窗口内编辑」本地赢整库上传，把对端新增条目从云端抹掉；
            // 整改后基线未动，本轮按冲突三方合并收敛
            val outcome = fx.cycle.runSyncCycle()
            assertTrue(
                "第三轮应按冲突合并收敛成功: $outcome",
                outcome is SyncOutcome.MergedAndUploaded || outcome is SyncOutcome.UploadedLocal
            )

            val remoteTitles = titlesOf(
                com.keepasskey.database.file.KdbxFile.load(
                    fx.provider.remoteBytes(fx.remotePath).inputStream(),
                    "MidCycleGuard#2026".toCharArray(),
                    null
                )
            )
            assertTrue(
                "对端新增条目不得被本地陈旧树覆盖: $remoteTitles",
                "远端新增" in remoteTitles
            )
            assertTrue("本地窗口内编辑不得丢失: $remoteTitles", "窗口内编辑" in remoteTitles)

            val sessionTitles = titlesOf(fx.databaseSession.databaseFlow.value)
            assertTrue(
                "会话树应收敛到含双方改动的合并结果: $sessionTitles",
                "远端新增" in sessionTitles && "窗口内编辑" in sessionTitles
            )
        }

    /** 进程内假 Provider：支持「下一次下载前注入一次动作」的窗口编辑钩子。 */
    private class MidCycleEditProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

        /** 非 null 时在下一次 download 内执行一次后自动清除（模拟窗口内用户编辑）。 */
        var onNextDownload: (suspend () -> Unit)? = null

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
            // ETag 必须随内容变化（内容增长时 size 变化，保证引擎能观测到「远端已更新」）
            val etag = "etag_${data.size}_${data.fold(0) { acc, b -> (acc + b) and 0x7FFF }}"
            remote[remotePath] = Pair(data, etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }
}
