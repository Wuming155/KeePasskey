package com.keepasskey.app.sync

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.R
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
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P2-627：prepare/resume 由宿主普通进程死亡分隔，resume 通过 WorkManager 执行锁态密文队列。 */
@RunWith(AndroidJUnit4::class)
class PeriodicSyncLockedQueueColdStartDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun 锁态周期Worker冷启动上传密文并在解锁后收敛() = runBlocking {
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)) { "缺少 p2_627_run_id" }
        when (requireNotNull(arguments.getString(ARG_PHASE)) { "缺少 p2_627_phase" }) {
            PHASE_PREPARE -> prepare(runId)
            PHASE_RESUME -> resume(runId)
            else -> error("p2_627_phase 只接受 prepare 或 resume")
        }
    }

    private suspend fun prepare(runId: String) {
        val webdavPort = requireNotNull(arguments.getString(ARG_WEBDAV_PORT)?.toIntOrNull()) {
            "缺少 p2_627_webdav_port"
        }
        val log = DebugLogBuffer()
        val databaseSession = DatabaseSession()
        val credentials = productionCredentials(databaseSession, log)
        val probeStore = PeriodicSyncProbeStore(context)
        val previousActiveId = ActiveDatabaseIdStore(context).load()
        val vaultFile = vaultFile(runId)
        val remotePath = remotePath(runId)
        val password = MASTER_PASSWORD.toCharArray()
        val state = stateStore()
        state.edit()
            .putString(KEY_RUN_ID, runId)
            .putString(KEY_VAULT_PATH, vaultFile.absolutePath)
            .putString(KEY_REMOTE_PATH, remotePath)
            .putString(KEY_PREVIOUS_ACTIVE_ID, previousActiveId)
            .commit()

        try {
            assertTrue(
                "合成库必须创建成功",
                databaseSession.create(vaultFile, VAULT_NAME, password, useArgon2 = false) is KdbxResult.Success
            )
            databaseSession.saveEntry(entry("Queue revision A"))
            assertTrue("revision A 必须先落盘", databaseSession.save() is KdbxResult.Success)
            ActiveDatabaseIdStore(context).save(vaultFile.absolutePath)
            assertTrue(
                "真实 Keystore WebDAV 凭据封印失败",
                credentials.saveWebDavConfig(
                    url = "https://127.0.0.1:$webdavPort",
                    username = WEBDAV_USER,
                    password = WEBDAV_PASSWORD.toCharArray(),
                    remotePath = remotePath,
                    dbId = vaultFile.absolutePath
                )
            )

            val coordinator = SyncCoordinator(
                context,
                databaseSession,
                credentials,
                log,
                periodicSyncProbeStore = probeStore
            )
            assertEquals("设备验收不得注入测试 Provider", null, coordinator.testSyncProvider)
            assertEquals("初始 revision A 必须走生产 WebDAV 上传", SyncOutcome.UploadedLocal, coordinator.syncNow())
            val baseline = requireNotNull(
                (probeStore.read(vaultFile.absolutePath) as? PeriodicSyncProbeRead.Stored)?.record
            )
            assertTrue("完整同步必须持久化 ETag", baseline.observedEtag.isNotBlank())
            assertNotNull("完整同步必须持久化本地密文摘要", baseline.syncedContentDigest)
            assertNotNull("完整同步必须持久化回滚库作用域", baseline.rollbackVaultScope)

            databaseSession.saveEntry(entry("Queue revision B"))
            assertTrue("revision B 必须在锁库前落盘", databaseSession.save() is KdbxResult.Success)
            val digestB = SyncCache.sha256Hex(vaultFile.readBytes())
            databaseSession.lock()
            assertEquals(DatabaseSession.SessionState.LOCKED, databaseSession.state.value)
            val stagePendingQueue =
                arguments.getString(ARG_TAMPER_QUEUE_BEFORE_WORKER) == "true" ||
                    arguments.getString(ARG_REMOTE_CHANGED_BEFORE_WORKER) == "true" ||
                    arguments.getString(ARG_NETWORK_UNAVAILABLE_BEFORE_WORKER) == "true"
            if (stagePendingQueue) {
                val queueDirectory = File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                val queueStore = PeriodicSyncPendingUploadStore(queueDirectory)
                val queued = requireNotNull(
                    queueStore.stage(
                        vaultIdentity = vaultFile.absolutePath,
                        targetIdentity = baseline.targetIdentity,
                        remotePath = remotePath,
                        expectedEtag = baseline.observedEtag,
                        rollbackVaultScope = requireNotNull(baseline.rollbackVaultScope),
                        input = FileInputStream(vaultFile)
                    )
                )
                assertEquals("测试队列必须先保存本地 revision B", digestB, queued.contentDigest)
                if (arguments.getString(ARG_TAMPER_QUEUE_BEFORE_WORKER) == "true") {
                    val tamperedBytes = ByteArray(queued.sizeBytes.toInt()) { 0xA5.toByte() }
                    try {
                        File(queueDirectory, queued.payloadFileName).writeBytes(tamperedBytes)
                    } finally {
                        tamperedBytes.fill(0)
                    }
                }
            }
            state.edit()
                .putString(KEY_REVISION_B_DIGEST, digestB)
                .putString(KEY_ROLLBACK_SCOPE, baseline.rollbackVaultScope)
                .putInt(KEY_PREPARE_PID, android.os.Process.myPid())
                .putBoolean(KEY_PREPARED, true)
                .commit()
            databaseSession.close()
            val request = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
                .setInitialDelay(
                    arguments.getLong(ARG_WORK_START_DELAY_SECONDS, WORK_START_DELAY_SECONDS),
                    TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                .result.get()
            state.edit().putString(KEY_WORK_ID, request.id.toString()).commit()
        } catch (failure: Throwable) {
            cleanup(runId, vaultFile, remotePath, previousActiveId, databaseSession, credentials, probeStore)
            throw failure
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun resume(runId: String) {
        val state = stateStore()
        val remoteChangedBeforeWorker = arguments.getString(ARG_REMOTE_CHANGED_BEFORE_WORKER) == "true"
        val tamperQueueBeforeWorker = arguments.getString(ARG_TAMPER_QUEUE_BEFORE_WORKER) == "true"
        val networkUnavailableBeforeWorker =
            arguments.getString(ARG_NETWORK_UNAVAILABLE_BEFORE_WORKER) == "true"
        assertTrue(
            "远端改变、队列损坏、网络失败场景必须分开运行",
            listOf(remoteChangedBeforeWorker, tamperQueueBeforeWorker, networkUnavailableBeforeWorker).count { it } <= 1
        )
        assertTrue("prepare 阶段必须完成", state.getBoolean(KEY_PREPARED, false))
        assertEquals("两阶段必须属于同一运行", runId, state.getString(KEY_RUN_ID, null))
        assertNotEquals(
            "宿主结束旧进程后 resume 必须运行于新应用进程",
            state.getInt(KEY_PREPARE_PID, -1),
            android.os.Process.myPid()
        )
        val vaultFile = File(requireNotNull(state.getString(KEY_VAULT_PATH, null)))
        val remotePath = requireNotNull(state.getString(KEY_REMOTE_PATH, null))
        val previousActiveId = state.getString(KEY_PREVIOUS_ACTIVE_ID, null)
        val expectedDigest = requireNotNull(state.getString(KEY_REVISION_B_DIGEST, null))
        val log = DebugLogBuffer()
        val databaseSession = DatabaseSession()
        val credentials = productionCredentials(databaseSession, log)
        val probeStore = PeriodicSyncProbeStore(context)
        val workCoordinator = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PeriodicSyncWorker.PeriodicSyncEntryPoint::class.java
        ).syncCoordinator()
        assertEquals("冷启动路径不得设置测试 Provider", null, workCoordinator.testSyncProvider)

        try {
            val workManager = WorkManager.getInstance(context)
            val persistedWorkId = java.util.UUID.fromString(requireNotNull(state.getString(KEY_WORK_ID, null)))
            assertNotNull("prepare 阶段排队的 WorkManager 任务必须跨进程死亡持久化", workManager.getWorkInfoById(persistedWorkId).get())
            var completed: WorkInfo
            if (networkUnavailableBeforeWorker) {
                workManager.cancelUniqueWork(WORK_NAME).result.get()
                val offlineRequest = OneTimeWorkRequestBuilder<PeriodicSyncWorker>().build()
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, offlineRequest).result.get()
                val offlineResult = awaitWork(workManager, offlineRequest.id)
                assertEquals("断网时 WorkManager 必须执行 Worker", WorkInfo.State.SUCCEEDED, offlineResult.state)
                assertEquals(
                    "断网时 Worker 必须报告暂时网络失败",
                    PeriodicSyncWorker.STATUS_TEMPORARY_NETWORK_FAILURE,
                    offlineResult.outputData.getString(PeriodicSyncWorker.OUTPUT_STATUS)
                )
                val queuedWhileOffline = requireNotNull(
                    PeriodicSyncPendingUploadStore(
                        File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                    ).read(vaultFile.absolutePath)
                ) { "断网后必须保留待传 revision B" }
                assertEquals(expectedDigest, queuedWhileOffline.contentDigest)

                val retryRequest = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
                    .setInitialDelay(NETWORK_RECOVERY_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build()
                workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, retryRequest).result.get()
                completed = awaitWork(workManager, retryRequest.id)
                assertEquals("网络恢复后 Worker 应完成 B 的条件上传", WorkInfo.State.SUCCEEDED, completed.state)
                assertEquals(
                    PeriodicSyncWorker.STATUS_ENCRYPTED_UPLOAD_COMPLETED,
                    completed.outputData.getString(PeriodicSyncWorker.OUTPUT_STATUS)
                )
                assertEquals(
                    "重试成功后待传队列应清理",
                    false,
                    PeriodicSyncPendingUploadStore(
                        File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                    ).hasEntry(vaultFile.absolutePath)
                )
            } else {
                completed = awaitWork(workManager, persistedWorkId)
                assertEquals("WorkManager 必须真实执行 Worker", WorkInfo.State.SUCCEEDED, completed.state)
            }
            if (remoteChangedBeforeWorker || tamperQueueBeforeWorker) {
                val expectedNeedsUnlockReason = if (remoteChangedBeforeWorker) {
                    PeriodicSyncNeedsUnlockReason.REMOTE_CHANGED
                } else {
                    PeriodicSyncNeedsUnlockReason.PROBE_STATE_CORRUPT
                }
                assertEquals(
                    "不可信队列/远端改变后 Worker 必须拒绝覆盖并要求解锁",
                    PeriodicSyncWorker.STATUS_NEEDS_UNLOCK,
                    completed.outputData.getString(PeriodicSyncWorker.OUTPUT_STATUS)
                )
                assertEquals(
                    expectedNeedsUnlockReason.name,
                    completed.outputData.getString(PeriodicSyncWorker.OUTPUT_REASON)
                )
                val probe = requireNotNull(
                    (probeStore.read(vaultFile.absolutePath) as? PeriodicSyncProbeRead.Stored)?.record
                )
                assertTrue("远端变化必须持久化 needs-unlock 状态", probe.needsUnlock)
                assertTrue(
                    "锁屏恢复查询必须向 UI 暴露待解锁/隔离状态",
                    workCoordinator.hasPendingUserRecovery(vaultFile.absolutePath)
                )
                val queued = requireNotNull(
                    PeriodicSyncPendingUploadStore(
                        File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                    ).read(vaultFile.absolutePath)
                ) { "拒绝覆盖后必须保留待解锁密文队列" }
                assertEquals("队列必须仍对应本地 revision B", expectedDigest, queued.contentDigest)
                if (tamperQueueBeforeWorker) {
                    val recoveryRemotePath = requireNotNull(
                        credentials.captureRotationRemotePath(vaultFile.absolutePath, vaultFile.name)
                    )
                    assertTrue(
                        "损坏密文在验载前不得写入 rollback pendingAcceptance",
                        !SyncRollbackGuard(
                            File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
                            requireNotNull(probe.rollbackVaultScope)
                        ).hasPendingAcceptance(recoveryRemotePath)
                    )
                    assertEquals(
                        "摘要不匹配的队列载荷必须 fail closed",
                        null,
                        PeriodicSyncPendingUploadStore(
                            File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                        ).readPayload(vaultFile.absolutePath, queued)
                    )
                }
            } else {
                assertEquals(
                    "Worker 必须报告锁态密文上传完成",
                    PeriodicSyncWorker.STATUS_ENCRYPTED_UPLOAD_COMPLETED,
                    completed.outputData.getString(PeriodicSyncWorker.OUTPUT_STATUS)
                )

                val probe = requireNotNull(
                    (probeStore.read(vaultFile.absolutePath) as? PeriodicSyncProbeRead.Stored)?.record
                )
                assertEquals(expectedDigest, probe.syncedContentDigest)
                assertTrue("条件写入成功后 ETag 基线必须更新", probe.observedEtag.isNotBlank())
            }

            val downloaded = ByteArrayOutputStream()
            val productionResolver = SyncProviderResolver(credentials, SyncPreferences(log), log)
            val providerResult = productionResolver.useProvider { provider ->
                provider.download(remotePath, downloaded).getOrThrow()
            }
            assertNotNull("持久化配置必须解析到生产 Provider", providerResult)
            val remoteBytes = downloaded.toByteArray()
            if (remoteChangedBeforeWorker || tamperQueueBeforeWorker) {
                assertNotEquals(
                    "ETag 冲突/队列损坏后服务端内容不得被本地 B 覆盖",
                    expectedDigest,
                    SyncCache.sha256Hex(remoteBytes)
                )
            } else {
                assertEquals("受控 HTTPS 服务端应收到 revision B", expectedDigest, SyncCache.sha256Hex(remoteBytes))
            }
            remoteBytes.fill(0)

            val password = MASTER_PASSWORD.toCharArray()
            try {
                assertTrue("正式库必须可由主密码重新打开", databaseSession.open(vaultFile, password) is KdbxResult.Success)
                if (remoteChangedBeforeWorker || tamperQueueBeforeWorker) {
                    val localDatabase = requireNotNull(databaseSession.databaseFlow.value)
                    assertTrue(
                        "远端冲突不得改写本地正式库 revision B",
                        localDatabase.rootGroup.entries.any { entry ->
                            entry.fields[KdbxConstants.Fields.TITLE]?.readString() == "Queue revision B"
                        }
                    )
                    databaseSession.close()
                    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    val launchResult = executeUiAutomationShell(
                        uiAutomation,
                        "am start -W -n ${context.packageName}/com.keepasskey.app.MainActivity"
                    )
                    assertTrue("必须能打开活动库解锁页", launchResult.contains("Status: ok"))
                    SystemClock.sleep(ACTIVITY_RENDER_SETTLE_MS)
                    val recoveryNotice = awaitForegroundAppText(
                        uiAutomation,
                        context.packageName,
                        context.getString(R.string.unlock_sync_recovery_pending),
                        UI_RECOVERY_NOTICE_TIMEOUT_MS
                    )
                    assertNotNull(
                        "冷启恢复提示必须在解锁 UI 实际显示；$recoveryNotice",
                        recoveryNotice
                    )
                    executeUiAutomationShell(uiAutomation, "input keyevent 3")
                    if (tamperQueueBeforeWorker) {
                        val passwordForRecovery = MASTER_PASSWORD.toCharArray()
                        try {
                            assertTrue(
                                "用户按锁屏提示解锁后必须仍能打开正式 revision B",
                                databaseSession.open(vaultFile, passwordForRecovery) is KdbxResult.Success
                            )
                        } finally {
                            passwordForRecovery.fill('\u0000')
                        }
                        val manualRecoveryCoordinator = SyncCoordinator(
                            context,
                            databaseSession,
                            credentials,
                            log,
                            periodicSyncProbeStore = probeStore
                        )
                        val manualOutcome = manualRecoveryCoordinator.syncNow()
                        val recoveryRecord = (probeStore.read(vaultFile.absolutePath) as? PeriodicSyncProbeRead.Stored)?.record
                        val recoveryQueueStore = PeriodicSyncPendingUploadStore(
                            File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
                        )
                        val recoveryQueue = recoveryQueueStore.read(vaultFile.absolutePath)
                        val recoveryQueuePresent = recoveryQueueStore.hasEntry(vaultFile.absolutePath)
                        val recoveryRollbackScope = recoveryRecord?.rollbackVaultScope
                        val recoveryRollbackPath = credentials.captureRotationRemotePath(
                            vaultFile.absolutePath,
                            vaultFile.name
                        )
                        val recoveryRollbackPending = recoveryRollbackScope?.let { scope ->
                            SyncRollbackGuard(
                                File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME),
                                scope
                            ).hasPendingAcceptance(recoveryRollbackPath ?: remotePath)
                        } ?: false
                        val localRevisionBAfterRecovery = databaseSession.databaseFlow.value
                            ?.rootGroup?.entries?.any { entry ->
                                entry.fields[KdbxConstants.Fields.TITLE]?.readString() == "Queue revision B"
                            } == true
                        assertTrue(
                            "解锁后的手动同步必须能从损坏队列恢复正式 B：$manualOutcome",
                            manualOutcome is SyncOutcome.UploadedLocal || manualOutcome is SyncOutcome.UpToDate
                        )
                        assertTrue(
                            "手动收敛后必须清理旧队列并撤下恢复提示（recordNeeds=${recoveryRecord?.needsUnlock}, " +
                                "queueReadable=${recoveryQueue != null}, queueEntry=$recoveryQueuePresent, " +
                                "rollbackScopePresent=${recoveryRollbackScope != null}, localB=$localRevisionBAfterRecovery, " +
                                "rollbackPathPresent=${recoveryRollbackPath != null}, rollbackPending=$recoveryRollbackPending）",
                            !manualRecoveryCoordinator.hasPendingUserRecovery(vaultFile.absolutePath)
                        )
                        val recoveredRemote = ByteArrayOutputStream()
                        productionResolver.useProvider { provider ->
                            provider.download(remotePath, recoveredRemote).getOrThrow()
                        }
                        val recoveredRemoteBytes = recoveredRemote.toByteArray()
                        val recoveredPassword = MASTER_PASSWORD.toCharArray()
                        try {
                            assertTrue(
                                "手动恢复后服务端 KDBX 必须保留正式 revision B",
                                recoveredVaultTitles(recoveredRemoteBytes, recoveredPassword)
                                    .contains("Queue revision B")
                            )
                        } finally {
                            recoveredPassword.fill('\u0000')
                            recoveredRemoteBytes.fill(0)
                        }
                    }
                } else {
                    val unlockedCoordinator = SyncCoordinator(
                        context,
                        databaseSession,
                        credentials,
                        log,
                        periodicSyncProbeStore = probeStore
                    )
                    val converged = unlockedCoordinator.runPeriodicSync()
                    val fullSync = converged as? PeriodicSyncWorkResult.FullSync
                    assertNotNull("解锁后必须执行完整同步: $converged", fullSync)
                    assertTrue(
                        "完整同步收敛后应清除恢复提示",
                        !unlockedCoordinator.hasPendingUserRecovery(vaultFile.absolutePath)
                    )
                    assertTrue(
                        "锁态上传后的完整同步应收敛且不能留下待同步版本: ${fullSync?.outcome}",
                        fullSync?.outcome is SyncOutcome.UpToDate ||
                            fullSync?.outcome is SyncOutcome.UploadedLocal ||
                            fullSync?.outcome is SyncOutcome.MergedAndUploaded
                    )
                    val database = requireNotNull(databaseSession.databaseFlow.value)
                    assertTrue(
                        "解锁收敛后正式库仍含 revision B",
                        database.rootGroup.entries.any { entry ->
                            entry.fields[KdbxConstants.Fields.TITLE]?.readString() == "Queue revision B"
                        }
                    )
                }
            } finally {
                password.fill('\u0000')
            }
        } finally {
            cleanup(runId, vaultFile, remotePath, previousActiveId, databaseSession, credentials, probeStore)
        }
    }

    private suspend fun awaitWork(workManager: WorkManager, id: java.util.UUID): WorkInfo =
        withTimeout(WORK_TIMEOUT_MS) {
            while (true) {
                val info = requireNotNull(workManager.getWorkInfoById(id).get())
                if (info.state.isFinished) return@withTimeout info
                delay(POLL_INTERVAL_MS)
            }
            error("unreachable")
        }

    private fun executeUiAutomationShell(uiAutomation: android.app.UiAutomation, command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun awaitForegroundAppText(
        uiAutomation: android.app.UiAutomation,
        packageName: String,
        expectedText: String,
        timeoutMillis: Long
    ): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastPackage = ""
        var lastAppText = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val root = runCatching { uiAutomation.rootInActiveWindow }.getOrNull()
            if (root != null) {
                lastPackage = root.packageName?.toString().orEmpty()
                if (lastPackage == packageName) {
                    val collected = StringBuilder()
                    fun collect(node: AccessibilityNodeInfo?, depth: Int) {
                        if (node == null || depth > 24 || collected.length > 32_000) return
                        node.text?.toString()?.let { collected.append(it).append('\n') }
                        node.contentDescription?.toString()?.let { collected.append(it).append('\n') }
                        for (index in 0 until node.childCount) collect(node.getChild(index), depth + 1)
                    }
                    collect(root, 0)
                    lastAppText = collected.toString()
                    if (lastAppText.contains(expectedText)) return lastAppText
                }
            }
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }
        return "app=$lastPackage text=$lastAppText"
    }

    private fun recoveredVaultTitles(bytes: ByteArray, password: CharArray): List<String> {
        val credentialCopy = password.clone()
        val database = try {
            KdbxFile.load(ByteArrayInputStream(bytes), credentialCopy)
        } finally {
            credentialCopy.fill('\u0000')
        }
        return try {
            database.rootGroup.allEntries().map { it.title }
        } finally {
            database.clearSensitiveData()
        }
    }

    private suspend fun cleanup(
        runId: String,
        vaultFile: File,
        remotePath: String,
        previousActiveId: String?,
        databaseSession: DatabaseSession,
        credentials: SyncCredentialsStore,
        probeStore: PeriodicSyncProbeStore
    ) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).result.get() }
        runCatching { databaseSession.close() }
        runCatching { credentials.clearFor(vaultFile.absolutePath) }
        runCatching { probeStore.clear(vaultFile.absolutePath) }
        runCatching {
            PeriodicSyncPendingUploadStore(
                File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
            ).clear(vaultFile.absolutePath)
        }
        val scope = stateStore().getString(KEY_ROLLBACK_SCOPE, null)
        if (scope != null) {
            val stateFileName = SyncCache.sha256Hex("$scope\n$remotePath".toByteArray(Charsets.UTF_8)) +
                SyncRollbackGuard.SUFFIX_STATE
            File(File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME), stateFileName).delete()
        }
        ActiveDatabaseIdStore(context).save(previousActiveId)
        File(vaultFile.absolutePath + ".bak").delete()
        vaultFile.delete()
        stateStore().edit().clear().putString(KEY_LAST_CLEANUP_RUN, runId).commit()
    }

    private fun productionCredentials(databaseSession: DatabaseSession, log: DebugLogBuffer): SyncCredentialsStore =
        SyncCredentialsStore(
            context,
            KeystoreManager(context, log),
            log,
            SyncCacheEvictor(context, log),
            databaseSession
        )

    private fun stateStore() = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun vaultFile(runId: String): File = File(context.filesDir, "p2-627-queue-$runId.kdbx")
    private fun remotePath(runId: String): String = "/p2-627-queue-$runId.kdbx"

    private fun entry(title: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(MASTER_PASSWORD, isProtected = true)
        )
    )

    private companion object {
        const val ARG_RUN_ID = "p2_627_run_id"
        const val ARG_PHASE = "p2_627_phase"
        const val ARG_WORK_START_DELAY_SECONDS = "p2_627_work_start_delay_seconds"
        const val ARG_REMOTE_CHANGED_BEFORE_WORKER = "p2_627_remote_changed_before_worker"
        const val ARG_TAMPER_QUEUE_BEFORE_WORKER = "p2_627_tamper_queue_before_worker"
        const val ARG_NETWORK_UNAVAILABLE_BEFORE_WORKER = "p2_627_network_unavailable_before_worker"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_RESUME = "resume"
        const val ARG_WEBDAV_PORT = "p2_627_webdav_port"
        const val WEBDAV_USER = "p2-627-test"
        const val WEBDAV_PASSWORD = "Synthetic-P2-627-WebDav-Password"
        const val MASTER_PASSWORD = "Synthetic-P2-627-Master-Password"
        const val VAULT_NAME = "P2-627 Locked Queue"
        const val WORK_NAME = "p2_627_locked_queue_worker"
        const val STATE_PREFS = "p2_627_locked_queue_device_test"
        const val KEY_RUN_ID = "run_id"
        const val KEY_VAULT_PATH = "vault_path"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_PREVIOUS_ACTIVE_ID = "previous_active_id"
        const val KEY_REVISION_B_DIGEST = "revision_b_digest"
        const val KEY_ROLLBACK_SCOPE = "rollback_scope"
        const val KEY_PREPARE_PID = "prepare_pid"
        const val KEY_WORK_ID = "work_id"
        const val KEY_PREPARED = "prepared"
        const val KEY_LAST_CLEANUP_RUN = "last_cleanup_run"
        const val WORK_TIMEOUT_MS = 360_000L
        const val UI_RECOVERY_NOTICE_TIMEOUT_MS = 20_000L
        const val ACTIVITY_RENDER_SETTLE_MS = 1_500L
        const val UI_POLL_INTERVAL_MS = 300L
        const val WORK_START_DELAY_SECONDS = 60L
        const val NETWORK_RECOVERY_RETRY_DELAY_SECONDS = 60L
        const val POLL_INTERVAL_MS = 250L
    }
}
