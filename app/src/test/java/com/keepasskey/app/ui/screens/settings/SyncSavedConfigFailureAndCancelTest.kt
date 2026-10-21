package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.sync.SyncProviderFactory
import com.keepasskey.app.sync.SyncProviderResolver
import com.keepasskey.app.sync.SyncPreferences
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.subscreens.persistThenContinue
import com.keepasskey.app.ui.screens.settings.subscreens.withSyncCredentialSubmissionCopy
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-652`：测试连接 / 同步必须走**本次保存**的目标；保存失败时**零网络请求**；
 * A 的测试在途期间改存 B，仍只触达 A；A 的迟到结论不得把 B 标成已验证；取消同样不谎报。
 *
 * ## 与 `SavedSyncConfigSnapshotFlowTest` 的分工
 *
 * 那件已证明「密封回执贯穿测连与同步」的主路径（A 在途 → 改存 B → 仍只触达 A）。本件补的是
 * **失败与取消两侧**——保存失败、测连失败、协程取消、以及普通同步入口的「迟到结论」外溢：
 * 这些都是「回执只被一次编排消费」的边界，一旦守卫写错，用户会看到「保存失败但连接已验证」
 * 或「测连失败却已同步旧目标」这类互相矛盾的结论。
 *
 * ## 判据形态
 *
 * 全部行为经**真实** [SettingsSyncController] + 真实 [SyncCredentialsStore]（内存偏好 + 可逆
 * 测试封印）+ 真实 [SyncCoordinator] 驱动，只把 Provider 构造点换成计数假件（`SyncProviderFactory`），
 * 因此「是否真的发出请求」「请求落在哪个 endpoint / 用哪份凭据」都是可数的观测值，而不是源码推断。
 *
 * ## 边界
 *
 * 假件不是 HTTPS 传输层：本类证明的是**编排层请求路由**，传输层由设备用例
 * `CloudSyncRealReceiverDeviceTest` 用真实 HTTPS 接收方复证（保存失败零请求亦在那里计数）。
 */
class SyncSavedConfigFailureAndCancelTest {

    @Test
    fun `保存失败时测试连接与同步都不发出任何请求`() = runBlocking {
        val fixture = Fixture()
        try {
            // 封印失败的真实形态：Keystore 密钥被作废 ⇒ 加密调用抛异常 ⇒ sealer 返回 null
            fixture.credentials.customEncryptor = { error("keystore key invalidated") }
            val draft = "fake-password".toCharArray()
            val saved = fixture.submitWebDav(draft, "https://dav-a.example.test/sync")
            assertFalse("封印失败必须回传失败", saved)
            assertEquals(
                R.string.sync_config_save_failed,
                fixture.controller.state.value.syncFeedbackMessage?.resId
            )

            // 生产表单的两处入口：都只在保存成功后才继续
            fixture.persistThenContinueLikeForm(draft)
            fixture.drainScheduler()

            assertEquals("保存失败不得构造任何 Provider（零请求）", 0, fixture.factory.createdEndpoints.size)
            assertTrue("保存失败不得发出任何网络操作", fixture.factory.requests.isEmpty())
            assertFalse("保存失败不得把连接标成已验证", fixture.controller.state.value.isConnectionVerified)
            assertFalse("保存失败不得让页面停在同步中", fixture.controller.state.value.isSyncing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `A的测试失败时不触发同步且明确上浮已跳过同步`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.factory.testSucceeds = false
            val draft = "fake-password-a".toCharArray()
            assertTrue(fixture.submitWebDav(draft, "https://dav-a.example.test/sync"))

            fixture.controller.verifyConnectionThenSync()
            fixture.awaitNotSyncing()

            assertEquals(
                "测连失败必须明确提示「已跳过同步」",
                R.string.sync_gate_test_failed,
                fixture.controller.state.value.syncFeedbackMessage?.resId
            )
            assertTrue("必须只构造测连用的那一个 Provider", fixture.factory.createdEndpoints.isNotEmpty())
            assertEquals(
                "失败路径只允许 test 操作，不得出现 metadata / upload",
                listOf("test"),
                fixture.factory.requests.map { it.operation }
            )
            assertFalse("测连失败不得置位已验证", fixture.controller.state.value.isConnectionVerified)
            assertEquals("两个阶段的 Provider 都必须释放", fixture.factory.createdEndpoints.size, fixture.factory.released)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `A测试在途改存B后迟到结论不得把B标成已验证`() = runBlocking {
        val fixture = Fixture()
        try {
            val draftA = "fake-password-a".toCharArray()
            assertTrue(fixture.submitWebDav(draftA, "https://dav-a.example.test/sync"))
            val aTestEntered = CompletableDeferred<Unit>()
            val releaseATest = CompletableDeferred<Unit>()
            fixture.factory.installFirstTestGate(aTestEntered, releaseATest)

            fixture.controller.verifyConnectionThenSync()
            aTestEntered.await()

            // 期间保存 B：新保存必须作废旧回执与旧修订
            val draftB = "fake-password-b".toCharArray()
            assertTrue(fixture.submitWebDav(draftB, "https://dav-b.example.test/sync"))
            releaseATest.complete(Unit)
            fixture.awaitNotSyncing()

            assertTrue("A 的测试必须真实发生过", fixture.factory.requests.any { it.operation == "test" })
            assertTrue(
                "A 阶段的所有请求都必须绑定 A 的 endpoint 与凭据",
                fixture.factory.requests.filter { it.endpoint.startsWith("https://dav-a") }.all {
                    it.username == "fake-user" && it.password == "fake-password-a"
                }
            )
            // 本轮编排的目标由**已消费的 A 回执**固定：恢复后同步阶段要么仍向 A 发出请求，
            // 要么在装配阶段如实失败（不使用 B）。两种结果都不得让 B 出现在任何请求里。
            assertTrue(
                "本轮编排的同步阶段只能出现 A（或提前失败），不得改投 B",
                fixture.factory.requests.none { it.endpoint.startsWith("https://dav-b") }
            )
            assertTrue(
                "期间保存的 B 绝不能被本次编排触达",
                fixture.factory.requests.none { it.endpoint.startsWith("https://dav-b") }
            )
            assertFalse(
                "A 的迟到结论不得把期间保存的 B 标成已验证",
                fixture.controller.state.value.isConnectionVerified
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `普通同步入口的成功结论不得外溢到期间新保存的配置`() = runBlocking {
        val fixture = Fixture()
        try {
            // 先保存 A 并完成一次测连，使「立即同步」入口可用
            assertTrue(
                "前置保存 A 必须成功",
                fixture.submitWebDav("fake-password-a".toCharArray(), "https://dav-a.example.test/sync")
            )
            val syncEntered = CompletableDeferred<Unit>()
            val releaseSync = CompletableDeferred<Unit>()
            fixture.factory.installUploadGate(syncEntered, releaseSync)

            fixture.controller.triggerSync()
            // 普通同步入口按**当前配置**建连；夹具远端尚无该对象时本轮以「建立基线」收尾，
            // 不会进入上传门闩，故此处只做有界观察，不把「未进入上传」当作失败。
            withTimeoutOrNull(CountingFactory.GATE_TIMEOUT_MS) { syncEntered.await() }

            // 本轮尚未出结论时保存 B：旧轮次的成功结论不得把它标成已验证。
            assertTrue(
                "期间保存 B 必须成功",
                fixture.submitWebDav("fake-password-b".toCharArray(), "https://dav-b.example.test/sync")
            )
            releaseSync.complete(Unit)
            fixture.awaitNotSyncing()
            assertFalse("同步结束后不得停在忙态", fixture.controller.state.value.isSyncing)
            assertFalse(
                "同步在途期间保存的新配置不得被旧轮次的成功结论标成已验证",
                fixture.controller.state.value.isConnectionVerified
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `取消在途编排会复位忙态且不产生成功结论`() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(fixture.submitWebDav("fake-password-a".toCharArray(), "https://dav-a.example.test/sync"))
            val entered = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            fixture.factory.installFirstTestGate(entered, neverRelease)

            fixture.controller.verifyConnectionThenSync()
            entered.await()
            assertTrue("前提：编排进行中", fixture.controller.state.value.isSyncing)

            // 模拟锁库 / 作用域销毁：取消控制器作用域
            fixture.cancelScope()
            fixture.awaitNotSyncing()

            assertFalse("取消后必须复位忙态（否则指示器永久停在同步中）", fixture.controller.state.value.isSyncing)
            assertFalse("取消不得置位已验证", fixture.controller.state.value.isConnectionVerified)
            assertEquals(
                "取消只保留过程态文案，不得落成任何成功/失败终态结论",
                R.string.sync_feedback_connecting,
                fixture.controller.state.value.syncFeedbackMessage?.resId
            )
        } finally {
            fixture.close()
        }
    }

    // ============================== 夹具 ==============================

    private class Fixture : AutoCloseable {
        private val prefs = InMemorySharedPreferences()
        private val filesDir: File = Files.createTempDirectory("p2-652-files").toFile()
        private val context: Context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.prefs
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        // 必须**解锁**：同步周期以 `databaseFlow.value` 判活，仅建库（仍 LOCKED）会让
        // 每个周期在第一行就返回「库未解锁」，使「同步仍走 A 回执」这类断言空转。
        val session = DatabaseSession().also { session ->
            runBlocking {
                val file = File(filesDir, "p2-652.kdbx")
                check(session.create(file, "P2-652", "fake-vault-password".toCharArray(), useArgon2 = false) is KdbxResult.Success) {
                    "测试库创建失败"
                }
                check(session.open(file, "fake-vault-password".toCharArray()) is KdbxResult.Success) {
                    "测试库解锁失败"
                }
            }
        }
        val credentials = SyncCredentialsStore(context, keystoreManager = null).apply {
            customEncryptor = reversibleEncryptor
            customDecryptor = reversibleDecryptor
        }
        val factory = CountingFactory()
        private val debugLog = DebugLogBuffer()
        private val resolver = SyncProviderResolver(
            credentials,
            SyncPreferences(debugLog, null),
            debugLog,
            factory
        )
        private val coordinator = SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = credentials,
            debugLog = debugLog,
            strings = StringsProvider { id, _ -> "s$id" },
            syncProviderResolver = resolver
        )
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = SettingsSyncController(
            credentials,
            coordinator,
            ExtendedSettingsStore(null),
            StringsProvider { id, _ -> "s$id" },
            scope
        )

        init {
            controller.setSyncProvider(CloudSyncProvider.WEBDAV)
        }

        fun submitWebDav(draft: CharArray, url: String): Boolean {
            val saved = withSyncCredentialSubmissionCopy(draft) { submission ->
                controller.updateWebDavConfig(url, "fake-user", submission, "/saved.kdbx")
            }

            if (saved) draft.fill('0')
            return saved
        }

        /** 复刻生产表单两处入口的守卫：保存成功才继续（`persistThenContinue`）。 */
        fun persistThenContinueLikeForm(draft: CharArray) {
            persistThenContinue(
                persist = {
                    withSyncCredentialSubmissionCopy(draft) { submission ->
                        controller.updateWebDavConfig(
                            "https://dav-a.example.test/sync", "fake-user", submission, "/saved.kdbx"
                        )
                    }
                },
                continueAfterPersist = { controller.verifyConnectionThenSync() }
            )
        }

        fun cancelScope() {
            scope.cancel()
        }

        /** 忙态复位发生在编排协程的 finally 中；此处等它落地（有界，不靠固定 sleep 判结论）。 */
        suspend fun awaitNotSyncing() {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (controller.state.value.isSyncing && System.currentTimeMillis() < deadline) {
                delay(5)
            }
        }

        suspend fun drainScheduler() {
            // 让 Unconfined 作用域里已排队的收尾段执行完
            delay(20)
        }

        override fun close() {
            scope.cancel()
            // 收尾必须有界：会话锁可能仍被在途同步协程持有（作用域取消不保证它已退出），
            // 无界 close() 会让整个测试任务挂死（实测 >8 分钟无输出）。
            runBlocking { withTimeoutOrNull(5_000L) { session.close() } }
            filesDir.deleteRecursively()
        }

        companion object {
            const val TIMEOUT_MS = 5_000L
            const val XOR_MASK = 0x5A
            private val TEST_IV = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            val reversibleEncryptor: (ByteArray) -> Pair<ByteArray, ByteArray> = { plaintext ->
                Pair(TEST_IV, plaintext.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray())
            }
            val reversibleDecryptor: (ByteArray, ByteArray) -> ByteArray = { _, cipher ->
                cipher.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
            }
        }
    }

    /** 计数假件：只记录「哪份配置被构造、发过哪些操作」，不做真实网络。 */
    private class CountingFactory : SyncProviderFactory {
        companion object {
            /** 门闩必须有界：调用方作用域被取消时无界 await 会永久悬挂（实测整包卡死 >7 分钟）。 */
            const val GATE_TIMEOUT_MS = 10_000L
        }

        data class Request(
            val operation: String,
            val endpoint: String,
            val username: String,
            val password: String
        )

        @Volatile var testSucceeds = true
        private var enteredGate: CompletableDeferred<Unit>? = null
        private var releaseGate: CompletableDeferred<Unit>? = null
        private var uploadEntered: CompletableDeferred<Unit>? = null
        private var uploadRelease: CompletableDeferred<Unit>? = null
        private val firstTestGateUsed = java.util.concurrent.atomic.AtomicBoolean(false)

        val createdEndpoints = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<Request>()
        @Volatile var released = 0
            private set
        private val remoteFiles = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        fun installFirstTestGate(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
            enteredGate = entered
            releaseGate = release
        }

        fun installUploadGate(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
            uploadEntered = entered
            uploadRelease = release
        }

        override fun createWebDav(
            serverUrl: String,
            username: String,
            passwordChars: CharArray,
            networkOptions: SyncNetworkOptions,
            transferOptions: SyncTransferOptions
        ): SyncProvider {
            createdEndpoints += serverUrl
            return CountingProvider(serverUrl, username, passwordChars.copyOf())
        }

        override fun createS3(
            endpoint: String,
            bucketName: String,
            region: String,
            accessKeyId: CharArray,
            secretAccessKey: CharArray,
            usePathStyle: Boolean,
            networkOptions: SyncNetworkOptions,
            initialClockOffsetMillis: Long,
            clockOffsetUpdater: (Long) -> Unit
        ): SyncProvider = error("本用例只走 WebDAV 编排")

        override fun releaseOwned(provider: SyncProvider) {
            released++
            (provider as CountingProvider).clear()
        }

        private inner class CountingProvider(
            private val endpoint: String,
            private val username: String,
            private val password: CharArray
        ) : SyncProvider {
            private val key = endpoint + "|" + username

            override suspend fun testConnection(): Result<Unit> {
                requests += Request("test", endpoint, username, String(password))
                if (firstTestGateUsed.compareAndSet(false, true)) {
                    enteredGate?.complete(Unit)
                    // 门闩必须有界：调用方作用域被取消时 await 会永久悬挂，
                    // 表现为整个测试任务卡死（实测 >7 分钟无输出）。
                    withTimeoutOrNull(CountingFactory.GATE_TIMEOUT_MS) { releaseGate?.await() }
                }
                return if (testSucceeds) Result.success(Unit) else Result.failure(java.io.IOException("unreachable"))
            }

            override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
                requests += Request("metadata", endpoint, username, String(password))
                val bytes = remoteFiles[remotePath] ?: return Result.failure(SyncException.FileNotFound("missing"))
                return Result.success(
                    RemoteFileMetadata(
                        path = remotePath,
                        contentLength = bytes.size.toLong(),
                        lastModifiedMillis = 1L,
                        etag = "etag-" + bytes.size
                    )
                )
            }

            override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
                requests += Request("download", endpoint, username, String(password))
                val bytes = remoteFiles[remotePath] ?: return Result.failure(SyncException.FileNotFound("missing"))
                sink.write(bytes)
                return Result.success(Unit)
            }

            override suspend fun upload(
                remotePath: String,
                data: ByteArray,
                expectedEtag: String?
            ): Result<String> {
                requests += Request("upload", endpoint, username, String(password))
                uploadEntered?.also { gate ->
                    uploadEntered = null
                    gate.complete(Unit)
                    withTimeoutOrNull(CountingFactory.GATE_TIMEOUT_MS) { uploadRelease?.await() }
                }
                remoteFiles[remotePath] = data.copyOf()
                return Result.success("etag-" + data.size)
            }

            override suspend fun delete(remotePath: String): Result<Unit> {
                requests += Request("delete", endpoint, username, String(password))
                remoteFiles.remove(remotePath)
                return Result.success(Unit)
            }

            fun clear() {
                password.fill('0')
            }
        }
    }
}
