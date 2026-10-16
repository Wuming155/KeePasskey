package com.keepasskey.app.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
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
import com.keepasskey.sync.engine.SyncRollbackGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** P2-621：prepare 与 resume 由宿主真正终止/重建 Android 进程分隔，远端使用仓库本地 HTTPS WebDAV。 */
@RunWith(AndroidJUnit4::class)
class SyncRollbackProcessRecoveryDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun prepare(): Unit = runBlocking {
        val runId = runId()
        val webdavPort = webdavPort()
        val path = vaultPath(runId)
        val remotePath = remotePath(runId)
        val stateDir = stateDir(runId)
        val crashStage = requireNotNull(arguments.getString(ARG_CRASH_STAGE)) { "missing p2_621_crash_stage" }
        stateDir.mkdirs()
        val previousActive = ActiveDatabaseIdStore(context).load()
        val log = DebugLogBuffer()
        val session = DatabaseSession()
        val credentials = productionCredentials(session, log)
        val file = File(path)
        val password = masterPassword()
        var remoteA: ByteArray? = null
        var coordinator: SyncCoordinator? = null
        try {
            assertTrue("合成库 A 创建失败", session.create(file, "P2-621 process recovery", password, false) is KdbxResult.Success)
            session.saveEntry(entry("revision A"))
            assertTrue("正式 A 保存失败", session.save() is KdbxResult.Success)
            ActiveDatabaseIdStore(context).save(path)
            assertTrue(
                "持久化生产 WebDAV 配置保存失败",
                credentials.saveWebDavConfig(
                    "https://127.0.0.1:$webdavPort",
                    WEBDAV_USER,
                    WEBDAV_PASSWORD.toCharArray(),
                    remotePath,
                    dbId = path
                )
            )
            credentials.saveProvider(com.keepasskey.app.ui.screens.settings.CloudSyncProvider.WEBDAV, path)

            val initialCoordinator = coordinator(session, credentials, log, stateDir = stateDir(runId))
            coordinator = initialCoordinator
            assertEquals("初始 A 必须通过生产 WebDAV 上传", SyncOutcome.UploadedLocal, initialCoordinator.syncNow())
            remoteA = downloadRemote(credentials, session, log, file.name, remotePath)
            File(stateDir, REMOTE_A_FILE).writeBytes(requireNotNull(remoteA))

            val activeDb = requireNotNull(session.databaseFlow.value)
            val remoteBEntry = entry("formal revision B")
            val remoteB = save(
                activeDb.copy(rootGroup = activeDb.rootGroup.copy(entries = activeDb.rootGroup.entries + remoteBEntry)),
                password
            )
            val digestB = SyncCache.sha256Hex(remoteB)
            publishRemoteBytes(credentials, session, log, file.name, remotePath, remoteB)
            remoteB.fill(0)

            // Persist phase-2 inputs before the crash point; the host kills this Android process from outside.
            statePrefs().edit()
                .putInt(key(runId, KEY_PREPARE_PID), android.os.Process.myPid())
                .putString(key(runId, KEY_VAULT_PATH), path)
                .putString(key(runId, KEY_REMOTE_PATH), remotePath)
                .putString(key(runId, KEY_PREVIOUS_ACTIVE_ID), previousActive)
                .putString(key(runId, KEY_DIGEST_B), digestB)
                .putString(key(runId, KEY_CRASH_STAGE), crashStage)
                .commit()

            val failingCoordinator = coordinator(
                session,
                credentials,
                log,
                stateDir = stateDir(runId),
                failHighWaterDigest = digestB,
                crashStage = crashStage,
                runId = runId
            )
            coordinator = failingCoordinator
            val failure = failingCoordinator.syncNow()
            assertTrue("定点高水位写失败必须返回错误", failure is SyncOutcome.Error)
            assertTrue("故障前正式 KDBX B 必须已提交", titles(file.readBytes(), password).contains("formal revision B"))
            assertTrue("无周期探测记录时 pending marker 必须存在", failingCoordinator.hasPendingUserRecovery(path))

        } finally {
            remoteA?.fill(0)
            password.fill('\u0000')
            coordinator?.let { runCatching { session.removeLockObserver(it) } }
            session.close()
        }
    }

    @Test
    fun resumeAfterProcessRestart(): Unit = runBlocking {
        val runId = runId()
        val prefs = statePrefs()
        val path = requireNotNull(prefs.getString(key(runId, KEY_VAULT_PATH), null))
        val remotePath = requireNotNull(prefs.getString(key(runId, KEY_REMOTE_PATH), null))
        val previousActive = prefs.getString(key(runId, KEY_PREVIOUS_ACTIVE_ID), null)
        val preparePid = prefs.getInt(key(runId, KEY_PREPARE_PID), -1)
        val digestB = requireNotNull(prefs.getString(key(runId, KEY_DIGEST_B), null))
        val crashStage = requireNotNull(prefs.getString(key(runId, KEY_CRASH_STAGE), null))
        assertTrue("prepare manifest missing", preparePid > 0)
        assertNotEquals("phase 2 必须由新 Android 进程执行", preparePid, android.os.Process.myPid())

        val file = File(path)
        val stateDir = stateDir(runId)
        val remoteAFile = File(stateDir, REMOTE_A_FILE)
        val remoteA = remoteAFile.readBytes()
        val password = masterPassword()
        val log = DebugLogBuffer()
        val session = DatabaseSession()
        val credentials = productionCredentials(session, log)
        val coordinator = coordinator(session, credentials, log, stateDir = File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME))
        try {
            val openResult = session.open(file, password)
            assertTrue("新进程必须能从正式库读取 B", openResult is KdbxResult.Success)
            assertTrue("新进程正式库必须保留 revision B", titles(requireNotNull(session.databaseFlow.value)).contains("formal revision B"))
            ActiveDatabaseIdStore(context).save(path)

            // 以第二个独立 WebDAV 客户端重放此前捕获的远端 A，再通过生产同步路径检查 quarantine。
            publishRemoteBytes(credentials, session, log, file.name, remotePath, remoteA)
            val replay = coordinator.syncNow()
            assertTrue("OS 进程重启后远端 A 必须被 pending marker/quarantine 拒绝: $replay", replay is SyncOutcome.Error)
            assertTrue("拒绝 A 后正式 KDBX 仍须保留 B", titles(file.readBytes(), password).contains("formal revision B"))
            assertEquals(
                "重建进程须读到持久 pending acceptance",
                com.keepasskey.sync.engine.RollbackVerdict.RecoveryRequired,
                SyncRollbackGuard(
                    File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
                    ownerVaultIdentity = path
                ).inspect(remotePath, digestB)
            )

            val resolved = coordinator.recoverPendingAcceptanceByPublishingLocal()
            assertEquals("显式选本地后必须以 ETag 条件上传并解除隔离", SyncOutcome.UploadedLocal, resolved)
            assertTrue("恢复完成后 marker 必须清除", !coordinator.hasPendingUserRecovery(path))
            val recoveredRemote = downloadRemote(credentials, session, log, file.name, remotePath)
            try {
                assertTrue("远端须保留正式 B", titles(recoveredRemote, password).contains("formal revision B"))
            } finally {
                recoveredRemote.fill(0)
            }
        } finally {
            remoteA.fill(0)
            password.fill('\u0000')
            runCatching { session.removeLockObserver(coordinator) }
            session.close()
            credentials.clearFor(path)
            SyncCache(File(context.cacheDir, SyncCache.CACHE_DIR_NAME)).clear(remotePath)
            val rollbackDir = File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME)
            File(
                rollbackDir,
                SyncCache.sha256Hex(path.toByteArray(Charsets.UTF_8)) + SyncRollbackGuard.SUFFIX_VAULT_PENDING
            ).delete()
            File(
                rollbackDir,
                SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8)) + SyncRollbackGuard.SUFFIX_STATE
            ).delete()
            ActiveDatabaseIdStore(context).save(previousActive)
            File(file.absolutePath + ".bak").delete()
            file.delete()
            stateDir.deleteRecursively()
            prefs.edit().remove(key(runId, KEY_PREPARE_PID)).remove(key(runId, KEY_VAULT_PATH))
                .remove(key(runId, KEY_REMOTE_PATH)).remove(key(runId, KEY_PREVIOUS_ACTIVE_ID))
                .remove(key(runId, KEY_DIGEST_B)).remove(key(runId, KEY_CRASH_STAGE)).commit()
        }
    }

    private fun coordinator(
        session: DatabaseSession,
        credentials: SyncCredentialsStore,
        log: DebugLogBuffer,
        stateDir: File,
        failHighWaterDigest: String? = null,
        crashStage: String? = null,
        runId: String? = null
    ): SyncCoordinator {
        val preferences = SyncPreferences(log)
        val resolver = SyncProviderResolver(credentials, preferences, log)
        val state = SyncSessionState()
        val strings = StringsProvider { id, args -> context.getString(id, *args) }
        val codec = SyncDatabaseCodec(session, log)
        val conflicts = SyncConflictController(session, codec, strings, state, log)
        val changes = SyncContentChangeDetector(codec, state)
        val cycle = SyncCycleRunner(
            context,
            session,
            state,
            resolver,
            codec,
            conflicts,
            changes,
            preferences,
            strings,
            rollbackStateDir = File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME)
        ).apply {
            syncCacheFactory = { SyncCache(it) }
            if (crashStage != null) {
                fun crashAt(stage: String) {
                    val expectedStage = if (stage == "HIGH_WATER_WRITE_STARTED") {
                        "HIGH_WATER_WRITE_STARTED"
                    } else {
                        stage
                    }
                    if (expectedStage == crashStage) {
                        val marker = File(
                            context.filesDir,
                            ".p2-621-process-${requireNotNull(runId)}/$CRASH_MARKER"
                        )
                        FileOutputStream(marker).use { output ->
                            output.write("stage=$stage\npid=${android.os.Process.myPid()}\n".toByteArray())
                            output.flush()
                            output.fd.sync()
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                        while (true) android.os.SystemClock.sleep(1_000)
                    }
                }
                settlementStageObserverForTest = { stage -> crashAt(stage.name) }
                rollbackGuardFactoryForTest = { dir, scope, identity ->
                    SyncRollbackGuard(dir, scope, { next ->
                        if (crashStage == "HIGH_WATER_WRITE_STARTED" &&
                            next.current == failHighWaterDigest && next.pendingDigest == null
                        ) {
                            crashAt("HIGH_WATER_WRITE_STARTED")
                        }
                    }, identity)
                }
            }
        }
        return SyncCoordinator(
            context,
            session,
            credentials,
            log,
            strings,
            syncSession = state,
            syncPreferences = preferences,
            syncProviderResolver = resolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        )
    }

    private suspend fun publishRemoteBytes(
        credentials: SyncCredentialsStore,
        session: DatabaseSession,
        log: DebugLogBuffer,
        defaultFileName: String,
        remotePath: String,
        bytes: ByteArray
    ) {
        val preferences = SyncPreferences(log)
        val resolver = SyncProviderResolver(credentials, preferences, log)
        val result = resolver.useProviderWithTarget(defaultFileName) { provider, _ ->
            val metadata = provider.getMetadata(remotePath).getOrThrow()
            val uploaded = provider.uploadAtomic(remotePath, bytes, metadata.etag, true)
            uploaded.getOrThrow()
        }
        assertTrue("production provider must publish synthetic revision", result != null)
    }

    private suspend fun downloadRemote(
        credentials: SyncCredentialsStore,
        session: DatabaseSession,
        log: DebugLogBuffer,
        defaultFileName: String,
        remotePath: String
    ): ByteArray {
        val preferences = SyncPreferences(log)
        val resolver = SyncProviderResolver(credentials, preferences, log)
        val output = ByteArrayOutputStream()
        resolver.useProviderWithTarget(defaultFileName) { provider, _ ->
            provider.download(remotePath, output).getOrThrow()
        }
        return output.toByteArray()
    }

    private fun productionCredentials(session: DatabaseSession, log: DebugLogBuffer) = SyncCredentialsStore(
        context,
        KeystoreManager(context, log),
        log,
        SyncCacheEvictor(context, log),
        session
    )

    private fun save(database: KdbxDatabase, password: CharArray): ByteArray {
        val output = ByteArrayOutputStream()
        val borrowed = password.clone()
        return try {
            KdbxFile.save(output, database, borrowed, null)
            output.toByteArray()
        } finally {
            borrowed.fill('\u0000')
        }
    }

    private fun titles(fileBytes: ByteArray, password: CharArray): List<String> {
        val borrowed = password.clone()
        val database = try {
            KdbxFile.load(ByteArrayInputStream(fileBytes), borrowed)
        } finally {
            borrowed.fill('\u0000')
        }
        return try {
            titles(database)
        } finally {
            database.clearSensitiveData()
        }
    }

    private fun titles(database: KdbxDatabase): List<String> = database.rootGroup.allEntries().map { it.title }

    private fun entry(title: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false))
    )

    private fun productionCredentialsConfig(
        credentials: SyncCredentialsStore,
        path: String,
        remotePath: String,
        port: Int
    ) {
        assertTrue(
            "生产 WebDAV 配置必须安全封印",
            credentials.saveWebDavConfig(
                "https://127.0.0.1:$port",
                WEBDAV_USER,
                WEBDAV_PASSWORD.toCharArray(),
                remotePath,
                dbId = path
            )
        )
        credentials.saveProvider(com.keepasskey.app.ui.screens.settings.CloudSyncProvider.WEBDAV, path)
    }

    private fun stateDir(runId: String) = File(context.filesDir, ".p2-621-process-$runId")
    private fun vaultPath(runId: String) = File(context.filesDir, "p2-621-process-$runId.kdbx").absolutePath
    private fun remotePath(runId: String) = "/p2-621-process-$runId.kdbx"
    private fun runId() = requireNotNull(arguments.getString(ARG_RUN_ID)) { "missing p2_621_run_id" }
    private fun webdavPort() = requireNotNull(arguments.getString(ARG_WEBDAV_PORT)?.toIntOrNull()) {
        "missing p2_621_webdav_port"
    }
    private fun masterPassword() = "Synthetic-P2-621-Process-Password".toCharArray()
    private fun statePrefs() = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private fun key(runId: String, suffix: String) = "$runId.$suffix"

    private companion object {
        const val ARG_RUN_ID = "p2_621_run_id"
        const val ARG_WEBDAV_PORT = "p2_621_webdav_port"
        const val ARG_CRASH_STAGE = "p2_621_crash_stage"
        const val STATE_PREFS = "p2_621_process_recovery_state"
        const val REMOTE_A_FILE = "remote-a.kdbx"
        const val CRASH_MARKER = "crash-marker.txt"
        const val KEY_PREPARE_PID = "prepare_pid"
        const val KEY_VAULT_PATH = "vault_path"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_PREVIOUS_ACTIVE_ID = "previous_active_id"
        const val KEY_DIGEST_B = "digest_b"
        const val KEY_CRASH_STAGE = "crash_stage"
        const val WEBDAV_USER = "p2-621-process-test"
        const val WEBDAV_PASSWORD = "Synthetic-P2-621-WebDav-Password"
    }
}
