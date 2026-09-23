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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncMidCycleEditGuardTest` 同口径）。 */
private val BINDING_TEST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * ISSUE-P2-291 AC③：同步配置按库身份绑定——两库共用同一 `remotePath` 时，
 * 第二个库的同步被显式拦截（上传前中止），云端副本不被整库覆盖，缓存与基线互不串用。
 *
 * ## 判据
 *
 * - 库 A 首轮同步：远端为空 ⇒ 建立基线（`UploadedLocal`），登记归属 = A 的根组 UUID；
 * - 库 B（另一 DatabaseSession，根组 UUID 必然不同）同步同一路径：装配段绑定闸
 *   **在任何网络写之前**返回 `VaultBindingMismatch`，远端字节仍为 A 的内容；
 * - B 经用户显式确认（`takeoverVaultBinding`）：改绑 + 整库覆盖上传；随后 A 再同步
 *   被同一闸反向拦截。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncVaultBindingTest {

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

    private class FakeSyncProvider : SyncProvider {

        val remote = mutableMapOf<String, Pair<ByteArray, String>>()

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

    private data class Harness(
        val context: ContextWrapper,
        val bindingStore: SyncVaultBindingStore,
        val provider: FakeSyncProvider,
        val remotePath: String,
        val cacheDir: File
    )

    /** 共享同一远端 / 绑定登记 / 缓存目录的装配环境（模拟同一同步配置下先后打开两库）。 */
    private fun newHarness(key: String): Harness {
        val cacheDir = File(tempFolder.root, "$key-cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "$key-files").apply { mkdirs() }
        val prefs = InMemorySharedPreferences()
        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.prefs
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        val provider = FakeSyncProvider()
        val remotePath = "/remote/$key.kdbx"
        val bindingStore = SyncVaultBindingStore(fakeContext)
        return Harness(fakeContext, bindingStore, provider, remotePath, cacheDir)
    }

    /** 在共享环境上为指定库装配周期（模拟「这个库打开时执行同步」）。 */
    private fun newCycle(harness: Harness, databaseSession: DatabaseSession): SyncCycleRunner {
        val debugLog = DebugLogBuffer()
        val session = SyncSessionState().apply {
            testSyncProvider = harness.provider
            testRemotePath = harness.remotePath
        }
        val preferences = SyncPreferences(debugLog, null)
        val credentialsStore = SyncCredentialsStore(harness.context, null)
        val codec = SyncDatabaseCodec(databaseSession, debugLog)
        return SyncCycleRunner(
            context = harness.context,
            databaseSession = databaseSession,
            session = session,
            providerResolver = SyncProviderResolver(credentialsStore, preferences, debugLog),
            codec = codec,
            conflicts = SyncConflictController(databaseSession, codec, BINDING_TEST_STRINGS, session, debugLog),
            changes = SyncContentChangeDetector(codec, session),
            preferences = preferences,
            strings = BINDING_TEST_STRINGS,
            vaultBindingStore = harness.bindingStore
        )
    }

    private suspend fun createVault(harness: Harness, key: String, marker: String): DatabaseSession {
        val databaseSession = DatabaseSession()
        val created = databaseSession.create(
            file = File(tempFolder.root, "$key-${marker}.kdbx"),
            name = marker,
            passwordChars = "VaultBinding#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue("夹具建库失败: $created", created is KdbxResult.Success)
        return databaseSession
    }

    private fun markerEntry(marker: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(marker, false))
    )

    private fun titlesOf(db: com.keepasskey.database.file.KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.TITLE]?.readString() }
            .toSet()

    @Test
    fun `两库共用同一远端路径_第二库上传前被拦截_云端副本不被覆盖`() = runTest(testDispatcher) {
        val harness = newHarness("binding")
        val sessionA = createVault(harness, "binding", "库A")
        sessionA.saveEntry(markerEntry("A的条目"))
        val cycleA = newCycle(harness, sessionA)

        // 库 A 首轮：远端为空 ⇒ 建立基线并登记归属
        val outcomeA = cycleA.runSyncCycle()
        assertTrue("库 A 首轮应建立基线: $outcomeA", outcomeA is SyncOutcome.UploadedLocal)
        assertEquals(
            "归属登记应为库 A 的根组 UUID",
            sessionA.databaseFlow.value!!.rootGroup.id.toHexString(),
            harness.bindingStore.loadBinding(harness.remotePath)
        )

        // 库 B（同一路径）：上传前被绑定闸拦截
        val sessionB = createVault(harness, "binding", "库B")
        sessionB.saveEntry(markerEntry("B的条目"))
        val cycleB = newCycle(harness, sessionB)
        val outcomeB = cycleB.runSyncCycle()
        assertTrue(
            "库 B 必须被绑定闸拦截: $outcomeB",
            outcomeB is SyncOutcome.VaultBindingMismatch
        )

        // 云端副本仍是库 A 的内容（AC③ 主判据：不被整库覆盖）
        val remoteTitles = titlesOf(
            com.keepasskey.database.file.KdbxFile.load(
                harness.provider.remote.getValue(harness.remotePath).first.inputStream(),
                "VaultBinding#2026".toCharArray(),
                null
            )
        )
        assertTrue("云端副本必须仍是库 A 的内容: $remoteTitles", "A的条目" in remoteTitles)
        assertFalse("库 B 不得把云端副本覆盖为自己的内容: $remoteTitles", "B的条目" in remoteTitles)
    }

    @Test
    fun `确认接管后改绑并整库覆盖_原归属库再同步被反向拦截`() = runTest(testDispatcher) {
        val harness = newHarness("takeover")
        val sessionA = createVault(harness, "takeover", "库A")
        sessionA.saveEntry(markerEntry("A的条目"))
        val cycleA = newCycle(harness, sessionA)
        assertTrue(cycleA.runSyncCycle() is SyncOutcome.UploadedLocal)

        val sessionB = createVault(harness, "takeover", "库B")
        sessionB.saveEntry(markerEntry("B的条目"))
        val cycleB = newCycle(harness, sessionB)
        assertTrue(cycleB.runSyncCycle() is SyncOutcome.VaultBindingMismatch)

        // 用户显式确认：改绑 + 整库覆盖
        val takeover = cycleB.takeoverVaultBinding()
        assertTrue("确认接管应整库上传成功: $takeover", takeover is SyncOutcome.UploadedLocal)
        assertEquals(
            "归属登记应改绑为库 B",
            sessionB.databaseFlow.value!!.rootGroup.id.toHexString(),
            harness.bindingStore.loadBinding(harness.remotePath)
        )
        val remoteTitles = titlesOf(
            com.keepasskey.database.file.KdbxFile.load(
                harness.provider.remote.getValue(harness.remotePath).first.inputStream(),
                "VaultBinding#2026".toCharArray(),
                null
            )
        )
        assertTrue("云端副本应已替换为库 B 的内容: $remoteTitles", "B的条目" in remoteTitles)

        // 库 A 再同步：被同一闸反向拦截
        val outcomeA2 = cycleA.runSyncCycle()
        assertTrue("原归属库再同步应被反向拦截: $outcomeA2", outcomeA2 is SyncOutcome.VaultBindingMismatch)
    }

    @Test
    fun `同库连续同步放行_缓存与基线落库身份键下_与另一库互不串用`() = runTest(testDispatcher) {
        val harness = newHarness("scope")
        val sessionA = createVault(harness, "scope", "库A")
        val cycleA = newCycle(harness, sessionA)
        assertTrue(cycleA.runSyncCycle() is SyncOutcome.UploadedLocal)

        // 同库第二轮：绑定一致 ⇒ 正常放行（远端与本地一致 ⇒ UpToDate）
        val outcomeA2 = cycleA.runSyncCycle()
        assertTrue("同库连续同步必须放行: $outcomeA2", outcomeA2 is SyncOutcome.UpToDate)

        // 库身份键下的缓存文件存在（SHA-256("scope\npath") 命名），旧无命名空间键已迁移
        // （缓存实际落点为 cacheDir/<SyncCache.CACHE_DIR_NAME>/，与生产装配一致）
        val scope = sessionA.databaseFlow.value!!.rootGroup.id.toHexString()
        val scopedKey = SyncCache.sha256Hex("${scope}\n${harness.remotePath}".toByteArray(Charsets.UTF_8))
        val legacyKey = SyncCache.sha256Hex(harness.remotePath.toByteArray(Charsets.UTF_8))
        val syncDir = File(harness.cacheDir, SyncCache.CACHE_DIR_NAME)
        assertTrue(
            "库身份键下必须存在缓存文件",
            File(syncDir, "$scopedKey.cache").exists()
        )
        assertFalse(
            "旧无命名空间键必须已被迁移（不得残留被另一库串用）",
            File(syncDir, "$legacyKey.cache").exists()
        )
        assertNotEquals("库身份键与旧键必须不同（隔离生效前提）", scopedKey, legacyKey)
    }
}
