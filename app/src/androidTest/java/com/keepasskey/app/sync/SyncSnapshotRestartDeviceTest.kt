package com.keepasskey.app.sync

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.RollbackVerdict
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.provider.SyncProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P1-591：独立 AVD 上以本地 HTTPS 远端验证上传快照、进程重建与他端并发修改。 */
@RunWith(AndroidJUnit4::class)
class SyncSnapshotRestartDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 正式库B保存后高水位提交失败且协调器重建时旧远端A不能覆盖B() = runBlocking {
        val suffix = System.nanoTime().toString()
        val vaultFile = File(context.filesDir, "p2621-formal-$suffix.kdbx")
        val cacheDir = File(context.cacheDir, "p2621-formal-$suffix")
        val rollbackDir = File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME)
        val password = "P2-621-device-${suffix.takeLast(6)}".toCharArray()
        val tlsCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(tlsCertificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(tlsCertificate.certificate)
            .build()
        val server = LocalHttpsRemoteStore(serverTls.sslSocketFactory())
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()
        val remotePath = "p2621-$suffix.kdbx"
        val session = DatabaseSession()
        cleanupInterruptedRecoveryCases(rollbackDir)
        val activeIdStore = ActiveDatabaseIdStore(context)
        val storedActiveId = activeIdStore.load()
        val previousActiveId = storedActiveId?.takeUnless {
            File(it).name.startsWith("p2621-formal-")
        }
        if (previousActiveId != storedActiveId) activeIdStore.save(previousActiveId)
        var reopenedSession: DatabaseSession? = null
        var initialCoordinator: SyncCoordinator? = null
        var failedCoordinator: SyncCoordinator? = null
        var restartedCoordinator: SyncCoordinator? = null
        var initialProvider: SnapshotHttpsProvider? = null
        var failedProvider: SnapshotHttpsProvider? = null
        var restartedProvider: SnapshotHttpsProvider? = null
        try {
            assertTrue(
                "合成库 A 创建失败",
                session.create(vaultFile, "P2-621 formal recovery", password, useArgon2 = false) is KdbxResult.Success
            )
            ActiveDatabaseIdStore(context).save(vaultFile.absolutePath)
            session.saveEntry(entry("revision A"))
            assertTrue("正式库 A 保存失败", session.save() is KdbxResult.Success)
            initialProvider = SnapshotHttpsProvider(server.start(), remotePath, httpClient)
            initialCoordinator = coordinator(session, initialProvider!!, cacheDir, rollbackDir)
            assertEquals("初始 revision A 必须上传", SyncOutcome.UploadedLocal, initialCoordinator!!.syncNow())
            val remoteA = server.file(remotePath)

            val remoteDb = load(remoteA, password)
            val remoteB = save(
                remoteDb.copy(rootGroup = remoteDb.rootGroup.copy(
                    entries = remoteDb.rootGroup.entries + entry("formal revision B")
                )),
                password
            )
            val remoteC = save(
                remoteDb.copy(rootGroup = remoteDb.rootGroup.copy(
                    entries = remoteDb.rootGroup.entries + entry("concurrent revision C")
                )),
                password
            )
            remoteDb.clearSensitiveData()
            val remoteBDigest = SyncCache.sha256Hex(remoteB)
            assertTrue("独立 HTTPS 客户端必须将 B 发布为远端当前版本", server.putFromOtherClient(remotePath, remoteB, httpClient))

            failedProvider = SnapshotHttpsProvider(server.start(), remotePath, httpClient)
            failedCoordinator = coordinator(
                session,
                failedProvider!!,
                cacheDir,
                rollbackDir,
                failHighWaterDigest = remoteBDigest
            )
            val failed = failedCoordinator!!.syncNow()
            assertTrue("定点高水位原子替换失败必须报告错误: $failed", failed is SyncOutcome.Error)
            assertTrue(
                "无 P2-627 探测记录时正式库待采纳意图仍须能被恢复查询发现",
                failedCoordinator!!.hasPendingUserRecovery(vaultFile.absolutePath)
            )
            val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val packageName = context.packageName
            val launchResult = executeUiAutomationShell(
                uiAutomation,
                "am start -W -n $packageName/com.keepasskey.app.MainActivity"
            )
            assertTrue("冷启动必须能进入活动库解锁页", launchResult.contains("Status: ok"))
            val recoveryNotice = awaitForegroundAppText(
                uiAutomation,
                packageName,
                context.getString(com.keepasskey.app.R.string.unlock_sync_recovery_pending),
                UI_RECOVERY_NOTICE_TIMEOUT_MS
            )
            assertTrue("无探测记录 marker 必须在解锁界面真实可见；$recoveryNotice", recoveryNotice != null)
            executeUiAutomationShell(uiAutomation, "input keyevent 3")
            assertTrue(
                "故障发生前远端 B 必须已保存到正式 KDBX",
                titles(vaultFile.readBytes(), password).contains("formal revision B")
            )

            // 新 DatabaseSession/SyncCoordinator 代表进程重建，从正式文件读取已提交 B。
            session.close()
            val coldSession = DatabaseSession()
            reopenedSession = coldSession
            assertTrue("冷启动后正式库 B 必须可解锁", coldSession.open(vaultFile, password.clone()) is KdbxResult.Success)
            assertTrue("冷启动会话必须读到 B", titles(coldSession.databaseFlow.value!!).contains("formal revision B"))
            assertTrue("把远端回放为旧 A", server.putFromOtherClient(remotePath, remoteA, httpClient))
            restartedProvider = SnapshotHttpsProvider(server.start(), remotePath, httpClient)
            restartedCoordinator = coordinator(coldSession, restartedProvider!!, cacheDir, rollbackDir)
            assertTrue(
                "协调器重建后待采纳意图仍须对恢复查询可见",
                restartedCoordinator!!.hasPendingUserRecovery(vaultFile.absolutePath)
            )
            val restartedOutcome = restartedCoordinator!!.syncNow()
            assertTrue("持久 quarantine 必须拒绝冷启动后的远端 A：$restartedOutcome", restartedOutcome is SyncOutcome.Error)
            assertTrue(
                "A 重放后正式 KDBX B 仍须保全",
                titles(vaultFile.readBytes(), password).contains("formal revision B")
            )
            val guard = SyncRollbackGuard(rollbackDir)
            assertEquals(
                "未完成的 B 采纳意图在协调器重建后仍须隔离",
                RollbackVerdict.RecoveryRequired,
                guard.inspect(remotePath, remoteBDigest)
            )
            restartedProvider!!.beforeNextUpload = {
                assertTrue(
                    "ETag 读取后模拟他端并发提交 C",
                    server.putFromOtherClient(remotePath, remoteC, httpClient)
                )
            }
            val conflictRecovery = restartedCoordinator!!.recoverPendingAcceptanceByPublishingLocal()
            assertTrue("并发写入导致 412 时不得清除恢复隔离: $conflictRecovery", conflictRecovery is SyncOutcome.Error)
            assertTrue(
                "412 后恢复操作必须仍处于待处理状态",
                restartedCoordinator!!.hasPendingUserRecovery(vaultFile.absolutePath)
            )
            val preservedRemoteC = server.file(remotePath)
            assertTrue("412 后并发云端 C 必须保全", titles(preservedRemoteC, password).contains("concurrent revision C"))
            preservedRemoteC.fill(0)

            // 用户看到 412 后再次确认以本地为准；第二次读取 C 的新 ETag 后才允许替换。
            val recoveryOutcome = restartedCoordinator!!.recoverPendingAcceptanceByPublishingLocal()
            assertEquals(
                "用户确认保留本地后，应以当前远端 ETag 条件上传并完成恢复",
                SyncOutcome.UploadedLocal,
                recoveryOutcome
            )
            assertTrue(
                "显式恢复成功后应撤下待处理状态",
                !restartedCoordinator!!.hasPendingUserRecovery(vaultFile.absolutePath)
            )
            val recoveredRemote = server.file(remotePath)
            assertTrue(
                "条件恢复上传应保留本地正式库 B 到 HTTPS 远端",
                titles(recoveredRemote, password).contains("formal revision B")
            )
            assertEquals(
                "显式恢复后远端 A 仍须作为已见历史版本被拒绝",
                RollbackVerdict.ReplayDetected,
                guard.inspect(remotePath, SyncCache.sha256Hex(remoteA))
            )
            recoveredRemote.fill(0)
            remoteA.fill(0)
            remoteB.fill(0)
            remoteC.fill(0)
        } finally {
            initialCoordinator?.let { runCatching { session.removeLockObserver(it) } }
            failedCoordinator?.let { runCatching { session.removeLockObserver(it) } }
            restartedCoordinator?.let { coordinator -> reopenedSession?.let { runCatching { it.removeLockObserver(coordinator) } } }
            initialProvider?.close()
            failedProvider?.close()
            restartedProvider?.close()
            session.close()
            reopenedSession?.close()
            server.close()
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
            password.fill('\u0000')
            ActiveDatabaseIdStore(context).save(previousActiveId)
            vaultFile.delete()
            File(vaultFile.absolutePath + ".bak").delete()
            cacheDir.deleteRecursively()
            File(
                rollbackDir,
                SyncCache.sha256Hex(vaultFile.absolutePath.toByteArray(Charsets.UTF_8)) +
                    SyncRollbackGuard.SUFFIX_VAULT_PENDING
            ).delete()
            File(
                rollbackDir,
                SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8)) + SyncRollbackGuard.SUFFIX_STATE
            ).delete()
        }
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
            }
            if (root != null && lastPackage == packageName) {
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
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }
        return "app=$lastPackage text=$lastAppText"
    }

    private fun cleanupInterruptedRecoveryCases(rollbackDir: File) {
        val markerSuffix = SyncRollbackGuard.SUFFIX_VAULT_PENDING
        context.filesDir.listFiles { file -> file.name.startsWith("p2621-formal-") && file.extension == "kdbx" }
            .orEmpty()
            .forEach { vaultFile ->
                val suffix = vaultFile.name.removePrefix("p2621-formal-").removeSuffix(".kdbx")
                val remotePath = "p2621-$suffix.kdbx"
                File(
                    rollbackDir,
                    SyncCache.sha256Hex(vaultFile.absolutePath.toByteArray(Charsets.UTF_8)) + markerSuffix
                ).delete()
                File(
                    rollbackDir,
                    SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8)) + SyncRollbackGuard.SUFFIX_STATE
                ).delete()
                vaultFile.delete()
                File(vaultFile.absolutePath + ".bak").delete()
            }
    }

    @Test
    fun 上传A期间保存B进程重建后与他端C合并且远端不丢任何版本() = runBlocking {
        val suffix = System.nanoTime().toString()
        val vaultFile = File(context.filesDir, "p1591-sync-$suffix.kdbx")
        val cacheDir = File(context.cacheDir, "p1591-sync-$suffix")
        val rollbackDir = File(context.filesDir, "p1591-rollback-$suffix")
        val password = "P1-591-AVD-${suffix.takeLast(6)}".toCharArray()
        val tlsCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(tlsCertificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(tlsCertificate.certificate)
            .build()
        val server = LocalHttpsRemoteStore(serverTls.sslSocketFactory())
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()
        val remotePath = "p1591-$suffix.kdbx"
        val remoteUrl = server.start()
        val firstSession = DatabaseSession()
        var firstProvider: SnapshotHttpsProvider? = null
        var restartedProvider: SnapshotHttpsProvider? = null
        var firstCoordinator: SyncCoordinator? = null
        var restartedCoordinator: SyncCoordinator? = null
        var restartedSession: DatabaseSession? = null
        try {
            assertTrue(
                "合成库创建失败",
                firstSession.create(vaultFile, "P1-591 device sync", password, useArgon2 = false) is KdbxResult.Success
            )
            firstSession.saveEntry(entry("A"))
            assertTrue("初始 A 写盘失败", firstSession.save() is KdbxResult.Success)

            firstProvider = SnapshotHttpsProvider(remoteUrl, remotePath, httpClient)
            val uploadGate = firstProvider!!.pauseNextUpload()
            firstCoordinator = coordinator(firstSession, firstProvider!!, cacheDir, rollbackDir)
            val cycle = async(Dispatchers.Default) { firstCoordinator!!.syncNow() }
            assertTrue("本地 HTTPS 服务未收到首传 A", withContext(Dispatchers.IO) {
                uploadGate.reached.await(20, TimeUnit.SECONDS)
            })

            val uploadedA = server.file(remotePath)
            assertTrue("远端首传必须只包含周期起点 A", titles(uploadedA, password).contains("A"))
            assertTrue("远端首传不得包含尚未编辑的 B", "B" !in titles(uploadedA, password))

            firstSession.saveEntry(entry("B"))
            assertTrue("上传窗口内的 B 未写入本地正式库", firstSession.save() is KdbxResult.Success)
            uploadGate.release.countDown()
            val firstOutcome = withTimeout(30_000) { cycle.await() }
            assertEquals("窗口内出现 B 后本轮必须报告仍有待同步修改", SyncOutcome.LocalPendingChanges, firstOutcome)
            assertTrue("本地正式库必须保留 B", titles(vaultFile.readBytes(), password).containsAll(listOf("A", "B")))
            assertTrue("远端本轮仍应是上传快照 A", "B" !in titles(server.file(remotePath), password))

            // 以关闭旧会话并从正式 KDBX 重开，模拟应用进程销毁及新进程恢复。
            firstSession.close()
            val freshSession = DatabaseSession()
            restartedSession = freshSession
            assertTrue("进程重建后从正式库解锁失败", freshSession.open(vaultFile, password.clone()) is KdbxResult.Success)
            assertTrue("进程重建后必须恢复已落盘的 B", titles(freshSession.databaseFlow.value!!).containsAll(listOf("A", "B")))

            // 独立第二客户端从远端 A 派生 C 并直接 HTTPS PUT，制造同一基线上的双端分叉。
            val remoteA = server.file(remotePath)
            val remoteDatabase = load(remoteA, password)
            val remoteWithC = remoteDatabase.copy(
                rootGroup = remoteDatabase.rootGroup.copy(entries = remoteDatabase.rootGroup.entries + entry("C"))
            )
            val remoteC = save(remoteWithC, password)
            assertTrue("他端 C 写入远端失败", server.putFromOtherClient(remotePath, remoteC, httpClient))
            remoteWithC.clearSensitiveData()
            remoteA.fill(0)
            remoteC.fill(0)

            restartedProvider = SnapshotHttpsProvider(remoteUrl, remotePath, httpClient)
            restartedCoordinator = coordinator(freshSession, restartedProvider!!, cacheDir, rollbackDir)
            val resumedOutcome = withTimeout(60_000) { restartedCoordinator!!.syncNow() }
            assertTrue("进程重建后的 B/C 双端差异必须完成三方合并：$resumedOutcome", resumedOutcome is SyncOutcome.MergedAndUploaded)

            val mergedLocal = freshSession.databaseFlow.value
                ?: error("合并后活动会话为空")
            assertTrue("会话合并结果必须同时包含 A/B/C", titles(mergedLocal).containsAll(listOf("A", "B", "C")))
            val mergedRemote = server.file(remotePath)
            assertTrue("HTTPS 远端合并产物必须同时包含 A/B/C", titles(mergedRemote, password).containsAll(listOf("A", "B", "C")))
            mergedRemote.fill(0)
        } finally {
            firstCoordinator?.let { runCatching { firstSession.removeLockObserver(it) } }
            restartedCoordinator?.let { coordinator -> restartedSession?.let { runCatching { it.removeLockObserver(coordinator) } } }
            firstProvider?.close()
            restartedProvider?.close()
            firstSession.close()
            restartedSession?.close()
            server.close()
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
            password.fill('\u0000')
            vaultFile.delete()
            File(vaultFile.absolutePath + ".bak").delete()
            cacheDir.deleteRecursively()
            rollbackDir.deleteRecursively()
        }
    }

    private fun coordinator(
        session: DatabaseSession,
        provider: SnapshotHttpsProvider,
        cacheDir: File,
        rollbackDir: File,
        failHighWaterDigest: String? = null
    ): SyncCoordinator {
        val log = DebugLogBuffer()
        val preferences = SyncPreferences(log, null)
        val credentials = SyncCredentialsStore(context, null, log)
        val providerResolver = SyncProviderResolver(credentials, preferences, log)
        val sessionState = SyncSessionState().apply { testRemotePath = provider.remotePath }
        val strings = StringsProvider { id, args -> context.getString(id, *args) }
        val codec = SyncDatabaseCodec(session, log)
        val conflicts = SyncConflictController(session, codec, strings, sessionState, log)
        val changes = SyncContentChangeDetector(codec, sessionState)
        val cycle = SyncCycleRunner(
            context = context,
            databaseSession = session,
            session = sessionState,
            providerResolver = providerResolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = strings,
            rollbackStateDir = rollbackDir
        ).apply {
            syncCacheFactory = { SyncCache(cacheDir) }
            if (failHighWaterDigest != null) {
                var injectFailure = true
                rollbackGuardFactoryForTest = { stateDir, scope, identity ->
                    SyncRollbackGuard(stateDir, scope, { next ->
                        if (injectFailure && next.current == failHighWaterDigest && next.pendingDigest == null) {
                            injectFailure = false
                            throw java.io.IOException("injected formal-vault high-water replacement failure")
                        }
                    }, identity)
                }
            }
        }
        return SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = credentials,
            debugLog = log,
            strings = strings,
            syncSession = sessionState,
            syncPreferences = preferences,
            syncProviderResolver = providerResolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        ).also { it.testSyncProvider = provider }
    }

    private fun titles(sessionDb: KdbxDatabase): List<String> =
        sessionDb.rootGroup.allEntries().map { it.title }

    private fun titles(bytes: ByteArray, password: CharArray): List<String> {
        val database = load(bytes, password)
        return try {
            titles(database)
        } finally {
            database.clearSensitiveData()
        }
    }

    private fun load(bytes: ByteArray, password: CharArray): KdbxDatabase {
        val credentialCopy = password.clone()
        return try {
            KdbxFile.load(ByteArrayInputStream(bytes), credentialCopy)
        } finally {
            credentialCopy.fill('\u0000')
        }
    }

    private fun save(database: KdbxDatabase, password: CharArray): ByteArray {
        val output = ByteArrayOutputStream()
        val credentialCopy = password.clone()
        try {
            KdbxFile.save(output, database, credentialCopy, null)
            return output.toByteArray()
        } finally {
            credentialCopy.fill('\u0000')
        }
    }

    private fun entry(title: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title))
    )

    private companion object {
        const val UI_RECOVERY_NOTICE_TIMEOUT_MS = 10_000L
        const val UI_POLL_INTERVAL_MS = 250L
    }
}

/** 使用独立测试 TLS 身份的 HTTPS 远端；请求经真实 socket，数据只在合成内存映射中。 */
private class LocalHttpsRemoteStore(sslSocketFactory: javax.net.ssl.SSLSocketFactory) : AutoCloseable {
    private val files = linkedMapOf<String, ByteArray>()
    private val server = okhttp3.mockwebserver.MockWebServer()
    private val dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
            val path = request.path.orEmpty()
            synchronized(files) {
                return when (request.method) {
                    "GET" -> {
                        val bytes = files[path] ?: return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                        okhttp3.mockwebserver.MockResponse()
                            .setResponseCode(200)
                            .setHeader("ETag", etag(bytes))
                            .setBody(Buffer().write(bytes))
                    }
                    "PUT" -> {
                        val current = files[path]
                        val ifMatch = request.getHeader("If-Match")
                        if (ifMatch != null && (current == null || cleanEtag(ifMatch) != cleanEtag(etag(current)))) {
                            return okhttp3.mockwebserver.MockResponse()
                                .setResponseCode(412)
                                .setHeader("ETag", current?.let(::etag).orEmpty())
                        }
                        val bytes = request.body.readByteArray()
                        files[path] = bytes
                        okhttp3.mockwebserver.MockResponse()
                            .setResponseCode(201)
                            .setHeader("ETag", etag(bytes))
                    }
                    "DELETE" -> {
                        files.remove(path)
                        okhttp3.mockwebserver.MockResponse().setResponseCode(204)
                    }
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(405)
                }
            }
        }
    }

    init {
        server.dispatcher = dispatcher
        server.useHttps(sslSocketFactory, false)
        server.start()
    }

    fun start(): String = server.url("/").toString().trimEnd('/')

    fun file(remotePath: String): ByteArray = synchronized(files) {
        requireNotNull(files["/$remotePath"]) { "synthetic remote object missing" }.clone()
    }

    fun putFromOtherClient(remotePath: String, bytes: ByteArray, client: OkHttpClient): Boolean {
        val request = Request.Builder()
            .url("${server.url("/").toString().trimEnd('/')}/$remotePath")
            .put(bytes.clone().toRequestBody(null))
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    override fun close() {
        synchronized(files) { files.values.forEach { it.fill(0) }; files.clear() }
        server.shutdown()
    }

    private fun etag(bytes: ByteArray): String = "\"" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) } + "\""
}

private class SnapshotHttpsProvider(
    private val baseUrl: String,
    val remotePath: String,
    private val client: OkHttpClient
) : SyncProvider, AutoCloseable {
    @Volatile
    private var nextUploadGate: UploadGate? = null
    @Volatile
    var beforeNextUpload: (() -> Unit)? = null
    data class UploadGate(val reached: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

    fun pauseNextUpload(): UploadGate = UploadGate().also { nextUploadGate = it }

    override suspend fun testConnection(): Result<Unit> = request { Result.success(Unit) }

    override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> = request {
        val response = execute("GET", remotePath)
        response.use {
            if (it.code == 404) throw SyncException.FileNotFound("synthetic remote file missing")
            if (!it.isSuccessful) throw SyncException.ProtocolError(it.code, "synthetic HTTPS GET failed")
            val bytes = requireNotNull(it.body).bytes()
            try {
                RemoteFileMetadata(remotePath, cleanEtag(it.header("ETag")), bytes.size.toLong(), 0L)
            } finally {
                bytes.fill(0)
            }
        }
    }

    override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> = request {
        execute("GET", remotePath).use {
            if (it.code == 404) throw SyncException.FileNotFound("synthetic remote file missing")
            if (!it.isSuccessful) throw SyncException.ProtocolError(it.code, "synthetic HTTPS download failed")
            requireNotNull(it.body).byteStream().use { input -> input.copyTo(sink) }
        }
    }

    override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> = request {
        beforeNextUpload?.also { beforeUpload ->
            beforeNextUpload = null
            beforeUpload()
        }
        val builder = Request.Builder().url(url(remotePath)).put(data.clone().toRequestBody(null))
        if (expectedEtag != null) {
            val strongTag = if (expectedEtag.startsWith('"')) expectedEtag else "\"$expectedEtag\""
            builder.header("If-Match", strongTag)
        }
        execute(builder.build()).use { response ->
            if (response.code == 412) {
                throw SyncException.ConflictError(
                    cleanEtag(response.header("ETag")), expectedEtag.orEmpty(), "synthetic ETag changed"
                )
            }
            if (!response.isSuccessful) throw SyncException.ProtocolError(response.code, "synthetic HTTPS upload failed")
            val etag = cleanEtag(response.header("ETag"))
            check(etag.isNotBlank()) { "synthetic HTTPS PUT omitted ETag" }
            nextUploadGate?.also { gate ->
                nextUploadGate = null
                gate.reached.countDown()
                check(gate.release.await(20, TimeUnit.SECONDS)) { "upload test gate timed out" }
            }
            etag
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> = request {
        execute("DELETE", remotePath).use {
            if (!it.isSuccessful && it.code != 404) throw SyncException.ProtocolError(it.code, "synthetic HTTPS DELETE failed")
        }
    }

    override fun close() {
        nextUploadGate?.release?.countDown()
        nextUploadGate = null
    }

    private fun url(remotePath: String) = "$baseUrl/$remotePath"

    private fun execute(method: String, remotePath: String) = execute(
        Request.Builder().url(url(remotePath)).method(method, null).build()
    )

    private fun execute(request: Request) = client.newCall(request).execute()

    private suspend fun <T> request(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
