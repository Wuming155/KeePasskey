package com.keepasskey.app.sync

import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.testutil.InMemorySharedPreferences
import android.content.Context
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.security.AutoLockHoldoff
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.FieldResolutionChoice
import com.keepasskey.sync.engine.RollbackVerdict
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** TASK-21：用户可见消息已资源化；单测无资源环境，注入返回占位文本的假 StringsProvider */
private val TEST_STRINGS = com.keepasskey.app.ui.model.StringsProvider { _, _ -> "" }

/**
 * SyncCoordinator 全分支同步状态机测试 (Wave 3-E P0-5 与 E5 验收标准)。
 *
 * `@OptIn` 必须落在**类**上：`Dispatchers.setMain` / `resetMain` 为
 * `@ExperimentalCoroutinesApi`；此前注解误挂在顶层 `TEST_STRINGS` 属性上，
 * 故 `setUp` / `tearDown` 两处调用各产生一条 opt-in 告警。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeContext: Context
    private lateinit var databaseSession: DatabaseSession
    private lateinit var credentialsStore: SyncCredentialsStore
    private lateinit var coordinator: SyncCoordinator
    private val masterPassword = "SyncMasterPassword#2026".toCharArray()

    private class MemorySyncProvider : SyncProvider {
        val remoteStorage = mutableMapOf<String, Pair<ByteArray, String>>()
        var isReachable = true
        var metadataCalls: Int = 0
        var downloadCalls: Int = 0
        var uploadCalls: Int = 0
        var uploadStarted: CompletableDeferred<Unit>? = null
        var continueUpload: CompletableDeferred<Unit>? = null

        // ISSUE-P0-09：模拟「provider 的 runCatching 捕到 Error 后包成 Result.failure」——
        // 与真实 WebDavSyncProvider/S3SyncProvider 在超深 XML / 超大响应下的失败形态同构
        var downloadFailure: Throwable? = null

        // ISSUE-P1-275 AC④：最近一次 upload 收到的期望 ETag（断言预条件真实传递）
        var lastExpectedEtag: String? = null
            private set

        // ISSUE-P2-402：注入 getMetadata 的失败类型（探测期分类映射的行为级判据）
        var metadataFailure: Throwable? = null

        // ISSUE-P1-275 AC③：接下来 N 次 upload 一律 412（模拟合并窗口内远端持续被他人修改）
        var forceConflictUploads: Int = 0

        override suspend fun testConnection(): Result<Unit> {
            return if (isReachable) Result.success(Unit) else Result.failure(IOException("Server unreachable"))
        }

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            metadataCalls++
            metadataFailure?.let { return Result.failure(it) }
            if (!isReachable) return Result.failure(SyncException.NetworkError("Network error"))
            val item = remoteStorage[remotePath] ?: return Result.failure(
                SyncException.FileNotFound("File not found: $remotePath")
            )
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = System.currentTimeMillis(),
                    etag = item.second
                )
            )
        }

        // ISSUE-P3-206 流式契约：内容边读边写进 sink
        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            downloadCalls++
            downloadFailure?.let { return Result.failure(it) }
            if (!isReachable) return Result.failure(SyncException.NetworkError("Network error"))
            val item = remoteStorage[remotePath] ?: return Result.failure(
                SyncException.FileNotFound("File not found")
            )
            sink.write(item.first)
            return Result.success(Unit)
        }

        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> {
            uploadCalls++
            uploadStarted?.complete(Unit)
            continueUpload?.await()
            lastExpectedEtag = expectedEtag
            if (!isReachable) return Result.failure(SyncException.NetworkError("Network error"))
            val existing = remoteStorage[remotePath]
            if (forceConflictUploads > 0) {
                forceConflictUploads--
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing?.second.orEmpty(),
                        localExpectedEtag = expectedEtag.orEmpty(),
                        message = "Forced precondition failed"
                    )
                )
            }
            if (expectedEtag != null && existing != null && existing.second != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing.second,
                        localExpectedEtag = expectedEtag,
                        message = "Precondition Failed"
                    )
                )
            }
            val newEtag = "etag_${System.currentTimeMillis()}_${data.size}"
            remoteStorage[remotePath] = Pair(data.clone(), newEtag)
            return Result.success(newEtag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteStorage.remove(remotePath)
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)

        val cacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "files").apply { mkdirs() }
        val sharedPreferences = InMemorySharedPreferences()

        fakeContext = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = cacheDir
            override fun getFilesDir(): File = filesDir
            override fun getSharedPreferences(name: String?, mode: Int) = sharedPreferences.prefs
            override fun getApplicationContext(): Context = this
        }

        databaseSession = DatabaseSession()
        val dbFile = File(filesDir, "active_sync_vault.kdbx")
        val createResult = databaseSession.create(
            file = dbFile,
            name = "ActiveSyncVault",
            passwordChars = masterPassword,
            useArgon2 = false // 快速单元测试
        )
        assertTrue(createResult is com.keepasskey.core.result.KdbxResult.Success)

        credentialsStore = SyncCredentialsStore(fakeContext, null)
        coordinator = SyncCoordinator(fakeContext, databaseSession, credentialsStore, com.keepasskey.app.data.logger.DebugLogBuffer(), TEST_STRINGS)
    }

    @Test
    fun `锁定周期只探测元数据并持久记录待解锁状态`() = runTest(testDispatcher) {
        val provider = MemorySyncProvider()
        val remotePath = "/remote/locked-probe.kdbx"
        val remoteBytes = (databaseSession.exportToBytes() as com.keepasskey.core.result.KdbxResult.Success).data
        provider.remoteStorage[remotePath] = remoteBytes.clone() to "\"etag-a\""
        val vaultIdentity = requireNotNull(databaseSession.currentPathIdentifier)
        val probeStore = PeriodicSyncProbeStore(InMemorySharedPreferences().context())
        val session = SyncSessionState().apply {
            testSyncProvider = provider
            testRemotePath = remotePath
        }
        val periodicCoordinator = SyncCoordinator(
            fakeContext,
            databaseSession,
            credentialsStore,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            TEST_STRINGS,
            syncSession = session,
            periodicSyncProbeStore = probeStore
        )
        try {
            databaseSession.lock()

            val first = periodicCoordinator.runPeriodicSync()

            assertEquals(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.INITIAL_BASELINE_REQUIRED),
                first
            )
            assertEquals("锁态仅请求一次元数据", 1, provider.metadataCalls)
            assertEquals("锁态不得下载 KDBX", 0, provider.downloadCalls)
            assertEquals("锁态不得上传", 0, provider.uploadCalls)
            val stored = probeStore.read(vaultIdentity) as PeriodicSyncProbeRead.Stored
            assertEquals("etag-a", stored.record.observedEtag)
            assertTrue(stored.record.needsUnlock)

            assertEquals(
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.PENDING_UNLOCK),
                periodicCoordinator.runPeriodicSync()
            )
            assertEquals("待解锁状态不能继续发网络请求", 1, provider.metadataCalls)
            assertEquals("锁定态不能修改正式库", null, databaseSession.databaseFlow.value)
        } finally {
            remoteBytes.fill(0)
            provider.remoteStorage.values.forEach { it.first.fill(0) }
            provider.remoteStorage.clear()
            databaseSession.close()
        }
    }

    @Test
    fun `锁定Worker条件上传持久密文并在解锁后安全收敛`() = runTest(testDispatcher) {
        val provider = MemorySyncProvider()
        val remotePath = "/remote/pending-ciphertext.kdbx"
        val vaultFile = requireNotNull(databaseSession.currentFile)
        val vaultIdentity = requireNotNull(databaseSession.currentPathIdentifier)
        com.keepasskey.app.data.repository.ActiveDatabaseIdStore(fakeContext).save(vaultIdentity)
        databaseSession.saveEntry(
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("locked queue entry", false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Synthetic-Queue-Password-A", true)
                )
            )
        )
        assertTrue(databaseSession.save().isSuccess)
        val versionA = vaultFile.readBytes()
        val contentDigestA = SyncCache.sha256Hex(versionA)
        val rootScope = requireNotNull(databaseSession.databaseFlow.value).rootGroup.id.toHexString()
        provider.remoteStorage[remotePath] = versionA.clone() to "etag-a"
        SyncRollbackGuard(File(fakeContext.filesDir, SyncRollbackGuard.STATE_DIR_NAME), rootScope)
            .recordAccepted(remotePath, contentDigestA)

        databaseSession.saveEntry(
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("pending queue entry", false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Synthetic-Queue-Password-B", true)
                )
            )
        )
        assertTrue(databaseSession.save().isSuccess)
        val versionB = vaultFile.readBytes()
        val contentDigestB = SyncCache.sha256Hex(versionB)
        assertFalse("合成库两个修订必须不同", contentDigestA == contentDigestB)
        // 与生产锁态探测相同的两层身份派生：目标摘要由库身份与配置目标组成，再绑定库身份。
        val probeTargetIdentity = digestIdentity(vaultIdentity, digestIdentity(vaultIdentity, remotePath))
        val probeStore = PeriodicSyncProbeStore(InMemorySharedPreferences().context())
        assertTrue(
            probeStore.write(
                vaultIdentity,
                PeriodicSyncProbeRecord(
                    targetIdentity = probeTargetIdentity,
                    observedEtag = "etag-a",
                    needsUnlock = false,
                    syncedContentDigest = contentDigestA,
                    rollbackVaultScope = rootScope
                )
            )
        )
        val session = SyncSessionState().apply {
            testSyncProvider = provider
            testRemotePath = remotePath
        }
        provider.forceConflictUploads = 1
        val periodicCoordinator = SyncCoordinator(
            fakeContext,
            DatabaseSession(),
            credentialsStore,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            TEST_STRINGS,
            syncSession = session,
            periodicSyncProbeStore = probeStore
        )

        try {
            databaseSession.lock()
            databaseSession.close()
            val lockedResult = periodicCoordinator.runPeriodicSync()

            assertEquals(
                "412 冲突须保留密文快照并转为待解锁/可重试状态",
                PeriodicSyncWorkResult.NeedsUnlock(PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED),
                lockedResult
            )
            assertEquals("冲突的首次 PUT 不得改写远端", "etag-a", provider.remoteStorage[remotePath]?.second)
            assertTrue(
                "冲突后待传快照仍须持久化",
                PeriodicSyncPendingUploadStore(
                    File(fakeContext.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                ).hasEntry(vaultIdentity)
            )

            val retryResult = periodicCoordinator.runPeriodicSync()

            assertEquals(
                "重试必须复用同一密文快照并完成受条件约束的上传",
                PeriodicSyncWorkResult.EncryptedUploadCompleted,
                retryResult
            )
            assertEquals("冲突后重试前必须重新探测 ETag", 2, provider.metadataCalls)
            assertEquals(2, provider.uploadCalls)
            assertEquals("上传必须绑定原远端 ETag", "etag-a", provider.lastExpectedEtag)
            assertArrayEquals("远端应收到锁定前已落盘的密文修订 B", versionB, provider.remoteStorage[remotePath]?.first)
            val settled = (probeStore.read(vaultIdentity) as PeriodicSyncProbeRead.Stored).record
            assertEquals(provider.remoteStorage[remotePath]?.second, settled.observedEtag)
            assertEquals(contentDigestB, settled.syncedContentDigest)
            assertEquals(rootScope, settled.rollbackVaultScope)
            assertFalse(settled.needsUnlock)

            val guard = SyncRollbackGuard(File(fakeContext.filesDir, SyncRollbackGuard.STATE_DIR_NAME), rootScope)
            assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, contentDigestB))
            assertEquals(RollbackVerdict.ReplayDetected, guard.inspect(remotePath, contentDigestA))
            assertFalse(
                "完成上传后必须清除锁态密文队列",
                PeriodicSyncPendingUploadStore(
                    File(fakeContext.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                ).hasEntry(vaultIdentity)
            )

            val restartedSession = DatabaseSession()
            assertTrue(restartedSession.open(vaultFile, masterPassword.clone()) is com.keepasskey.core.result.KdbxResult.Success)
            val unlockedCoordinator = SyncCoordinator(
                fakeContext,
                restartedSession,
                credentialsStore,
                com.keepasskey.app.data.logger.DebugLogBuffer(),
                TEST_STRINGS,
                syncSession = SyncSessionState().apply {
                    testSyncProvider = provider
                    testRemotePath = remotePath
                },
                periodicSyncProbeStore = probeStore
            )
            val unlockedResult = unlockedCoordinator.runPeriodicSync()
            assertTrue("解锁后完整同步必须收敛且不能覆盖 B: $unlockedResult", unlockedResult is PeriodicSyncWorkResult.FullSync)
            val unlockedDatabase = requireNotNull(restartedSession.databaseFlow.value)
            assertTrue(
                "解锁收敛后正式库仍应保留 B 的条目",
                unlockedDatabase.rootGroup.entries.any { entry ->
                    entry.fields[KdbxConstants.Fields.TITLE]?.readString() == "pending queue entry"
                }
            )
        } finally {
            versionA.fill(0)
            versionB.fill(0)
            provider.remoteStorage.values.forEach { it.first.fill(0) }
            provider.remoteStorage.clear()
            databaseSession.close()
        }
    }

    @Test
    fun `冷启动Worker用持久密文队列对账服务端已提交但本地未收尾的上传`() = runTest(testDispatcher) {
        val provider = MemorySyncProvider()
        val remotePath = "/remote/pending-ciphertext-reconcile.kdbx"
        val vaultFile = requireNotNull(databaseSession.currentFile)
        val vaultIdentity = requireNotNull(databaseSession.currentPathIdentifier)
        com.keepasskey.app.data.repository.ActiveDatabaseIdStore(fakeContext).save(vaultIdentity)
        databaseSession.saveEntry(
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("revision-a", false))
            )
        )
        assertTrue(databaseSession.save().isSuccess)
        val versionA = vaultFile.readBytes()
        val digestA = SyncCache.sha256Hex(versionA)
        val scope = requireNotNull(databaseSession.databaseFlow.value).rootGroup.id.toHexString()
        val guardDir = File(fakeContext.filesDir, SyncRollbackGuard.STATE_DIR_NAME)
        SyncRollbackGuard(guardDir, scope).recordAccepted(remotePath, digestA)

        databaseSession.saveEntry(
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("revision-b", false))
            )
        )
        assertTrue(databaseSession.save().isSuccess)
        val versionB = vaultFile.readBytes()
        val digestB = SyncCache.sha256Hex(versionB)
        val targetIdentity = digestIdentity(vaultIdentity, digestIdentity(vaultIdentity, remotePath))
        val probeStore = PeriodicSyncProbeStore(InMemorySharedPreferences().context())
        assertTrue(
            probeStore.write(
                vaultIdentity,
                PeriodicSyncProbeRecord(
                    targetIdentity,
                    "etag-a",
                    needsUnlock = true,
                    syncedContentDigest = digestA,
                    rollbackVaultScope = scope
                )
            )
        )
        val pendingStore = PeriodicSyncPendingUploadStore(
            File(fakeContext.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
        )
        val queued = requireNotNull(
            pendingStore.stage(
                vaultIdentity,
                targetIdentity,
                remotePath,
                "etag-a",
                scope,
                ByteArrayInputStream(versionB)
            )
        )
        // 模拟远端已完成 PUT，但进程在本地高水位/ETag 收尾前死亡。
        SyncRollbackGuard(guardDir, scope).beginAcceptance(remotePath, digestB)
        provider.remoteStorage[remotePath] = versionB.clone() to "etag-b"
        databaseSession.close()

        val coldCoordinator = SyncCoordinator(
            fakeContext,
            DatabaseSession(),
            credentialsStore,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            TEST_STRINGS,
            syncSession = SyncSessionState().apply {
                testSyncProvider = provider
                testRemotePath = remotePath
            },
            periodicSyncProbeStore = probeStore
        )
        try {
            val recovered = coldCoordinator.runPeriodicSync()

            assertEquals(PeriodicSyncWorkResult.EncryptedUploadCompleted, recovered)
            assertEquals("已提交的远端修订应通过密文摘要核验，不需重复 PUT", 0, provider.uploadCalls)
            assertEquals("重启恢复仅通过远端下载对账", 1, provider.downloadCalls)
            assertEquals(RollbackVerdict.Unchanged, SyncRollbackGuard(guardDir, scope).inspect(remotePath, digestB))
            assertEquals(RollbackVerdict.ReplayDetected, SyncRollbackGuard(guardDir, scope).inspect(remotePath, digestA))
            val stored = (probeStore.read(vaultIdentity) as PeriodicSyncProbeRead.Stored).record
            assertEquals("etag-b", stored.observedEtag)
            assertEquals(digestB, stored.syncedContentDigest)
            assertFalse(stored.needsUnlock)
            assertEquals(queued.contentDigest, stored.syncedContentDigest)
            assertFalse(pendingStore.hasEntry(vaultIdentity))
        } finally {
            versionA.fill(0)
            versionB.fill(0)
            provider.remoteStorage.values.forEach { it.first.fill(0) }
            provider.remoteStorage.clear()
            databaseSession.close()
        }
    }

    @Test
    fun `正式库B已落盘而高水位提交失败后重建协调器不得接受远端A`() = runTest(testDispatcher) {
        val provider = MemorySyncProvider()
        val remotePath = "/remote/p2-621-formal-vault.kdbx"
        val vaultFile = requireNotNull(databaseSession.currentFile)
        coordinator.testSyncProvider = provider
        coordinator.testRemotePath = remotePath
        assertTrue("初始 A 必须上传并建立已接受基线", coordinator.syncNow() is SyncOutcome.UploadedLocal)
        val remoteA = requireNotNull(provider.remoteStorage[remotePath]).first.clone()

        val passwordForRead = masterPassword.clone()
        val remoteDatabase = try {
            KdbxFile.load(ByteArrayInputStream(remoteA), passwordForRead)
        } finally {
            passwordForRead.fill('\u0000')
        }
        val remoteBEntry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("formal revision B", false))
        )
        val passwordForWrite = masterPassword.clone()
        val remoteB = try {
            ByteArrayOutputStream().also { output ->
                KdbxFile.save(
                    output,
                    remoteDatabase.copy(rootGroup = remoteDatabase.rootGroup.copy(
                        entries = remoteDatabase.rootGroup.entries + remoteBEntry
                    )),
                    passwordForWrite
                )
            }.toByteArray()
        } finally {
            passwordForWrite.fill('\u0000')
        }
        remoteDatabase.clearSensitiveData()
        val remoteBDigest = SyncCache.sha256Hex(remoteB)
        provider.remoteStorage[remotePath] = remoteB.clone() to "etag-b"

        var injectHighWaterFailure = true
        val failureLog = com.keepasskey.app.data.logger.DebugLogBuffer()
        val preferences = SyncPreferences(failureLog, null)
        val sessionState = SyncSessionState().apply {
            testSyncProvider = provider
            testRemotePath = remotePath
        }
        val resolver = SyncProviderResolver(credentialsStore, preferences, failureLog)
        val codec = SyncDatabaseCodec(databaseSession, failureLog)
        val conflicts = SyncConflictController(databaseSession, codec, TEST_STRINGS, sessionState, failureLog)
        val changes = SyncContentChangeDetector(codec, sessionState)
        val rollbackDir = File(fakeContext.filesDir, SyncRollbackGuard.STATE_DIR_NAME)
        val cycle = SyncCycleRunner(
            fakeContext,
            databaseSession,
            sessionState,
            resolver,
            codec,
            conflicts,
            changes,
            preferences,
            TEST_STRINGS,
            rollbackStateDir = rollbackDir
        ).apply {
            syncCacheFactory = { SyncCache(it) }
            rollbackGuardFactoryForTest = { stateDir, scope, identity ->
                SyncRollbackGuard(stateDir, scope, { next ->
                    if (injectHighWaterFailure && next.current == remoteBDigest && next.pendingDigest == null) {
                        injectHighWaterFailure = false
                        throw IOException("injected high-water replacement failure")
                    }
                }, identity)
            }
        }
        val failingCoordinator = SyncCoordinator(
            fakeContext,
            databaseSession,
            credentialsStore,
            failureLog,
            TEST_STRINGS,
            syncSession = sessionState,
            syncPreferences = preferences,
            syncProviderResolver = resolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        )

        try {
            val failed = failingCoordinator.syncNow()
            assertTrue("高水位提交故障必须报告失败", failed is SyncOutcome.Error)
            assertTrue(
                "没有周期探测记录时，待采纳意图也必须能通过库身份被恢复 UI 查询到",
                failingCoordinator.hasPendingUserRecovery(vaultFile.absolutePath)
            )
            assertTrue(
                "远端 B 已采用并保存到正式 KDBX 文件",
                vaultFile.readBytes().let { bytes ->
                    val verifyPassword = masterPassword.clone()
                    try {
                        KdbxFile.load(ByteArrayInputStream(bytes), verifyPassword)
                            .useDatabase { db -> db.rootGroup.allEntries().any { it.title == "formal revision B" } }
                    } finally {
                        verifyPassword.fill('\u0000')
                        bytes.fill(0)
                    }
                }
            )

            // 模拟进程结束：丢弃旧会话与协调器，从正式文件和持久目录创建全新对象。
            databaseSession.close()
            val restartedSession = DatabaseSession()
            val restartPassword = masterPassword.clone()
            val reopened = try {
                restartedSession.open(vaultFile, restartPassword)
            } finally {
                restartPassword.fill('\u0000')
            }
            assertTrue("重建后必须能从正式 KDBX 解锁 B", reopened is com.keepasskey.core.result.KdbxResult.Success)
            val restartedCoordinator = SyncCoordinator(
                fakeContext,
                restartedSession,
                credentialsStore,
                com.keepasskey.app.data.logger.DebugLogBuffer(),
                TEST_STRINGS,
                syncSession = SyncSessionState().apply {
                    testSyncProvider = provider
                    testRemotePath = remotePath
                }
            )
            provider.remoteStorage[remotePath] = remoteA.clone() to "etag-a-replayed"
            val resumed = restartedCoordinator.syncNow()
            assertTrue("持久 quarantine 必须阻止重建后自动采用 A：$resumed", resumed is SyncOutcome.Error)
            assertTrue(
                "远端 A 重放后正式 KDBX 仍必须包含 B",
                restartedSession.databaseFlow.value!!.rootGroup.allEntries()
                    .any { it.title == "formal revision B" }
            )
            val restartedGuard = SyncRollbackGuard(rollbackDir)
            assertEquals(
                "B 的待完成采纳意图应跨协调器重建保留隔离",
                RollbackVerdict.RecoveryRequired,
                restartedGuard.inspect(remotePath, remoteBDigest)
            )
            restartedSession.close()
        } finally {
            remoteA.fill(0)
            remoteB.fill(0)
            provider.remoteStorage.values.forEach { it.first.fill(0) }
            provider.remoteStorage.clear()
            databaseSession.close()
        }
    }

    private inline fun <T> KdbxDatabase.useDatabase(block: (KdbxDatabase) -> T): T = try {
        block(this)
    } finally {
        clearSensitiveData()
    }

    private fun digestIdentity(vaultIdentity: String, targetIdentity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$vaultIdentity\u0000$targetIdentity".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `用户裁决上传网络等待不持有自动锁定挂锁`() = runTest(testDispatcher) {
        val provider = MemorySyncProvider()
        val remotePath = "/remote/conflict-lock.kdbx"
        val settings = ExtendedSettingsStore(null).apply {
            publish(ExtendedSettings(conflictResolution = ConflictResolution.PROMPT_USER))
        }
        val debugLog = com.keepasskey.app.data.logger.DebugLogBuffer()
        val holdoff = AutoLockHoldoff()
        val guard = AutoLockSessionGuard(
            databaseSession,
            FakeSettingsRepository(),
            debugLog,
            holdoff = holdoff
        )
        val session = SyncSessionState().apply {
            testSyncProvider = provider
            testRemotePath = remotePath
        }
        val guardedCoordinator = SyncCoordinator(
            fakeContext,
            databaseSession,
            credentialsStore,
            debugLog,
            TEST_STRINGS,
            extendedSettingsStore = settings,
            syncSession = session,
            autoLockGuard = guard
        )
        val entryId = KdbxUuid.random()
        val baseEntry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("lock test", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Base#Password1", true)
            )
        )
        databaseSession.saveEntry(baseEntry)
        assertTrue(databaseSession.save().isSuccess)
        assertTrue(guardedCoordinator.syncNow() is SyncOutcome.UploadedLocal)

        databaseSession.saveEntry(
            baseEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("Local#Password2", true))
        )
        assertTrue(databaseSession.save().isSuccess)
        val remoteBase = requireNotNull(databaseSession.databaseFlow.value)
        val remoteEntry = baseEntry.withField(
            KdbxConstants.Fields.PASSWORD,
            ProtectedString("Remote#Password3", true)
        )
        val remoteRoot = remoteBase.rootGroup.copy(
            entries = remoteBase.rootGroup.entries.map { if (it.id == entryId) remoteEntry else it }
        )
        val remoteBytes = ByteArrayOutputStream().also { output ->
            KdbxFile.save(output, remoteBase.copy(rootGroup = remoteRoot), masterPassword)
        }.toByteArray()
        provider.remoteStorage[remotePath] = remoteBytes to "etag-remote-conflict"
        val detected = guardedCoordinator.syncNow()
        assertTrue("并发修改应先进入待决冲突: $detected", detected is SyncOutcome.ConflictNeedsUser)

        val uploadStarted = CompletableDeferred<Unit>()
        val continueUpload = CompletableDeferred<Unit>()
        provider.uploadStarted = uploadStarted
        provider.continueUpload = continueUpload
        val resolution = async {
            guardedCoordinator.resolveConflicts(
                mapOf(entryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
            )
        }
        uploadStarted.await()

        assertFalse("云端上传等待阶段不得持有自动锁定挂锁", holdoff.isHolding)
        val remoteEtagBeforeLock = provider.remoteStorage[remotePath]?.second
        assertTrue(
            "上传网络等待期间的锁定应立即完成",
            guard.triggerLock("冲突上传慢响应期间锁定") != com.keepasskey.app.security.LockOutcome.Deferred
        )
        val resolutionFailure = runCatching { resolution.await() }.exceptionOrNull()
        assertTrue("锁库必须取消进行中的冲突上传", resolutionFailure is CancellationException)
        assertEquals("取消后的冲突上传不得改写远端对象", remoteEtagBeforeLock, provider.remoteStorage[remotePath]?.second)
        assertEquals(DatabaseSession.SessionState.LOCKED, databaseSession.state.value)
        assertFalse("结束后挂锁计数必须归零", holdoff.isHolding)
        remoteBytes.fill(0)
        provider.remoteStorage.values.forEach { it.first.fill(0) }
        provider.remoteStorage.clear()
    }

    @After
    fun tearDown() {
        masterPassword.fill('0')
        // Main 采用「只装不卸」口径（ISSUE-P3-189 路线①）：此处不 resetMain()，
        // 收尾统一走 MainDispatcherGuard（见其类 KDoc 的实测反证）。
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `测试 SyncOutcome_UploadedLocal 与 SyncOutcome_UpToDate 往返`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_test1.kdbx"

        // 1. 远端尚无文件时首次同步 -> 触发自愈上传
        val outcome1 = coordinator.syncNow()
        assertTrue("首次应自动上传本地: $outcome1", outcome1 is SyncOutcome.UploadedLocal)
        assertEquals(1, memoryProvider.remoteStorage.size)

        // 2. 再次执行同步，双方哈希一致 -> 判定为 UpToDate
        val outcome2 = coordinator.syncNow()
        assertTrue("数据未变更时应判定为 UpToDate: $outcome2", outcome2 is SyncOutcome.UpToDate)
    }

    @Test
    fun `测试网络不可达时降级为 SyncOutcome_Offline`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_test2.kdbx"

        // 首次同步使本地缓存就绪
        coordinator.syncNow()

        // 模拟断网
        memoryProvider.isReachable = false

        val outcome = coordinator.syncNow()
        assertTrue("断网且有本地缓存时应降级为 Offline: $outcome", outcome is SyncOutcome.Offline)
    }

    // ===== ISSUE-P2-402：首传探测（cached=false 的 getMetadata）失败按异常类型如实归类 =====

    @Test
    fun `首传探测鉴权失败归类为Error而非Offline`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_p2_402_auth.kdbx"
        memoryProvider.metadataFailure = SyncException.AuthenticationError("401 Unauthorized")

        val outcome = coordinator.syncNow()
        assertTrue(
            "鉴权失败必须报 Error（整改前一律误报 Offline）: $outcome",
            outcome is SyncOutcome.Error
        )
    }

    @Test
    fun `首传探测限流失败归类为Error而非Offline`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_p2_402_rate.kdbx"
        memoryProvider.metadataFailure = SyncException.ProtocolError(503, "Service Unavailable")

        val outcome = coordinator.syncNow()
        assertTrue(
            "限流（503）必须报 Error（整改前一律误报 Offline）: $outcome",
            outcome is SyncOutcome.Error
        )
    }

    @Test
    fun `首传探测网络错误仍归类为Offline`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = "/remote/vault_p2_402_net.kdbx"
        memoryProvider.metadataFailure = SyncException.NetworkError("Connection reset")

        val outcome = coordinator.syncNow()
        assertTrue("真网络错误仍应报 Offline: $outcome", outcome is SyncOutcome.Offline)
    }

    @Test
    fun `测试provider重抛的Error被遏制为同步失败而非进程崩溃（ISSUE-P0-09）`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_p0_09.kdbx"
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = remotePath

        // 1. 建立基线（首传路径不触发下载）
        val baseline = coordinator.syncNow()
        assertTrue("首传应建立远端基线: $baseline", baseline is SyncOutcome.UploadedLocal)

        // 2. 远端 ETag 变化触发「远端有更新，拉取刷新」下载路径；provider 的 runCatching
        //    把 Error（真实形态：超深 XML 的 StackOverflowError / 超大响应 OOM）包成 Result.failure
        memoryProvider.remoteStorage[remotePath] = Pair(ByteArray(0), "etag_remote_changed")
        memoryProvider.downloadFailure = StackOverflowError("simulated deep-xml stack overflow")

        // 3. 整改前：Error 经引擎 getOrThrow 原样重抛并穿透 handleOpenRemote 的 catch(Exception)
        //    → 直接杀死进程且每个同步周期自动复发；整改后：遏制为本次同步失败
        val outcome = coordinator.syncNow()
        assertTrue("Error 必须被遏制为 SyncOutcome.Error 而非脱网崩溃: $outcome", outcome is SyncOutcome.Error)
    }

    @Test
    fun `测试条目冲突检测与 resolveConflicts 解决提交闭环`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_test3.kdbx"
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = remotePath

        // 1. 建立基线
        val entryId = KdbxUuid.random()
        val sharedEntry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Shared Entry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("BasePassword#1", true)
            )
        )
        databaseSession.saveEntry(sharedEntry)
        databaseSession.save()

        val baseOutcome = coordinator.syncNow()
        assertTrue(baseOutcome is SyncOutcome.UploadedLocal || baseOutcome is SyncOutcome.UpToDate)

        // 2. 本地修改密码
        val localModifiedEntry = sharedEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("LocalNewPwd#2", true))
        databaseSession.saveEntry(localModifiedEntry)
        databaseSession.save()

        // 3. 构造并发的云端修改（密码改为 RemoteNewPwd#3）
        val remoteDb = databaseSession.databaseFlow.value!!.copy()
        val remoteEntry = sharedEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("RemoteNewPwd#3", true))
        val updatedRemoteRoot = remoteDb.rootGroup.copy(
            entries = remoteDb.rootGroup.entries.map { if (it.id == entryId) remoteEntry else it }
        )
        val finalRemoteDb = remoteDb.copy(rootGroup = updatedRemoteRoot)
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, finalRemoteDb, masterPassword)
        val remoteBytes = baos.toByteArray()
        memoryProvider.remoteStorage[remotePath] = Pair(remoteBytes, "etag_concurrent_change")

        // 4. 执行同步，双方修改了相同字段，应检测出 ConflictNeedsUser
        val conflictOutcome = coordinator.syncNow()
        assertTrue("同字段并发修改必须产生冲突: $conflictOutcome", conflictOutcome is SyncOutcome.ConflictNeedsUser)
        val conflicts = (conflictOutcome as SyncOutcome.ConflictNeedsUser).conflicts
        assertEquals(1, conflicts.size)
        assertEquals(entryId.toHexString(), conflicts.first().entryId)

        // 5. 用户在冲突界面选择 KEEP_LOCAL 并提交解决
        val resolutions = mapOf(entryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
        val resolveOutcome = coordinator.resolveConflicts(resolutions)
        assertTrue("冲突解决上传成功: $resolveOutcome", resolveOutcome is SyncOutcome.MergedAndUploaded)

        // 6. 验证冲突列表已清空
        assertTrue(coordinator.conflictFlow.value.isEmpty())
    }

    @Test
    fun `逐字段采纳云端删除后上传并重解析仍缺失字段`() = runTest(testDispatcher) {
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/field-delete.kdbx"
        val preferences = ExtendedSettingsStore(null).apply {
            publish(ExtendedSettings(conflictResolution = ConflictResolution.PROMPT_USER))
        }
        val promptCoordinator = SyncCoordinator(
            fakeContext,
            databaseSession,
            credentialsStore,
            com.keepasskey.app.data.logger.DebugLogBuffer(),
            TEST_STRINGS,
            extendedSettingsStore = preferences
        ).apply {
            testSyncProvider = memoryProvider
            testRemotePath = remotePath
        }

        val entryId = KdbxUuid.random()
        val baseEntry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Keep this title", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("BasePassword#1", true)
            )
        )
        databaseSession.saveEntry(baseEntry)
        assertTrue(databaseSession.save().isSuccess)
        assertTrue(promptCoordinator.syncNow() is SyncOutcome.UploadedLocal)
        val baselineDatabase = requireNotNull(databaseSession.databaseFlow.value)

        databaseSession.saveEntry(
            baseEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("LocalPassword#2", true))
        )
        assertTrue(databaseSession.save().isSuccess)

        val remoteEntry = baseEntry.copy(fields = baseEntry.fields - KdbxConstants.Fields.PASSWORD)
        val remoteDatabase = baselineDatabase.copy(
            rootGroup = baselineDatabase.rootGroup.copy(
                entries = baselineDatabase.rootGroup.entries.map { if (it.id == entryId) remoteEntry else it }
            )
        )
        val remoteBytes = ByteArrayOutputStream().use { output ->
            KdbxFile.save(output, remoteDatabase, masterPassword)
            output.toByteArray()
        }
        memoryProvider.remoteStorage[remotePath] = remoteBytes to "etag-password-deleted"

        val conflictOutcome = promptCoordinator.syncNow()
        assertTrue("双方密码分歧应进入冲突裁决: $conflictOutcome", conflictOutcome is SyncOutcome.ConflictNeedsUser)
        val conflict = (conflictOutcome as SyncOutcome.ConflictNeedsUser).conflicts.single()
        assertTrue(KdbxConstants.Fields.PASSWORD in conflict.modifiedFields)

        val resolved = promptCoordinator.resolveConflicts(
            resolutions = mapOf(entryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL),
            fieldResolutions = mapOf(
                entryId.toHexString() to mapOf(
                    KdbxConstants.Fields.PASSWORD to FieldResolutionChoice.DELETE_REMOTE
                )
            )
        )
        assertTrue("字段删除决策应条件上传成功: $resolved", resolved is SyncOutcome.MergedAndUploaded)

        val uploadedBytes = requireNotNull(memoryProvider.remoteStorage[remotePath]).first
        val uploadedFile = File(tempFolder.root, "field-delete-uploaded.kdbx").apply {
            writeBytes(uploadedBytes)
        }
        val verifier = DatabaseSession()
        try {
            assertTrue("上传的 KDBX 应可重新打开", verifier.open(uploadedFile, masterPassword).isSuccess)
            val uploadedEntry = requireNotNull(verifier.databaseFlow.value)
                .rootGroup.allEntries().single { it.id == entryId }
            assertFalse("远端删除的密码字段不得在本地或上传产物中复活", uploadedEntry.fields.containsKey(KdbxConstants.Fields.PASSWORD))
            assertEquals("未选字段仍保持本地值", "Keep this title", uploadedEntry.fields[KdbxConstants.Fields.TITLE]?.readString())
        } finally {
            verifier.close()
        }
    }

    @Test
    fun `测试缓存被系统回收且本地有未同步修改时不被远端覆盖`() = runTest(testDispatcher) {
        // F1 + F2 修复验收：cacheDir 会被系统在存储不足时自动回收（Android 官方语义）。
        // 缓存丢失后本地存在未同步修改（已落盘但尚未同步，isDirty 已复位的最常见形态）时，
        // 严禁以远端整体覆盖会话；basecache 一并丢失时必须以空 base 退化并集合并，
        // 本地修改必须存活并交由用户决策，而非静默丢失
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_test4.kdbx"
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = remotePath

        // 1. 建立基线并上传云端
        val entryId = KdbxUuid.random()
        val sharedEntry = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Shared Entry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("BasePassword#1", true)
            )
        )
        databaseSession.saveEntry(sharedEntry)
        databaseSession.save()
        coordinator.syncNow()

        // 2. 本地修改密码并落盘（已保存但尚未同步）
        val localModified = sharedEntry.withField(
            KdbxConstants.Fields.PASSWORD, ProtectedString("LocalOnly#9", true)
        )
        databaseSession.saveEntry(localModified)
        databaseSession.save()

        // 3. 模拟 Android 系统回收 cacheDir（缓存与 basecache 一并丢失）
        File(tempFolder.root, "cache/sync").deleteRecursively()

        // 4. 远端被其他设备修改为不同密码
        val remoteDb = databaseSession.databaseFlow.value!!.copy()
        val remoteEntry = sharedEntry.withField(
            KdbxConstants.Fields.PASSWORD, ProtectedString("RemoteOnly#7", true)
        )
        val updatedRemoteRoot = remoteDb.rootGroup.copy(
            entries = remoteDb.rootGroup.entries.map { if (it.id == entryId) remoteEntry else it }
        )
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, remoteDb.copy(rootGroup = updatedRemoteRoot), masterPassword)
        memoryProvider.remoteStorage[remotePath] = Pair(baos.toByteArray(), "etag_remote_change")

        // 5. 同步：修复前本地修改被远端整体覆盖（UpToDate 且数据丢失）；
        //    修复后转三方合并——basecache 一并丢失，F2 修复退化并集合并 → 字段冲突交用户决策
        val outcome = coordinator.syncNow()
        assertTrue(
            "缓存回收 + 本地未同步修改必须转冲突合并而非覆盖: $outcome",
            outcome is SyncOutcome.ConflictNeedsUser
        )
        val conflicts = (outcome as SyncOutcome.ConflictNeedsUser).conflicts
        assertEquals(1, conflicts.size)
        assertEquals(entryId.toHexString(), conflicts.first().entryId)

        // 6. 用户选择保留本地 → 合并版本上传 → 本地修改完整保留
        val resolveOutcome = coordinator.resolveConflicts(
            mapOf(entryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
        )
        assertTrue("冲突解决应成功上传: $resolveOutcome", resolveOutcome is SyncOutcome.MergedAndUploaded)
        val savedEntry = databaseSession.databaseFlow.value!!.rootGroup.allEntries()
            .first { it.id == entryId }
        assertEquals("LocalOnly#9", savedEntry.fields[KdbxConstants.Fields.PASSWORD]?.readString())
    }

    /** 建立基线后：本地新增条目 A（落盘），远端新增条目 B（无字段分叉 ⇒ 自动合并路径） */
    private suspend fun prepareAutoMergeScenario(
        memoryProvider: MemorySyncProvider,
        remotePath: String
    ) {
        coordinator.testSyncProvider = memoryProvider
        coordinator.testRemotePath = remotePath
        val baseEntry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Base Entry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("BasePassword#1", true)
            )
        )
        databaseSession.saveEntry(baseEntry)
        databaseSession.save()
        coordinator.syncNow()

        // 本地新增条目 A（与远端改动无字段交集）
        val localOnlyEntry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("LocalOnlyEntry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("LocalOnlyPwd#1", true)
            )
        )
        databaseSession.saveEntry(localOnlyEntry)
        databaseSession.save()

        // 远端新增条目 B，ETag 前移（冲突时刻 ETag = etag_remote_added_B）
        val remoteDb = databaseSession.databaseFlow.value!!.copy()
        val remoteOnlyEntry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("RemoteOnlyEntry", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("RemoteOnlyPwd#1", true)
            )
        )
        val updatedRemoteRoot = remoteDb.rootGroup.copy(entries = remoteDb.rootGroup.entries + remoteOnlyEntry)
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, remoteDb.copy(rootGroup = updatedRemoteRoot), masterPassword)
        memoryProvider.remoteStorage[remotePath] = Pair(baos.toByteArray(), "etag_remote_added_B")
    }

    @Test
    fun `测试自动合并上传必须携带冲突时刻ETag`() = runTest(testDispatcher) {
        // ISSUE-P1-275 AC①/AC④：自动合并路径（旧实现整个丢弃冲突时刻 ETag、退化为重探当前值）
        // 的合并上传必须把冲突时刻 ETag 透传到 Provider 预条件
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_p275_auto.kdbx"
        prepareAutoMergeScenario(memoryProvider, remotePath)

        val outcome = coordinator.syncNow()
        assertTrue("无字段分叉应自动合并并上传: $outcome", outcome is SyncOutcome.MergedAndUploaded)
        assertEquals(
            "合并上传必须携带冲突时刻 ETag 作 If 预条件",
            "etag_remote_added_B",
            memoryProvider.lastExpectedEtag
        )
    }

    @Test
    fun `测试合并上传412后重新进入冲突流程完成二次合并上传`() = runTest(testDispatcher) {
        // ISSUE-P1-275 AC③：合并上传遭 412（合并窗口内远端再次变更）必须重新进入冲突流程
        // （重新合并 + 重新上传），而非归一为一次性错误
        val memoryProvider = MemorySyncProvider()
        val remotePath = "/remote/vault_p275_412reentry.kdbx"
        prepareAutoMergeScenario(memoryProvider, remotePath)

        // 接下来两次 upload 一律 412：① 合并上传本身 412 ⇒ 触发重入；
        // ② 重入前取最新远端的 commitLocal 上传也 412（基线已过期）⇒ 携最新远端进入二次合并
        memoryProvider.forceConflictUploads = 2

        val outcome = coordinator.syncNow()
        assertTrue("412 重入后二次合并上传必须成功: $outcome", outcome is SyncOutcome.MergedAndUploaded)
        // 二次合并的最终上传携带重入时探测到的最新 ETag
        assertEquals("etag_remote_added_B", memoryProvider.lastExpectedEtag)

        // 双侧新增均存活
        val entries = databaseSession.databaseFlow.value!!.rootGroup.allEntries()
        assertEquals("本地与远端新增条目合并后均存活", 3, entries.size)
        assertTrue(coordinator.conflictFlow.value.isEmpty())
    }
}
