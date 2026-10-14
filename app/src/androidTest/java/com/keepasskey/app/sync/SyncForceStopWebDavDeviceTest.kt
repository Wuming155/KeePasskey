package com.keepasskey.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.RemoteListPage
import com.keepasskey.sync.provider.SyncProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P1-613：由宿主分阶段 force-stop 后，以生产 WebDAV Provider 恢复并提交本地待同步修改。 */
@RunWith(AndroidJUnit4::class)
class SyncForceStopWebDavDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun forceStop后经生产WebDavProvider重新同步本地版本() = runBlocking {
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)) { "缺少 p1_613_run_id，须经 tools/device/run_p1_613_lifecycle.py 编排" }
        val phase = arguments.getString(ARG_PHASE)
        val vaultFile = File(context.filesDir, "p1-613-sync-$runId.kdbx")
        val cacheDir = File(context.cacheDir, "p1-613-sync-$runId")
        val rollbackDir = File(context.filesDir, "p1-613-rollback-$runId")
        val password = SYNC_PASSWORD.toCharArray()
        val log = DebugLogBuffer()
        val preferences = SyncPreferences(log, null)
        val credentialStore = SyncCredentialsStore(context, KeystoreManager(context, log), log)
        val remotePath = "p1-613-$runId.kdbx"
        val session = DatabaseSession()

        try {
            when (phase) {
                PHASE_PREPARE -> {
                    assertTrue("合成库创建失败", session.create(vaultFile, "P1-613 sync", password, useArgon2 = false) is KdbxResult.Success)
                    session.saveEntry(entry("A"))
                    assertTrue("A 初始落盘失败", session.save() is KdbxResult.Success)
                    configureWebDav(credentialStore, remotePath)
                    val uploadGate = WebDavUploadGate()
                    val uploadProvider = GatedWebDavProvider(
                        requireNotNull(SyncProviderResolver(credentialStore, preferences, log).resolveProvider()),
                        uploadGate
                    )
                    val first = coordinator(session, preferences, credentialStore, log, cacheDir, rollbackDir, uploadProvider)
                    val upload = async(Dispatchers.IO) { first.coordinator.syncNow() }
                    withTimeout(60_000) { uploadGate.reached.await() }
                    session.saveEntry(entry("B"))
                    assertTrue("上传后 B 未写入正式库", session.save() is KdbxResult.Success)
                    uploadGate.release.complete(Unit)
                    assertEquals("A 上传完成时窗口内的 B 必须仍待同步", SyncOutcome.LocalPendingChanges, withTimeout(60_000) { upload.await() })
                    val independentClient = requireNotNull(SyncProviderResolver(credentialStore, preferences, log).resolveProvider())
                    val remoteA = ByteArrayOutputStream()
                    assertTrue("独立 WebDAV 客户端读取 A 失败", independentClient.download(remotePath, remoteA).isSuccess)
                    val databaseA = load(remoteA.toByteArray(), password)
                    val databaseC = databaseA.copy(
                        rootGroup = databaseA.rootGroup.copy(entries = databaseA.rootGroup.entries + entry("C"))
                    )
                    val remoteC = save(databaseC, password)
                    databaseA.clearSensitiveData()
                    databaseC.clearSensitiveData()
                    val remoteMetadata = independentClient.getMetadata(remotePath).getOrThrow()
                    assertTrue(
                        "独立 WebDAV 客户端写入远端 C 失败",
                        independentClient.uploadAtomic(remotePath, remoteC, remoteMetadata.etag, remoteExists = true).isSuccess
                    )
                    remoteC.fill(0)
                    context.getSharedPreferences(PREFS, 0).edit()
                        .putInt(KEY_PREPARE_PID, android.os.Process.myPid())
                        .putString(KEY_RUN_ID, runId)
                        .commit()
                    session.close()
                }

                PHASE_RESUME -> {
                    val state = context.getSharedPreferences(PREFS, 0)
                    assertEquals("阶段标记必须对应同一运行", runId, state.getString(KEY_RUN_ID, null))
                    assertNotEquals("必须先 force-stop 并由新 OS 进程接手", state.getInt(KEY_PREPARE_PID, -1), android.os.Process.myPid())
                    assertTrue("新进程从正式库解锁失败", session.open(vaultFile, password.clone()) is KdbxResult.Success)
                    assertTrue("冷进程打开后应从本地正式库读到 B", titles(session.databaseFlow.value!!).containsAll(listOf("A", "B")))
                    val next = coordinator(session, preferences, credentialStore, log, cacheDir, rollbackDir)
                    val outcome = withTimeout(60_000) { next.coordinator.syncNow() }
                    assertTrue("force-stop 后 WebDAV 三方恢复同步失败：$outcome", outcome is SyncOutcome.MergedAndUploaded)

                    val provider = requireNotNull(SyncProviderResolver(credentialStore, preferences, log).resolveProvider())
                    val remoteBytes = ByteArrayOutputStream()
                    assertTrue("生产 WebDAV Provider 下载远端文件失败", provider.download(remotePath, remoteBytes).isSuccess)
                    val remoteData = remoteBytes.toByteArray()
                    val baselineData = requireNotNull(SyncCache(cacheDir).readBaseContent(remotePath)) { "重启同步后缺少持久化提交基线" }
                    assertArrayEquals("持久化基线字节必须等于 WebDAV 实际提交产物", remoteData, baselineData)
                    val remoteTitles = titles(remoteData, password)
                    val baselineTitles = titles(baselineData, password)
                    assertTrue("重启同步后的远端必须同时保留 A/B/C：$remoteTitles", remoteTitles.containsAll(listOf("A", "B", "C")))
                    assertTrue("重启同步后的本地库必须同时保留 A/B/C", titles(session.databaseFlow.value!!).containsAll(listOf("A", "B", "C")))
                    assertTrue("基线解密后必须同时保留 A/B/C：$baselineTitles", baselineTitles.containsAll(listOf("A", "B", "C")))
                    cleanup(vaultFile, cacheDir, rollbackDir, credentialStore)
                }

                else -> error("p1_613_phase 只接受 prepare 或 resume")
            }
        } finally {
            password.fill('\u0000')
            session.close()
        }
    }

    private fun configureWebDav(store: SyncCredentialsStore, remotePath: String) {
        assertTrue(
            "真实 Keystore WebDAV 凭据封印失败",
            store.saveWebDavConfig(
                url = "https://127.0.0.1:$WEBDAV_PORT",
                username = WEBDAV_USER,
                password = WEBDAV_PASSWORD.toCharArray(),
                remotePath = remotePath
            )
        )
    }

    private fun coordinator(
        session: DatabaseSession,
        preferences: SyncPreferences,
        credentials: SyncCredentialsStore,
        log: DebugLogBuffer,
        cacheDir: File,
        rollbackDir: File,
        testProvider: SyncProvider? = null
    ): SyncHarness {
        val resolver = SyncProviderResolver(credentials, preferences, log)
        val sessionState = SyncSessionState().apply { testRemotePath = requireNotNull(credentials.loadWebDavConfig()).let {
            try { it.remotePath } finally { it.password.fill('0') }
        } }
        val strings = StringsProvider { id, args -> context.getString(id, *args) }
        val codec = SyncDatabaseCodec(session, log)
        val conflicts = SyncConflictController(session, codec, strings, sessionState, log)
        val changes = SyncContentChangeDetector(codec, sessionState)
        val cycle = SyncCycleRunner(
            context = context,
            databaseSession = session,
            session = sessionState,
            providerResolver = resolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = strings,
            rollbackStateDir = rollbackDir
        ).apply { syncCacheFactory = { SyncCache(cacheDir) } }
        val syncCoordinator = SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = credentials,
            debugLog = log,
            strings = strings,
            syncSession = sessionState,
            syncPreferences = preferences,
            syncProviderResolver = resolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        )
        syncCoordinator.testSyncProvider = testProvider
        return SyncHarness(syncCoordinator, sessionState)
    }

    private fun cleanup(vault: File, cache: File, rollback: File, store: SyncCredentialsStore) {
        runCatching { store.clear() }
        context.getSharedPreferences(PREFS, 0).edit().remove(KEY_PREPARE_PID).remove(KEY_RUN_ID).commit()
        vault.delete()
        File(vault.absolutePath + ".bak").delete()
        cache.deleteRecursively()
        rollback.deleteRecursively()
    }

    private fun entry(title: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title))
    )

    private fun titles(db: KdbxDatabase): List<String> = db.rootGroup.allEntries().map { it.title }

    private fun titles(bytes: ByteArray, password: CharArray): List<String> {
        val db = load(bytes, password)
        return try { titles(db) } finally { db.clearSensitiveData() }
    }

    private fun load(bytes: ByteArray, password: CharArray): KdbxDatabase {
        val copy = password.clone()
        return try { KdbxFile.load(ByteArrayInputStream(bytes), copy) } finally { copy.fill('\u0000'); bytes.fill(0) }
    }

    private fun save(database: KdbxDatabase, password: CharArray): ByteArray {
        val output = ByteArrayOutputStream()
        val copy = password.clone()
        return try { KdbxFile.save(output, database, copy, null); output.toByteArray() } finally { copy.fill('\u0000') }
    }

    private data class SyncHarness(val coordinator: SyncCoordinator, val sessionState: SyncSessionState)

    private class WebDavUploadGate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** 仅延迟真实生产 WebDAV PUT+MOVE 的回执，协议请求仍全部委托给 WebDavSyncProvider。 */
    private class GatedWebDavProvider(
        private val delegate: SyncProvider,
        private val gate: WebDavUploadGate
    ) : SyncProvider {
        private val firstUpload = AtomicBoolean(true)

        override suspend fun testConnection(): Result<Unit> = delegate.testConnection()
        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> = delegate.getMetadata(remotePath)
        override suspend fun listRemoteDirectory(remotePath: String, cursor: String?, pageSize: Int): Result<RemoteListPage> =
            delegate.listRemoteDirectory(remotePath, cursor, pageSize)
        override suspend fun download(remotePath: String, sink: java.io.OutputStream): Result<Unit> = delegate.download(remotePath, sink)
        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> =
            delegate.upload(remotePath, data, expectedEtag)

        override suspend fun uploadAtomic(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?,
            remoteExists: Boolean?
        ): Result<String> {
            val result = delegate.uploadAtomic(remotePath, data, expectedEtag, remoteExists)
            if (result.isSuccess && firstUpload.compareAndSet(true, false)) {
                gate.reached.complete(Unit)
                gate.release.await()
            }
            return result
        }

        override suspend fun delete(remotePath: String): Result<Unit> = delegate.delete(remotePath)
    }

    private companion object {
        const val ARG_PHASE = "p1_613_phase"
        const val ARG_RUN_ID = "p1_613_run_id"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_RESUME = "resume"
        const val PREFS = "p1_613_sync_force_stop"
        const val KEY_PREPARE_PID = "prepare_pid"
        const val KEY_RUN_ID = "run_id"
        const val SYNC_PASSWORD = "Synthetic-P1-613-Vault-Password"
        const val WEBDAV_USER = "p1-613-test"
        const val WEBDAV_PASSWORD = "Synthetic-WebDav-Password"
        const val WEBDAV_PORT = 9443
    }
}
