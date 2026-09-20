package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.NoopSyncIntegrityMac
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncCoordinatorTest` 同形） */
private val ERASURE_TEST_STRINGS = StringsProvider { _, _ -> "" }

private const val PWD = KdbxConstants.Fields.PASSWORD
private const val TITLE = KdbxConstants.Fields.TITLE

private fun uuidOf(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val UUID_ROOT = uuidOf("00000000000000000000000000000001")
private val UUID_SHARED = uuidOf("00000000000000000000000000000002")
private val UUID_COMMON = uuidOf("00000000000000000000000000000003")
private val UUID_REMOTE_ONLY = uuidOf("00000000000000000000000000000004")
private val UUID_LOCAL_ONLY = uuidOf("00000000000000000000000000000005")

/** 读出失败即视为已擦除（`ProtectedString.readString()` 在清零后抛 `IllegalStateException`） */
private fun ProtectedString.isCleared(): Boolean =
    try {
        readString()
        false
    } catch (_: IllegalStateException) {
        true
    }

/**
 * 逐字段快照（组 / 条目 / 受保护字段明文 / 附件字节 / 历史字段）：
 * **任一实例被误擦都会在物化明文时抛 `IllegalStateException`**，故它是「活动库未被波及」的判据。
 */
private fun KdbxDatabase.snapshotTree(): String = buildString {
    fun appendGroup(group: KdbxGroup) {
        append("G ").append(group.id.toHexString()).append(' ').append(group.name).append('\n')
        group.entries.sortedBy { it.id.toHexString() }.forEach { entry ->
            append("E ").append(entry.id.toHexString()).append('\n')
            entry.fields.toSortedMap().forEach { (key, value) ->
                append("f ").append(key).append('=').append(value.readString()).append('\n')
            }
            entry.attachments.forEach { attachment ->
                append("a ").append(attachment.name).append('=')
                    .append(attachment.inlineBytes().joinToString(",")).append('\n')
            }
            entry.history.forEach { snapshot ->
                snapshot.fields.toSortedMap().forEach { (key, value) ->
                    append("h ").append(key).append('=').append(value.readString()).append('\n')
                }
            }
        }
        group.subgroups.forEach { appendGroup(it) }
    }
    appendGroup(rootGroup)
}

/** 带 KDF secret `K` 的头部（用于头部护栏判据） */
private fun headerWithSecret(secret: ByteArray = ByteArray(32) { 0x5A }) = KdbxHeader(
    kdfParameters = KdfParameters.Argon2(
        type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
        salt = ByteArray(32),
        secretKey = secret
    )
)

private fun secretOf(header: KdbxHeader): ByteArray? =
    (header.kdfParameters as KdfParameters.Argon2).secretKey

/**
 * `ISSUE-P3-235` G2（`RC-02` 收口）的契约用例：**丢弃解析树前的身份集合判定擦除**。
 *
 * 锁定两件事，缺一不可（`docs/architecture/敏感缓冲所有权契约.md` §3 R1 ~ R5 / §4 / §7.1）：
 *
 * 1. **不误擦存活侧**（P0 护栏）：`KdbxDatabase` 是 data class，`localDb.copy(rootGroup = mergedRoot, …)`
 *    与 `KdbxMerger` 对「单侧独有对象」的**原实例复用**会让同一 `ProtectedString` / `KdbxAttachment`
 *    被「待丢弃树」与「活动会话树」同时可达。裸 `clearSensitiveData()` 会静默清空活动库
 *    （`SECURITY_RECHECK_2026-09.md` §9.6 #3 / #19 同型）——这正是 §52 此前对 `pending*`
 *    **只丢引用不擦除**的原因；本用例必须让「无判定的裸擦实现」变红。
 * 2. **确实擦到了**（非空跑）：待丢弃树中不被存活侧引用的实例（含 history、附件、头部 KDF secret）
 *    必须清零——否则本改动退化为「只改注释」。
 *
 * 链路层的观测点：`SyncConflictController.conflictFlow` 暴露的 [com.keepasskey.sync.merge.ConflictedEntryPair]
 * 持有**远端解析树内的实例**（`remoteEntry`），故擦除可由外部断言；
 * `pendingRemoteDb` 本身是私有的，不另开测试后门。
 *
 * **边界如实声明**：自动合并成功路径的擦除**效果**不可外部观测（解析树实例不外泄，
 * 且 `pending*` 通道不参与该路径），故该路径的用例只锁定「不误擦已采用实例」一侧。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncPendingTreeErasureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val masterPassword = "PendingTreeWipe#2026".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        masterPassword.fill('0')
        // Main 采用「只装不卸」口径（ISSUE-P3-189 路线①）：收尾统一走 MainDispatcherGuard
        MainDispatcherGuard.tearDown()
    }

    // ------------------------------------------------------------------ 原语层

    @Test
    fun `丢弃树按身份集合判定擦除：共享实例存活、独有实例清零（含附件与历史）`() {
        val sharedPassword = ProtectedString("Shared#1", true)
        val sharedAttachment = KdbxAttachment(name = "shared.bin", data = ByteArray(4) { 0x11 })

        val live = KdbxDatabase(
            header = headerWithSecret(),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        id = UUID_COMMON,
                        fields = mapOf(PWD to sharedPassword),
                        attachments = listOf(sharedAttachment)
                    )
                )
            )
        )

        val uniquePassword = ProtectedString("RemoteOnly#2", true)
        val uniqueTitle = ProtectedString("Remote Only", false)
        val uniqueAttachment = KdbxAttachment(name = "unique.bin", data = ByteArray(4) { 0x22 })
        val historyPassword = ProtectedString("History#3", true)
        val discarded = KdbxDatabase(
            header = headerWithSecret(),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    // 被合并器原实例复用进活动树的条目：与存活树共享同一批实例
                    KdbxEntry(
                        id = UUID_COMMON,
                        fields = mapOf(PWD to sharedPassword),
                        attachments = listOf(sharedAttachment)
                    ),
                    KdbxEntry(
                        id = UUID_REMOTE_ONLY,
                        fields = mapOf(TITLE to uniqueTitle, PWD to uniquePassword),
                        attachments = listOf(uniqueAttachment),
                        history = listOf(KdbxEntry(fields = mapOf(PWD to historyPassword)))
                    )
                )
            )
        )

        eraseDiscardedDatabase(discarded, live)

        assertEquals("存活树共享的口令实例不得被擦", "Shared#1", sharedPassword.readString())
        assertArrayEquals(
            "存活树共享的附件实例不得被擦",
            ByteArray(4) { 0x11 },
            sharedAttachment.inlineBytes()
        )
        assertTrue("待丢弃树的独有口令必须清零", uniquePassword.isCleared())
        assertTrue("待丢弃树的独有标题必须清零", uniqueTitle.isCleared())
        assertTrue("待丢弃树的独有附件必须清零", uniqueAttachment.inlineBytes().all { it == 0.toByte() })
        assertTrue("待丢弃树 history 中的独有密文必须清零", historyPassword.isCleared())
    }

    @Test
    fun `存活侧为空时丢弃树全量擦除（会话终止路径）`() {
        val password = ProtectedString("Terminated#4", true)
        val attachment = KdbxAttachment(name = "a.bin", data = ByteArray(4) { 0x33 })
        val discarded = KdbxDatabase(
            header = headerWithSecret(),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(fields = mapOf(PWD to password), attachments = listOf(attachment))
                )
            )
        )

        eraseDiscardedDatabase(discarded, null)

        assertTrue("无存活别名 ⇒ 全量擦除", password.isCleared())
        assertTrue("无存活别名 ⇒ 附件一并擦除", attachment.inlineBytes().all { it == 0.toByte() })
        assertNull("无存活别名 ⇒ 头部 KDF secret 一并擦除", secretOf(discarded.header))
    }

    @Test
    fun `丢弃树与存活树为同一实例时不做任何动作`() {
        val password = ProtectedString("Self#5", true)
        val db = KdbxDatabase(
            header = headerWithSecret(),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(KdbxEntry(fields = mapOf(PWD to password))))
        )

        eraseDiscardedDatabase(db, db)

        assertEquals("同一实例（属调用方 / 会话）绝不可被擦", "Self#5", password.readString())
        assertNotNull("同一实例的头部不得被擦", secretOf(db.header))
    }

    @Test
    fun `头部 KDF secret 仅在头部实例不被存活树共享时擦除`() {
        // (a) `localDb.copy(...)` 的形态：合并树与来源树共享同一 header 实例 ⇒ 不得擦
        val sharedHeader = headerWithSecret()
        val live = KdbxDatabase(header = sharedHeader, rootGroup = KdbxGroup(name = "Root"))
        val merged = live.copy(rootGroup = KdbxGroup(name = "Root"))

        eraseDiscardedDatabase(merged, live)

        assertNotNull("共享头部实例的 KDF secret 不得被擦（否则活动库写出错密钥的库）", secretOf(sharedHeader))

        // (b) 独立头部（如远端 / base 的解析产物）⇒ 擦本侧、不动存活侧
        val ownHeader = headerWithSecret()
        val remote = KdbxDatabase(header = ownHeader, rootGroup = KdbxGroup(name = "Root"))

        eraseDiscardedDatabase(remote, live)

        assertNull("独立头部（无第二持有者）的 KDF secret 必须擦除", secretOf(ownHeader))
        assertNotNull("存活侧头部不得受影响", secretOf(live.header))
    }

    // ------------------------------------------------------------------ 链路层

    @Test
    fun `冲突待决期锁定会话：远端解析树随会话终止被全量擦除`() = runTest(testDispatcher) {
        val workDir = File(tempFolder.root, "lock-while-pending").apply { mkdirs() }
        val (session, fixture) = buildFixture(workDir, conflicting = true)
        val controller = controllerFor(session)
        val engine = engineFor(workDir, fixture.remoteBytes)

        val outcome = controller.handleConflictMerge(
            syncEngine = engine,
            syncCache = SyncCache(File(workDir, "cache").apply { mkdirs() }),
            remotePath = "/remote/pending.kdbx",
            localBytes = fixture.localBytes,
            remoteBytes = fixture.remoteBytes,
            baseSnapshotBytes = fixture.baseBytes,
            remoteEtag = "etag-1",
            strategy = SyncConflictStrategy.AUTO_MERGE,
            localDbOverride = session.databaseFlow.value
        )
        assertTrue("前提：本场景必须进入用户决策（否则本用例不构成证据）: $outcome", outcome is SyncOutcome.ConflictNeedsUser)

        val remotePassword = controller.conflictFlow.value.single().remoteEntry.fields.getValue(PWD)
        assertEquals("前提：待决远端树的字段此时仍可读", "Remote#3", remotePassword.readString())

        // 生产接线形态：SyncCoordinator 在 init 注册为会话锁观察者，其 onSessionLocked
        // 委托 SyncConflictController.clearPendingConflictSession()（活动树此时已被擦除并置空）
        val context = fakeContext(workDir)
        SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = SyncCredentialsStore(context, null),
            debugLog = DebugLogBuffer(),
            strings = ERASURE_TEST_STRINGS,
            syncConflicts = controller
        )

        session.lock()

        assertTrue("会话终止后待决远端树（不属于活动树）的字段必须被擦除", remotePassword.isCleared())
        assertTrue("冲突清单必须清空", controller.conflictFlow.value.isEmpty())
    }

    @Test
    fun `决策路径丢弃待决会话：活动库字段不被误擦`() = runTest(testDispatcher) {
        val workDir = File(tempFolder.root, "discard-pending").apply { mkdirs() }
        val (session, fixture) = buildFixture(workDir, conflicting = true)
        val controller = controllerFor(session)
        val engine = engineFor(workDir, fixture.remoteBytes)

        val outcome = controller.handleConflictMerge(
            syncEngine = engine,
            syncCache = SyncCache(File(workDir, "cache").apply { mkdirs() }),
            remotePath = "/remote/discard.kdbx",
            localBytes = fixture.localBytes,
            remoteBytes = fixture.remoteBytes,
            baseSnapshotBytes = fixture.baseBytes,
            remoteEtag = "etag-1",
            strategy = SyncConflictStrategy.AUTO_MERGE,
            localDbOverride = session.databaseFlow.value
        )
        assertTrue("前提：本场景必须进入用户决策: $outcome", outcome is SyncOutcome.ConflictNeedsUser)

        val before = session.databaseFlow.value!!.snapshotTree()
        assertTrue("前提：活动库此时含本地口令", before.contains("Local#2"))
        assertTrue("前提：活动库此时含本地独有条目（合并器原实例复用的判别锚点）", before.contains("LocalOnly#pwd"))

        // 决策路径：`pendingLocalDb` 与活动库为同一实例，`pendingMergedRoot` 按原实例复用其节点
        controller.clearPendingConflictSession()

        assertEquals(
            "P0 护栏：丢弃待决会话绝不得波及活动库（对共享实例裸擦的实现会在此变红）",
            before,
            session.databaseFlow.value!!.snapshotTree()
        )
        assertTrue("冲突清单必须清空", controller.conflictFlow.value.isEmpty())
    }

    @Test
    fun `自动合并成功后擦除来源树不得波及已采用的活动库`() = runTest(testDispatcher) {
        val workDir = File(tempFolder.root, "auto-merge").apply { mkdirs() }
        val (session, fixture) = buildFixture(workDir, conflicting = false)
        val controller = controllerFor(session)
        val engine = engineFor(workDir, fixture.remoteBytes)

        val outcome = controller.handleConflictMerge(
            syncEngine = engine,
            syncCache = SyncCache(File(workDir, "cache").apply { mkdirs() }),
            remotePath = "/remote/auto.kdbx",
            localBytes = fixture.localBytes,
            remoteBytes = fixture.remoteBytes,
            baseSnapshotBytes = fixture.baseBytes,
            remoteEtag = "etag-1",
            strategy = SyncConflictStrategy.AUTO_MERGE,
            localDbOverride = session.databaseFlow.value
        )
        assertTrue("前提：本场景必须走自动合并并上传: $outcome", outcome is SyncOutcome.MergedAndUploaded)

        // 远端独有条目按**原实例**被合并器复用进活动库：其口令实例与远端解析树共享，
        // 来源树擦除必须把它判为存活（裸擦实现会在此读出「已清零」）
        val adopted = session.databaseFlow.value ?: error("活动库不得丢失")
        val remoteOnly = adopted.rootGroup.findEntry(UUID_REMOTE_ONLY)
            ?: error("远端独有条目必须被采用进活动库")
        assertEquals("远端独有条目口令不得被误擦", "RemoteOnly#pwd", remoteOnly.fields.getValue(PWD).readString())
        assertEquals(
            "共有条目口令不得被误擦",
            "Base#1",
            adopted.rootGroup.findEntry(UUID_COMMON)!!.fields.getValue(PWD).readString()
        )
        assertEquals(
            "本地独有条目口令不得被误擦",
            "LocalOnly#pwd",
            adopted.rootGroup.findEntry(UUID_LOCAL_ONLY)!!.fields.getValue(PWD).readString()
        )
    }

    // ------------------------------------------------------------------ 夹具

    private class Fixture(
        val localBytes: ByteArray,
        val remoteBytes: ByteArray,
        val baseBytes: ByteArray
    )

    private class MemorySyncProvider : SyncProvider {
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
            val etag = "etag-${data.size}"
            remoteStorage[remotePath] = Pair(data, etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteStorage.remove(remotePath)
            return Result.success(Unit)
        }
    }

    private fun fakeContext(workDir: File): Context = object : android.content.ContextWrapper(null) {
        override fun getCacheDir(): File = File(workDir, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(workDir, "files").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    private fun controllerFor(session: DatabaseSession) = SyncConflictController(
        databaseSession = session,
        codec = SyncDatabaseCodec(session, DebugLogBuffer()),
        strings = ERASURE_TEST_STRINGS,
        session = SyncSessionState(),
        debugLog = DebugLogBuffer()
    )

    private fun engineFor(workDir: File, remoteBytes: ByteArray): SyncEngine {
        val provider = MemorySyncProvider()
        provider.remoteStorage["/remote/pending.kdbx"] = Pair(remoteBytes, "etag-1")
        provider.remoteStorage["/remote/discard.kdbx"] = Pair(remoteBytes, "etag-1")
        provider.remoteStorage["/remote/auto.kdbx"] = Pair(remoteBytes, "etag-1")
        return SyncEngine(
            provider,
            SyncCache(File(workDir, "engine-cache").apply { mkdirs() }),
            SyncRollbackGuard(File(workDir, "rollback").apply { mkdirs() }, NoopSyncIntegrityMac)
        )
    }

    private fun newEntry(title: String, id: KdbxUuid) = KdbxEntry(
        id = id,
        fields = mapOf(
            TITLE to ProtectedString(title, false),
            PWD to ProtectedString("$title#pwd", true)
        )
    )

    /**
     * 造场景：base 含共有条目 `Common`（口令 `Base#1`）；本地新增 `Shared` 分组，
     * 远端新增 `RemoteOnly` 条目（口令 `RemoteOnly#pwd`，自动合并段必须被**原实例复用**）；
     * [conflicting] 为 true 时双方再各自改写 `Common` 的口令（条目级字段冲突）。
     */
    private suspend fun buildFixture(workDir: File, conflicting: Boolean): Pair<DatabaseSession, Fixture> {
        val session = DatabaseSession()
        val created = session.create(
            file = File(workDir, "pending_tree.kdbx"),
            name = "PendingTree",
            passwordChars = masterPassword,
            useArgon2 = false
        )
        assertTrue("建库必须成功: $created", created is KdbxResult.Success)

        val codec = SyncDatabaseCodec(session, DebugLogBuffer())
        val commonEntry = KdbxEntry(
            id = UUID_COMMON,
            fields = mapOf(
                TITLE to ProtectedString("Common", false),
                PWD to ProtectedString("Base#1", true)
            )
        )
        val baseRoot = KdbxGroup(
            id = UUID_ROOT,
            name = "Root",
            entries = listOf(commonEntry)
        )
        val baseDb = session.databaseFlow.value!!.copy(rootGroup = baseRoot)
        session.updateDatabaseMeta { baseDb }
        val baseBytes = codec.serializeLocalDatabase(baseDb) ?: error("base 序列化失败")

        val localCommon = if (conflicting) {
            commonEntry.withField(PWD, ProtectedString("Local#2", true))
        } else {
            commonEntry
        }
        val localRoot = baseRoot.copy(
            // 本地独有条目：合并器对「单侧独有对象」**原实例复用**，故其敏感实例必然同时
            // 可达于「活动库」与「`pendingMergedRoot`」——它是决策路径 P0 护栏的判别锚点
            entries = listOf(localCommon, newEntry("LocalOnly", UUID_LOCAL_ONLY)),
            subgroups = listOf(KdbxGroup(id = UUID_SHARED, name = "Shared"))
        )
        val localDb = baseDb.copy(rootGroup = localRoot)
        session.updateDatabaseMeta { localDb }
        session.save()
        val localBytes = codec.serializeLocalDatabase(localDb) ?: error("local 序列化失败")

        val remoteCommon = if (conflicting) {
            commonEntry.withField(PWD, ProtectedString("Remote#3", true))
        } else {
            commonEntry
        }
        val remoteRoot = baseRoot.copy(
            entries = listOf(remoteCommon, newEntry("RemoteOnly", UUID_REMOTE_ONLY))
        )
        val remoteBytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, baseDb.copy(rootGroup = remoteRoot), masterPassword) }
            .toByteArray()

        return session to Fixture(localBytes, remoteBytes, baseBytes)
    }
}
