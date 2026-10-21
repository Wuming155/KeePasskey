package com.keepasskey.app.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.s3.S3SyncProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class SyncGroupConflictDeviceFixture(private val context: Context) {
    private val arguments = InstrumentationRegistry.getArguments()
    private val endpoint = requireNotNull(arguments.getString("s3Endpoint")) {
        "P2-660 必须提供真实 HTTPS S3 接收方 s3Endpoint，不允许跳过设备验收"
    }.also { require(it.startsWith("https://")) { "P2-660 接收方必须使用 HTTPS" } }
    private val runId = System.nanoTime().toString()
    val remotePath = "p2-660/$runId.kdbx"
    private val password = "Synthetic#P2-660-Groups".toCharArray()
    private val directory = File(context.filesDir, "p2-660-$runId").apply { mkdirs() }
    private val cacheDirectory = File(context.cacheDir, "p2-660-$runId").apply { mkdirs() }
    val observer = provider()
    private val peers = mutableListOf<Peer>()

    internal data class Peer(
        val file: File,
        val session: DatabaseSession,
        val coordinator: SyncCoordinator,
        val provider: S3SyncProvider
    )

    fun peer(name: String): Peer {
        val session = DatabaseSession()
        val provider = provider()
        val log = DebugLogBuffer()
        val extended = ExtendedSettingsStore(context).apply {
            publish(ExtendedSettings(conflictResolution = ConflictResolution.PROMPT_USER))
        }
        val preferences = SyncPreferences(log, extended)
        val credentials = SyncCredentialsStore(context, null, log)
        val resolver = SyncProviderResolver(credentials, preferences, log)
        val state = SyncSessionState().apply {
            testRemotePath = remotePath
            testSyncProvider = provider
        }
        val strings = StringsProvider { id, args -> context.getString(id, *args) }
        val codec = SyncDatabaseCodec(session, log)
        val conflicts = SyncConflictController(session, codec, strings, state, log)
        val changes = SyncContentChangeDetector(codec, state)
        val cycle = SyncCycleRunner(
            context = context,
            databaseSession = session,
            session = state,
            providerResolver = resolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = strings,
            rollbackStateDir = File(directory, "$name-rollback")
        ).apply { syncCacheFactory = { SyncCache(File(cacheDirectory, name)) } }
        val coordinator = SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = credentials,
            debugLog = log,
            strings = strings,
            extendedSettingsStore = extended,
            syncSession = state,
            syncPreferences = preferences,
            syncProviderResolver = resolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        )
        return Peer(File(directory, "$name.kdbx"), session, coordinator, provider).also(peers::add)
    }

    suspend fun create(peer: Peer) = withPassword { credential ->
        assertTrue("A 合成库创建失败", peer.session.create(peer.file, "P2-660", credential, useArgon2 = false) is KdbxResult.Success)
    }

    suspend fun open(peer: Peer) = withPassword { credential ->
        assertTrue("正式 KDBX 打开失败", peer.session.open(peer.file, credential) is KdbxResult.Success)
    }

    suspend fun sync(peer: Peer): SyncOutcome = withTimeout(SYNC_TIMEOUT_MS) { peer.coordinator.syncNow() }

    suspend fun download(): ByteArray {
        val output = ByteArrayOutputStream()
        val result = withTimeout(SYNC_TIMEOUT_MS) { observer.download(remotePath, output) }
        assertTrue("真实接收方下载失败: ${result.exceptionOrNull()?.javaClass?.simpleName}", result.isSuccess)
        return output.toByteArray()
    }

    fun load(bytes: ByteArray): KdbxDatabase {
        val credential = password.clone()
        return try {
            KdbxFile.load(ByteArrayInputStream(bytes), credential)
        } finally {
            credential.fill('\u0000')
        }
    }

    fun loadFile(peer: Peer): KdbxDatabase {
        val bytes = peer.file.readBytes()
        return try { load(bytes) } finally { bytes.fill(0) }
    }

    suspend fun reconnect(peer: Peer): Peer {
        peer.session.close()
        val replacement = peer(peer.file.nameWithoutExtension)
        open(replacement)
        return replacement
    }

    suspend fun close() {
        try {
            // 清理是**装置**步骤，不承载业务判据：对象可能从未创建、也可能已被前一轮删除，
            // 服务端的「不存在 / 协议错」都不应把已通过的业务断言判红。失败只记日志。
            val result = withTimeout(SYNC_TIMEOUT_MS) { observer.delete(remotePath) }
            if (result.isFailure) {
                android.util.Log.w(
                    "P2-660",
                    "隔离对象清理未成功（不影响业务断言）: ${result.exceptionOrNull()?.javaClass?.simpleName}"
                )
            }
        } finally {
            peers.forEach { peer ->
                peer.session.close()
                peer.provider.clearCredentials()
            }
            observer.clearCredentials()
            password.fill('\u0000')
            assertTrue("P2-660 私有测试目录清理失败", directory.deleteRecursively())
            assertTrue("P2-660 测试缓存清理失败", cacheDirectory.deleteRecursively())
        }
    }

    private fun provider() = S3SyncProvider(
        endpoint = endpoint,
        bucketName = arguments.getString("s3Bucket") ?: "kp-device-validation",
        region = arguments.getString("s3Region") ?: "us-east-1",
        accessKeyId = (arguments.getString("s3AccessKey") ?: "DEVICEACCESSKEY").toCharArray(),
        secretAccessKey = (arguments.getString("s3SecretKey") ?: "devicesecretkey").toCharArray(),
        usePathStyle = true
    )

    private suspend fun withPassword(block: suspend (CharArray) -> Unit) {
        val credential = password.clone()
        try { block(credential) } finally { credential.fill('\u0000') }
    }

    companion object {
        const val SYNC_TIMEOUT_MS = 60_000L
    }
}
