package com.keepasskey.app.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
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
        rollbackDir: File
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
        ).apply { syncCacheFactory = { SyncCache(cacheDir) } }
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
