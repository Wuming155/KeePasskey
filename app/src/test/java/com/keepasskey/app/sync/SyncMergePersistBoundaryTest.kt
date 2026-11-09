package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.MainDispatcherGuard
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
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与同包既有用例同形）。 */
private val PERSIST_TEST_STRINGS = StringsProvider { _, _ -> "" }

private const val PWD = KdbxConstants.Fields.PASSWORD
private const val TITLE = KdbxConstants.Fields.TITLE

private fun uuidOf(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val UUID_ROOT = uuidOf("00000000000000000000000000000011")
private val UUID_COMMON = uuidOf("00000000000000000000000000000012")
private val UUID_LOCAL_ONLY = uuidOf("00000000000000000000000000000013")
private val UUID_REMOTE_ONLY = uuidOf("00000000000000000000000000000014")

/**
 * `ENG-20261028-09` / `ENG-20261028-19` / `ENG-20261028-31`：**同步合并的本地提交边界**。
 *
 * 锁定的缺陷（整改前）：
 * - 自动合并与用户裁决都是「先采纳合并树（并擦除部分旧实例）→ 再 `save()`」：写盘失败后内存
 *   已是合并版本而文件仍是旧版本；用户裁决路径更在检查保存结果**之前**清空待决会话，失败后
 *   既无法重试、也不能把已擦除的旧实例挂回去；
 * - 合并产物先为上传序列化一次、本地落盘再序列化一次（两次整库 XML / 压缩 / KDF）。
 *
 * 判据：写盘失败时活动树实例不变、正式文件不变、进程内同步基线作废；用户裁决路径的待决会话
 * 保留并可原样重试；上传字节与本地写出字节**逐字节相同**（KDBX4 每次保存重生成 master seed /
 * IV，二次序列化不可能得到同一字节 ⇒ 这是「只序列化一次」的行为证据）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncMergePersistBoundaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val masterPassword = "{{SECRET_p3x9vk2a}}".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        masterPassword.fill('0')
        MainDispatcherGuard.tearDown()
    }

    /** 记录写入并按需注入失败 / 取消的写盘通道（`failOnWrite` 为 1 起算的序号）。 */
    private class RecordingWriter(private val target: File) {
        var failOnWrite: Int? = null
        var cancelOnWrite: Int? = null
        var writes = 0
        val written = mutableListOf<ByteArray>()

        suspend fun write(bytes: ByteArray) {
            writes++
            if (failOnWrite == writes) throw IOException("disk full")
            if (cancelOnWrite == writes) throw CancellationException("cancelled during write")
            written += bytes.copyOf()
            target.writeBytes(bytes)
        }
    }

    private class RecordingProvider : SyncProvider {
        val remoteStorage = mutableMapOf<String, Pair<ByteArray, String>>()

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val item = remoteStorage[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("File not found: $remotePath"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = 0L,
                    etag = item.second
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            remoteStorage[remotePath]?.let {
                sink.write(it.first)
                Result.success(Unit)
            } ?: Result.failure(SyncException.FileNotFound("File not found: $remotePath"))

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            val existing = remoteStorage[remotePath]
            if (expectedEtag != null && existing != null && existing.second != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing.second,
                        localExpectedEtag = expectedEtag,
                        message = "Precondition Failed"
                    )
                )
            }
            val etag = "etag-${data.size}-${data.fold(0) { acc, b -> (acc + b) and 0x7FFF }}"
            remoteStorage[remotePath] = Pair(data.copyOf(), etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteStorage.remove(remotePath)
            return Result.success(Unit)
        }

        fun remoteBytes(remotePath: String): ByteArray = remoteStorage.getValue(remotePath).first
    }

    private class Fixture(
        val session: DatabaseSession,
        val controller: SyncConflictController,
        val syncState: SyncSessionState,
        val engine: SyncEngine,
        val cache: SyncCache,
        val provider: RecordingProvider,
        val writer: RecordingWriter,
        val vaultFile: File,
        val remotePath: String,
        val baseBytes: ByteArray,
        val localBytes: ByteArray,
        val remoteBytes: ByteArray,
        val commonEntryId: KdbxUuid
    )

    private fun newEntry(title: String, id: KdbxUuid, password: String) = KdbxEntry(
        id = id,
        fields = mapOf(
            TITLE to ProtectedString(title, false),
            PWD to ProtectedString(password, true)
        )
    )

    private suspend fun buildFixture(key: String, conflicting: Boolean): Fixture {
        val workDir = File(tempFolder.root, key).apply { mkdirs() }
        val vaultFile = File(workDir, "$key.kdbx")

        // 先用常规通道建库并写出 base / local 两个版本，再以**可注入失败的通道**重开会话：
        // 生产上写盘失败来自磁盘满 / SAF 授权失效，注入点选在真实 writer 上。
        val bootstrap = DatabaseSession()
        assertTrue(
            "前置：建库必须成功",
            bootstrap.create(vaultFile, key, masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        val codec = SyncDatabaseCodec(bootstrap, DebugLogBuffer())
        val common = newEntry("Common", UUID_COMMON, "Base#1")
        val baseRoot = KdbxGroup(id = UUID_ROOT, name = "Root", entries = listOf(common))
        val baseDb = bootstrap.databaseFlow.value!!.copy(rootGroup = baseRoot)
        bootstrap.updateDatabaseMeta { baseDb }
        bootstrap.save()
        val baseBytes = codec.serializeLocalDatabase(baseDb) ?: error("base 序列化失败")

        val localRoot = baseRoot.copy(
            entries = listOf(
                if (conflicting) newEntry("Common", UUID_COMMON, "Local#2") else common,
                newEntry("LocalOnly", UUID_LOCAL_ONLY, "LocalOnly#pwd")
            )
        )
        val localDb = baseDb.copy(rootGroup = localRoot)
        bootstrap.updateDatabaseMeta { localDb }
        assertTrue("前置：本地版本必须落盘", bootstrap.save().isSuccess)
        val localBytes = codec.serializeLocalDatabase(localDb) ?: error("local 序列化失败")
        bootstrap.close()

        val writer = RecordingWriter(vaultFile)
        val session = DatabaseSession()
        assertTrue(
            "前置：可注入会话必须打开成功",
            session.openStream(
                pathIdentifier = vaultFile.absolutePath,
                inputStreamProvider = { ByteArrayInputStream(vaultFile.readBytes()) },
                saveWriter = writer::write,
                passwordChars = masterPassword,
                associatedFile = vaultFile
            ).isSuccess
        )

        val remoteRoot = KdbxGroup(
            id = UUID_ROOT,
            name = "Root",
            entries = listOf(
                if (conflicting) newEntry("Common", UUID_COMMON, "Remote#3") else newEntry("Common", UUID_COMMON, "Base#1"),
                newEntry("RemoteOnly", UUID_REMOTE_ONLY, "RemoteOnly#pwd")
            )
        )
        val remoteBytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, session.databaseFlow.value!!.copy(rootGroup = remoteRoot), masterPassword) }
            .toByteArray()

        val remotePath = "/remote/$key.kdbx"
        val provider = RecordingProvider()
        provider.remoteStorage[remotePath] = Pair(remoteBytes, "etag-1")
        val cache = SyncCache(File(workDir, "cache").apply { mkdirs() })
        val engine = SyncEngine(
            provider,
            cache,
            SyncRollbackGuard(File(workDir, "rollback").apply { mkdirs() })
        )
        val syncState = SyncSessionState()
        val controller = SyncConflictController(
            databaseSession = session,
            codec = SyncDatabaseCodec(session, DebugLogBuffer()),
            strings = PERSIST_TEST_STRINGS,
            session = syncState,
            debugLog = DebugLogBuffer()
        )
        return Fixture(
            session, controller, syncState, engine, cache, provider, writer, vaultFile,
            remotePath, baseBytes, localBytes, remoteBytes, UUID_COMMON
        )
    }

    private fun titlesOf(db: KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries().mapNotNull { it.fields[TITLE]?.readString() }.toSet()

    private fun remoteTitles(fx: Fixture): Set<String> {
        val parsed = KdbxFile.load(fx.provider.remoteBytes(fx.remotePath).inputStream(), masterPassword)
        return parsed.rootGroup.allEntries().mapNotNull { it.fields[TITLE]?.readString() }.toSet()
    }

    @Test
    fun `自动合并写盘失败：内存与文件保持采纳前状态、上传已完成、基线作废`() =
        runTest(testDispatcher) {
            val fx = buildFixture("auto-fail", conflicting = false)
            try {
                val before = fx.session.databaseFlow.value!!
                val fileBefore = fx.vaultFile.readBytes()
                // 直接驱动合并入口（无周期前置保存）：唯一一次写盘即合并提交，令其失败
                fx.writer.failOnWrite = 1

                val outcome = fx.controller.handleConflictMerge(
                    syncEngine = fx.engine,
                    syncCache = fx.cache,
                    remotePath = fx.remotePath,
                    localBytes = fx.localBytes,
                    remoteBytes = fx.remoteBytes,
                    baseSnapshotBytes = fx.baseBytes,
                    remoteEtag = "etag-1",
                    strategy = SyncConflictStrategy.AUTO_MERGE,
                    localDbOverride = before,
                    expectedSessionSnapshot = before
                )

                assertTrue("本地写盘失败必须如实报错: $outcome", outcome is SyncOutcome.Error)
                assertFalse("不得谎报已合并上传", outcome.isInSyncWithRemote())
                assertSame("写盘失败不得发布合并树", before, fx.session.databaseFlow.value)
                assertFalse(
                    "远端独有条目不得出现在未落盘的活动树中: ${titlesOf(fx.session.databaseFlow.value)}",
                    "RemoteOnly" in titlesOf(fx.session.databaseFlow.value)
                )
                assertTrue(
                    "本地修改必须仍在活动树中: ${titlesOf(fx.session.databaseFlow.value)}",
                    "LocalOnly" in titlesOf(fx.session.databaseFlow.value)
                )
                assertArrayEquals("写盘失败不得改动正式文件", fileBefore, fx.vaultFile.readBytes())
                assertTrue(
                    "云端已接收合并版本（上传先于本地落盘，失败只影响本地）: ${remoteTitles(fx)}",
                    "RemoteOnly" in remoteTitles(fx) && "LocalOnly" in remoteTitles(fx)
                )
                assertNull(
                    "本地落盘失败必须作废进程内同步基线（否则下轮会把未落盘的合并版本当成已同步）",
                    fx.syncState.lastSyncedDb
                )
            } finally {
                fx.session.close()
            }
        }

    @Test
    fun `用户裁决写盘失败：待决会话保留并可原样重试成功`() = runTest(testDispatcher) {
        val fx = buildFixture("resolve-fail", conflicting = true)
        try {
            val before = fx.session.databaseFlow.value!!
            val pendingOutcome = fx.controller.handleConflictMerge(
                syncEngine = fx.engine,
                syncCache = fx.cache,
                remotePath = fx.remotePath,
                localBytes = fx.localBytes,
                remoteBytes = fx.remoteBytes,
                baseSnapshotBytes = fx.baseBytes,
                remoteEtag = "etag-1",
                strategy = SyncConflictStrategy.AUTO_MERGE,
                localDbOverride = before,
                expectedSessionSnapshot = before
            )
            assertTrue(
                "前提：双方改写同一字段必须进入用户决策: $pendingOutcome",
                pendingOutcome is SyncOutcome.ConflictNeedsUser
            )
            val fileBefore = fx.vaultFile.readBytes()
            fx.writer.failOnWrite = 1

            val failed = fx.controller.resolveConflicts(
                mapOf(fx.commonEntryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
            )

            assertTrue("本地写盘失败必须如实报错: $failed", failed is SyncOutcome.Error)
            assertSame("写盘失败不得发布裁决结果", before, fx.session.databaseFlow.value)
            assertArrayEquals("写盘失败不得改动正式文件", fileBefore, fx.vaultFile.readBytes())
            assertFalse(
                "待决冲突清单必须保留（用户仍可重试）: ${fx.controller.conflictFlow.value}",
                fx.controller.conflictFlow.value.isEmpty()
            )
            assertSame(
                "待决会话的快照判据必须仍是活动树（重试入口据此放行）",
                before,
                fx.controller.pendingSessionSnapshot
            )
            assertNull("本地落盘失败必须作废进程内同步基线", fx.syncState.lastSyncedDb)

            // 重试：写盘恢复后同一裁决必须收敛，且不依赖任何已擦除实例
            fx.writer.failOnWrite = null
            val retried = fx.controller.resolveConflicts(
                mapOf(fx.commonEntryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
            )
            assertTrue(
                "重试必须收敛（合并上传 + 本地落盘）: $retried",
                retried is SyncOutcome.MergedAndUploaded
            )
            assertTrue(
                "待决冲突清单必须在成功后清空",
                fx.controller.conflictFlow.value.isEmpty()
            )
            val adopted = fx.session.databaseFlow.value!!
            assertEquals(
                "裁决结果必须落盘并发布（取本地口令）",
                "Local#2",
                adopted.rootGroup.findEntry(fx.commonEntryId)!!.fields.getValue(PWD).readString()
            )
            assertTrue("远端独有条目不得丢失: ${titlesOf(adopted)}", "RemoteOnly" in titlesOf(adopted))
            assertArrayEquals(
                "正式文件必须等于本次写出的字节",
                fx.writer.written.last(),
                fx.vaultFile.readBytes()
            )
        } finally {
            fx.session.close()
        }
    }

    @Test
    fun `用户裁决写盘取消：待决会话保留且内存与文件不变`() = runTest(testDispatcher) {
        val fx = buildFixture("cancel", conflicting = true)
        try {
            val before = fx.session.databaseFlow.value!!
            val pendingOutcome = fx.controller.handleConflictMerge(
                syncEngine = fx.engine,
                syncCache = fx.cache,
                remotePath = fx.remotePath,
                localBytes = fx.localBytes,
                remoteBytes = fx.remoteBytes,
                baseSnapshotBytes = fx.baseBytes,
                remoteEtag = "etag-1",
                strategy = SyncConflictStrategy.AUTO_MERGE,
                localDbOverride = before,
                expectedSessionSnapshot = before
            )
            assertTrue(
                "前提：必须进入用户决策: $pendingOutcome",
                pendingOutcome is SyncOutcome.ConflictNeedsUser
            )
            val fileBefore = fx.vaultFile.readBytes()
            // 置一个非空基线：取消后必须作废（引擎已把合并内容写入同步缓存，
            // 保留旧基线会让下轮把未落盘的合并版本误判为已同步）
            fx.syncState.lastSyncedDb = before
            fx.writer.cancelOnWrite = 1

            // 在独立子协程中驱动：取消只应取消该子任务，测试协程随后仍可断言与继续
            val cancelledRun = launch {
                fx.controller.resolveConflicts(
                    mapOf(fx.commonEntryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
                )
            }
            cancelledRun.join()

            assertTrue("写盘点的取消必须传播到调用方", cancelledRun.isCancelled)
            assertSame("取消不得发布裁决结果", before, fx.session.databaseFlow.value)
            assertArrayEquals("取消不得改动正式文件", fileBefore, fx.vaultFile.readBytes())
            assertFalse(
                "待决冲突清单必须保留（用户仍可重试）: ${fx.controller.conflictFlow.value}",
                fx.controller.conflictFlow.value.isEmpty()
            )
            assertNull("取消必须作废进程内同步基线", fx.syncState.lastSyncedDb)

            // 写盘恢复后仍可重试收敛（证明取消未破坏待决材料）
            fx.writer.cancelOnWrite = null
            val retried = fx.controller.resolveConflicts(
                mapOf(fx.commonEntryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
            )
            assertTrue(
                "取消后重试必须收敛: $retried",
                retried is SyncOutcome.MergedAndUploaded
            )
            assertEquals(
                "裁决结果必须落盘并发布（取本地口令）",
                "Local#2",
                fx.session.databaseFlow.value!!.rootGroup
                    .findEntry(fx.commonEntryId)!!.fields.getValue(PWD).readString()
            )
        } finally {
            fx.session.close()
        }
    }

    @Test
    fun `自动合并成功：上传字节与本地写出字节逐字节相同（只序列化一次）`() =
        runTest(testDispatcher) {
            val fx = buildFixture("auto-ok", conflicting = false)
            try {
                val before = fx.session.databaseFlow.value!!
                val outcome = fx.controller.handleConflictMerge(
                    syncEngine = fx.engine,
                    syncCache = fx.cache,
                    remotePath = fx.remotePath,
                    localBytes = fx.localBytes,
                    remoteBytes = fx.remoteBytes,
                    baseSnapshotBytes = fx.baseBytes,
                    remoteEtag = "etag-1",
                    strategy = SyncConflictStrategy.AUTO_MERGE,
                    localDbOverride = before,
                    expectedSessionSnapshot = before
                )

                assertTrue("自动合并必须成功: $outcome", outcome is SyncOutcome.MergedAndUploaded)
                val uploaded = fx.provider.remoteBytes(fx.remotePath)
                val written = fx.writer.written.last()
                assertArrayEquals(
                    "上传字节与本地写出字节必须逐字节相同（KDBX4 每次保存重生成 master seed / IV，" +
                        "二次序列化不可能得到同一字节）",
                    written,
                    uploaded
                )
                val adopted = fx.session.databaseFlow.value!!
                assertTrue("本地独有条目必须保留: ${titlesOf(adopted)}", "LocalOnly" in titlesOf(adopted))
                assertTrue("远端独有条目必须保留: ${titlesOf(adopted)}", "RemoteOnly" in titlesOf(adopted))
                assertSame(
                    "已与远端收敛的版本必须被登记为检查点候选",
                    adopted,
                    fx.syncState.checkpointDatabase
                )
            } finally {
                fx.session.close()
            }
        }
}
