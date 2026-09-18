package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.NoopSyncIntegrityMac
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.SyncConflictStrategy
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与 `SyncCoordinatorTest` 同形） */
private val MERGE_TEST_STRINGS = com.keepasskey.app.ui.model.StringsProvider { _, _ -> "" }

/**
 * 构造固定 UUID（`KdbxUuid` 的 16 字节构造）。
 *
 * **两次对照运行必须使用同一批标识**：条目 / 分组 id 参与合并匹配，若各自随机生成，
 * 两次运行的快照天然不同，差分断言会退化为「比 UUID」而失去意义。
 */
private fun uuidOf(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val UUID_ROOT = uuidOf("00000000000000000000000000000001")
private val UUID_SHARED = uuidOf("00000000000000000000000000000002")
private val UUID_LOCAL_GROUP = uuidOf("00000000000000000000000000000003")
private val UUID_REMOTE_GROUP = uuidOf("00000000000000000000000000000004")
private val UUID_COMMON = uuidOf("00000000000000000000000000000005")
private val UUID_LOCAL_ONLY = uuidOf("00000000000000000000000000000006")
private val UUID_REMOTE_ONLY = uuidOf("00000000000000000000000000000007")

/**
 * `ISSUE-P3-168` ①的**差分验收**：三方合并的本地侧「取内存树」与「解析 localBytes 回树」
 * 两种来源必须产出**逐字段一致**的结果，且内存树**不得**被本类擦除。
 *
 * 直接驱动 [SyncConflictController.handleConflictMerge]（用 `localDbOverride` 参数显式选择两条路径），
 * 而非经由 `SyncCoordinator`——后者只能走单一分支，无法构造同输入的对照运行。
 *
 * 三条判据各自锁定的东西：
 * 1. **等价性**（自动合并分支）：两种来源被采用为会话库的树，按「组 / 条目 / 字段（含保护标志）/
 *    自定义字段 / 标签 / 历史条数 / 墓碑」逐项快照比较必须完全相同 ⇒ 对应 AC 的
 *    「合并结果与现状逐字段一致」；
 * 2. **等价性**（条目级冲突分支）：冲突清单 + 用户 `KEEP_LOCAL` 裁决后的落库结果同样必须一致
 *    （该分支是「先冻结 pending 上下文、稍后回写」的路径，别名/所有权风险最高）;
 * 3. **擦除边界**（P0 级护栏）：远端字节解析失败时合并整体放弃，此时**传入的内存树绝不能被擦除** ——
 *    若 `wipeDiscarded` 的 `localDbOwned` 判据失效，活动库会被静默清空，本用例必须变红。
 *
 * **边界如实声明**：快照**不含** `times`（`KdbxContentComparator` 刻意不比 `times`，见
 * `已知工程限界.md` §10）与 `binaries` 池形态（属实现细节）；本用例**不**断言 KDF 派生次数
 * （宿主侧无可注入的计数缝，见批次文档的边界声明），它只证明「两条路径产物等价 + 擦除边界安全」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictMergeLocalTreeEquivalenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val masterPassword = "MergeEquiv#2026".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        masterPassword.fill('0')
        // Main 采用「只装不卸」口径（ISSUE-P3-189 路线①）：此处不 resetMain()，
        // 收尾统一走 MainDispatcherGuard（见其类 KDoc 的实测反证）。
        MainDispatcherGuard.tearDown()
    }

    /** 最小远端替身（与 `SyncCoordinatorTest.MemorySyncProvider` 同形，只保留本用例触达的方法） */
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

        override suspend fun download(remotePath: String): Result<ByteArray> =
            remoteStorage[remotePath]?.let { Result.success(it.first) }
                ?: Result.failure(SyncException.FileNotFound("File not found: $remotePath"))

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
            val etag = "etag-${data.size}"
            remoteStorage[remotePath] = Pair(data, etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteStorage.remove(remotePath)
            return Result.success(Unit)
        }
    }

    /** 一次「本地改动 + 远端改动」场景的三方字节与关键 UUID */
    private class Fixture(
        val localBytes: ByteArray,
        val remoteBytes: ByteArray,
        val baseBytes: ByteArray,
        val commonEntryId: KdbxUuid
    )

    /** 一次合并运行的观测结果（全部为可比对的字符串形态） */
    private class RunResult(
        val outcome: String,
        val adopted: String?,
        val conflictIds: List<String>,
        val resolveOutcome: String?
    )

    /**
     * 造场景：base 含共有条目 `Common`（口令 `Base#1`）与分组 `Shared`；
     * 本地新增 `LocalOnly` + 分组 `LocalGroup`，远端新增 `RemoteOnly` + 分组 `RemoteGroup`；
     * [conflicting] 为 true 时双方再**各自改写 `Common` 的口令**（制造条目级字段冲突）。
     */
    private suspend fun buildFixture(
        session: DatabaseSession,
        workDir: File,
        conflicting: Boolean
    ): Fixture {
        val created = session.create(
            file = File(workDir, "merge_equiv.kdbx"),
            name = "MergeEquiv",
            passwordChars = masterPassword,
            useArgon2 = false
        )
        assertTrue("建库必须成功: $created", created is KdbxResult.Success)

        val codec = SyncDatabaseCodec(session, DebugLogBuffer())
        val commonId = UUID_COMMON
        val commonEntry = KdbxEntry(
            id = commonId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Common", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("user", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Base#1", true)
            )
        )
        val baseRoot = KdbxGroup(
            id = UUID_ROOT,
            name = "Root",
            entries = listOf(commonEntry),
            subgroups = listOf(KdbxGroup(id = UUID_SHARED, name = "Shared"))
        )
        val baseDb = session.databaseFlow.value!!.copy(rootGroup = baseRoot)
        session.updateDatabaseMeta { baseDb }
        val baseBytes = codec.serializeLocalDatabase(baseDb) ?: error("base 序列化失败")

        val localCommon = if (conflicting) {
            commonEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("Local#2", true))
        } else {
            commonEntry
        }
        val localRoot = baseRoot.copy(
            entries = listOf(localCommon, newEntry("LocalOnly", UUID_LOCAL_ONLY)),
            subgroups = baseRoot.subgroups + KdbxGroup(id = UUID_LOCAL_GROUP, name = "LocalGroup")
        )
        val localDb = baseDb.copy(rootGroup = localRoot)
        session.updateDatabaseMeta { localDb }
        session.save()
        val localBytes = codec.serializeLocalDatabase(localDb) ?: error("local 序列化失败")

        val remoteCommon = if (conflicting) {
            commonEntry.withField(KdbxConstants.Fields.PASSWORD, ProtectedString("Remote#3", true))
        } else {
            commonEntry
        }
        val remoteRoot = baseRoot.copy(
            entries = listOf(remoteCommon, newEntry("RemoteOnly", UUID_REMOTE_ONLY)),
            subgroups = baseRoot.subgroups + KdbxGroup(id = UUID_REMOTE_GROUP, name = "RemoteGroup")
        )
        val remoteBytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, baseDb.copy(rootGroup = remoteRoot), masterPassword) }
            .toByteArray()

        return Fixture(localBytes, remoteBytes, baseBytes, commonId)
    }

    private fun newEntry(title: String, id: KdbxUuid) = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString("$title#pwd", true)
        )
    )

    private suspend fun runConflictCycle(
        key: String,
        conflicting: Boolean,
        useMemoryTree: Boolean
    ): RunResult {
        val workDir = File(tempFolder.root, key).apply { mkdirs() }
        val session = DatabaseSession()
        val fixture = buildFixture(session, workDir, conflicting)

        val remotePath = "/remote/$key.kdbx"
        val provider = MemorySyncProvider()
        provider.remoteStorage[remotePath] = Pair(fixture.remoteBytes, "etag-1")

        val cache = SyncCache(File(workDir, "cache").apply { mkdirs() })
        val engine = SyncEngine(
            provider,
            cache,
            SyncRollbackGuard(File(workDir, "rollback").apply { mkdirs() }, NoopSyncIntegrityMac)
        )
        val controller = SyncConflictController(
            databaseSession = session,
            codec = SyncDatabaseCodec(session, DebugLogBuffer()),
            strings = MERGE_TEST_STRINGS,
            session = SyncSessionState(),
            debugLog = DebugLogBuffer()
        )

        val outcome = controller.handleConflictMerge(
            syncEngine = engine,
            syncCache = cache,
            remotePath = remotePath,
            localBytes = fixture.localBytes,
            remoteBytes = fixture.remoteBytes,
            baseSnapshotBytes = fixture.baseBytes,
            remoteEtag = "etag-1",
            strategy = SyncConflictStrategy.AUTO_MERGE,
            // 差分自变量：内存树快照 vs 让实现自行解析 localBytes
            localDbOverride = if (useMemoryTree) session.databaseFlow.value else null
        )
        val conflictIds = controller.conflictFlow.value.map { it.entryId }.sorted()

        val resolveOutcome = if (outcome is SyncOutcome.ConflictNeedsUser) {
            controller.resolveConflicts(
                mapOf(fixture.commonEntryId.toHexString() to ConflictResolutionChoice.KEEP_LOCAL)
            ).javaClass.simpleName
        } else {
            null
        }

        return RunResult(
            outcome = outcome.javaClass.simpleName,
            adopted = session.databaseFlow.value?.snapshot(),
            conflictIds = conflictIds,
            resolveOutcome = resolveOutcome
        )
    }

    @Test
    fun `自动合并分支：本地侧取内存树与解析回树的产物逐字段一致`() = runTest(testDispatcher) {
        val withTree = runConflictCycle("union-memory", conflicting = false, useMemoryTree = true)
        val viaParse = runConflictCycle("union-parse", conflicting = false, useMemoryTree = false)

        assertEquals(
            "前提：本场景必须走到自动合并分支（否则本用例不构成证据）",
            SyncOutcome.MergedAndUploaded::class.java.simpleName,
            withTree.outcome
        )
        assertEquals("结果类型必须一致", viaParse.outcome, withTree.outcome)
        assertEquals("合并后采用的会话库必须逐字段一致", viaParse.adopted, withTree.adopted)
        // 场景自检：并集语义（3 条目 + 3 分组）确实成立，避免「两边都空」式的假一致
        val adopted = withTree.adopted!!
        assertTrue("共有条目必须在: $adopted", adopted.contains("Common"))
        assertTrue("本地独有条目必须在: $adopted", adopted.contains("LocalOnly"))
        assertTrue("远端独有条目必须在: $adopted", adopted.contains("RemoteOnly"))
        assertTrue("本地独有分组必须在: $adopted", adopted.contains("LocalGroup"))
        assertTrue("远端独有分组必须在: $adopted", adopted.contains("RemoteGroup"))
    }

    @Test
    fun `条目级冲突分支：两种本地侧的冲突清单与裁决后产物逐字段一致`() = runTest(testDispatcher) {
        val withTree = runConflictCycle("conflict-memory", conflicting = true, useMemoryTree = true)
        val viaParse = runConflictCycle("conflict-parse", conflicting = true, useMemoryTree = false)

        assertEquals(
            "前提：双方改写同一字段必须进入用户决策（否则本用例不构成证据）",
            SyncOutcome.ConflictNeedsUser::class.java.simpleName,
            withTree.outcome
        )
        assertEquals("冲突清单必须一致", viaParse.conflictIds, withTree.conflictIds)
        assertTrue("必须恰好报出共有条目的字段冲突: ${withTree.conflictIds}", withTree.conflictIds.size == 1)
        assertEquals("裁决后的结果类型必须一致", viaParse.resolveOutcome, withTree.resolveOutcome)
        assertEquals("裁决后采用的会话库必须逐字段一致", viaParse.adopted, withTree.adopted)
        assertTrue(
            "KEEP_LOCAL 后本地口令必须存活: ${withTree.adopted}",
            withTree.adopted!!.contains("Local#2")
        )
    }

    /**
     * P0 级护栏：远端字节解析失败 ⇒ 合并整体放弃；此时传入的内存树**绝不能被擦除**
     * （若 `localDbOwned` 判据失效，活动库会被静默清空）。两种来源都跑一遍：两条路径
     * 都不得动会话树。
     */
    @Test
    fun `远端解析失败时不得擦除会话内存树`() = runTest(testDispatcher) {
        for (useMemoryTree in listOf(true, false)) {
            val key = "wipe-guard-${if (useMemoryTree) "tree" else "parse"}"
            val workDir = File(tempFolder.root, key).apply { mkdirs() }
            val session = DatabaseSession()
            val fixture = buildFixture(session, workDir, conflicting = false)

            val provider = MemorySyncProvider()
            val remotePath = "/remote/$key.kdbx"
            val cache = SyncCache(File(workDir, "cache").apply { mkdirs() })
            val engine = SyncEngine(
                provider,
                cache,
                SyncRollbackGuard(File(workDir, "rollback").apply { mkdirs() }, NoopSyncIntegrityMac)
            )
            val controller = SyncConflictController(
                databaseSession = session,
                codec = SyncDatabaseCodec(session, DebugLogBuffer()),
                strings = MERGE_TEST_STRINGS,
                session = SyncSessionState(),
                debugLog = DebugLogBuffer()
            )

            val outcome = controller.handleConflictMerge(
                syncEngine = engine,
                syncCache = cache,
                remotePath = remotePath,
                localBytes = fixture.localBytes,
                // 非 KDBX 字节 ⇒ 远端解析必然失败，进入 `wipeDiscarded` 分支
                remoteBytes = ByteArray(64) { 0x41 },
                baseSnapshotBytes = fixture.baseBytes,
                remoteEtag = "etag-1",
                strategy = SyncConflictStrategy.AUTO_MERGE,
                localDbOverride = if (useMemoryTree) session.databaseFlow.value else null
            )

            assertTrue(
                "远端解析失败必须如实报错（useMemoryTree=$useMemoryTree）: $outcome",
                outcome is SyncOutcome.Error
            )
            // 会话树必须原样存活：条目字段仍可读出明文（被擦除会抛 IllegalStateException 或读出空串）
            val adopted = session.databaseFlow.value?.snapshot() ?: error("会话树不得丢失")
            assertTrue("会话树必须完好（useMemoryTree=$useMemoryTree）: $adopted", adopted.contains("Common"))
            assertTrue("会话树口令必须完好（useMemoryTree=$useMemoryTree）: $adopted", adopted.contains("Base#1"))
        }
    }

    /**
     * 字段级确定性快照：组（id / 名称 / 备注 / 标签）+ 条目（id / 全字段含保护标志 /
     * 自定义字段 / 标签 / 历史条数）+ 墓碑 UUID。**不含** `times` 与 `binaries` 池（见类 KDoc 边界声明）。
     */
    private fun KdbxDatabase.snapshot(): String = buildString {
        append("deleted=").append(deletedObjects.map { it.id.toHexString() }.sorted()).append('\n')
        append(rootGroup.snapshot(0))
    }

    private fun KdbxGroup.snapshot(depth: Int): String = buildString {
        val pad = "  ".repeat(depth)
        append(pad).append("G ").append(id.toHexString()).append(' ').append(name)
            .append(" notes=").append(notes).append(" tags=").append(tags.sorted()).append('\n')
        entries.sortedBy { it.id.toHexString() }.forEach { entry ->
            append(pad).append("  E ").append(entry.id.toHexString()).append('\n')
            entry.fields.toSortedMap().forEach { (key, value) ->
                append(pad).append("    f ").append(key).append('=').append(value.readString())
                    .append(" protected=").append(value.isProtected).append('\n')
            }
            append(pad).append("    customFields=")
                .append(entry.customFields.map { "${it.key}=${it.value.readString()}" }.sorted()).append('\n')
            append(pad).append("    tags=").append(entry.tags.sorted())
                .append(" history=").append(entry.history.size).append('\n')
        }
        subgroups.forEach { append(it.snapshot(depth + 1)) }
    }
}
