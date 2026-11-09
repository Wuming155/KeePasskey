package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.time.Instant

/** 单测无资源环境，注入返回占位文本的假 StringsProvider（与同目录既有用例同形）。 */
private val BASE_TRUST_STRINGS = StringsProvider { _, _ -> "" }

/**
 * 夹具用固定**整秒**时间戳（口径见 `entry` 的说明）：KDBX 落盘只保留秒，
 * 用 `Instant.now()` 会让内存侧与解析侧的同一时刻在亚秒上不等。
 */
private val KEEP_MODIFIED: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** 删除时刻必须晚于被删条目的修改时刻，否则「删除后重建」规则会复活该条目。 */
private val DELETED_AT: Instant = Instant.parse("2026-01-02T00:00:00Z")

/**
 * ENG-20261028-01：共同基线的可信判据**只看内容身份，不看「是否与本地字节相等」**。
 *
 * 旧判据 `!baseSnapshotBytes.contentEquals(localBytes)` 把「base 与本地字节相同」当作
 * 「base 已被本地内容顶替」的证据，理由是 KDBX4 随机 IV 让同一内容的两次序列化必然不同。
 * 该理由推不出结论：同步收敛时 `.cache` 与 `.basecache` 由同一个数组写出，本地内容未变时
 * 又会直接复用缓存字节，因此「字节相等」恰是正常收敛形态。按旧判据，合法基线会被判为不可信
 * 并被擦除，三方合并退化为「空库底版 + 双方并集」——远端删除被复活、基线判断整体失效。
 *
 * 本用例锁两件事：
 * 1. [resolveTrustedBase] 对「与本地字节完全相同」的合法基线返回可信结果，且基线树未被擦除；
 * 2. 在生产合并入口（[SyncConflictController.handleConflictMerge]）上，相等基线使远端删除生效；
 *    以「无基线」作对照时同一删除会被并集语义复活——这正是旧判据的后果形态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictBaseTrustTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val masterPassword = "BaseTrust#Fixture".toCharArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        masterPassword.fill('0')
        MainDispatcherGuard.tearDown()
    }

    private class Fixture(
        val session: DatabaseSession,
        val codec: SyncDatabaseCodec,
        val baseBytes: ByteArray,
        val remoteBytes: ByteArray,
        val keepId: KdbxUuid,
        val deletedId: KdbxUuid
    )

    /**
     * 构造固定时刻的条目。
     *
     * **时间戳必须显式给到整秒**：KDBX 只以「自 0001-01-01 起的秒数」落盘（官方规范，本仓
     * `KdbxXmlTimeHelper` 同样丢弃亚秒），而内存条目的 `lastModificationTime` 带纳秒。
     * 若沿用默认的 `Instant.now()`，内存侧与解析侧的同一时刻会在亚秒上不等，
     * 从而把「本地未修改」误判成「本地已修改」——本用例用固定整秒消除该噪声。
     */
    private fun entry(
        id: KdbxUuid,
        title: String,
        password: String? = null,
        modified: Instant = KEEP_MODIFIED
    ): KdbxEntry = KdbxEntry(
        id = id,
        fields = buildMap {
            put(KdbxConstants.Fields.TITLE, ProtectedString(title, false))
            if (password != null) put(KdbxConstants.Fields.PASSWORD, ProtectedString(password, true))
        },
        times = KdbxTimes(creationTime = modified)
    )

    /**
     * 建库并造出三方场景：基线含 `Keep` 与 `Deleted` 两条；远端删除了 `Deleted` 且未留墓碑
     * （无墓碑单边删除），其余内容与基线一致（本地未修改）。
     */
    private suspend fun createFixture(key: String): Fixture {
        val workDir = File(tempFolder.root, key).apply { mkdirs() }
        val session = DatabaseSession()
        val created = session.create(
            file = File(workDir, "$key.kdbx"),
            name = key,
            passwordChars = masterPassword,
            useArgon2 = false
        )
        assertTrue("夹具建库失败: $created", created is KdbxResult.Success)

        val keepId = KdbxUuid.random()
        val deletedId = KdbxUuid.random()
        val root = KdbxGroup(
            id = KdbxUuid.random(),
            name = "Root",
            entries = listOf(
                entry(keepId, "Keep", "Base#1", KEEP_MODIFIED),
                entry(deletedId, "Deleted")
            )
        )
        val baseDb = session.databaseFlow.value!!.copy(rootGroup = root)
        session.updateDatabaseMeta { baseDb }
        assertTrue("夹具落盘失败", session.save() is KdbxResult.Success)

        val codec = SyncDatabaseCodec(session, DebugLogBuffer())
        val baseBytes = codec.serializeLocalDatabase(baseDb) ?: error("基线序列化失败")

        val remoteDb = baseDb.copy(
            rootGroup = root.copy(entries = listOf(entry(keepId, "Keep", "Base#1", KEEP_MODIFIED))),
            deletedObjects = emptyList()
        )
        val remoteBytes = codec.serializeLocalDatabase(remoteDb) ?: error("远端序列化失败")

        return Fixture(session, codec, baseBytes, remoteBytes, keepId, deletedId)
    }

    private fun titlesOf(db: KdbxDatabase?): Set<String> =
        db!!.rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.TITLE]?.readString() }
            .toSet()

    @Test
    fun `与本地字节相同的合法基线必须被采信且不被擦除`() = runTest(testDispatcher) {
        val fixture = createFixture("equal-base")

        // 同一个数组：模拟「本地内容未变 ⇒ localBytes 直接复用缓存/基线字节」的生产形态
        val snapshot = resolveTrustedBase(fixture.codec, fixture.baseBytes, fixture.baseBytes)

        assertNotNull("字节相等的合法基线不得被拒绝（ENG-20261028-01）", snapshot.trusted)
        assertEquals(
            "基线镜像必须是完整内容，而不是空库并集底版",
            setOf("Keep", "Deleted"),
            titlesOf(snapshot.trusted)
        )
        val password = snapshot.trusted!!.rootGroup.allEntries()
            .first { it.id == fixture.keepId }
            .fields[KdbxConstants.Fields.PASSWORD]!!
        assertEquals("基线树不得被擦除：口令仍可读", "Base#1", password.readString())

        // 内容相同但属于不同数组：判据是内容身份，不是实例身份
        val copied = resolveTrustedBase(fixture.codec, fixture.baseBytes, fixture.baseBytes.copyOf())
        assertNotNull("内容相同的不同数组同样必须被采信", copied.trusted)
        assertEquals(setOf("Keep", "Deleted"), titlesOf(copied.trusted))
    }

    @Test
    fun `不可解析的基线回退空库底版而不是本地内容`() = runTest(testDispatcher) {
        val fixture = createFixture("broken-base")

        val snapshot = resolveTrustedBase(fixture.codec, ByteArray(64) { 0x41 }, fixture.baseBytes)

        assertNull("无法解析的字节不得成为可信基线", snapshot.trusted)
        assertTrue(
            "不可信时必须退化为空库并集语义，不得以本地字节冒充 base",
            snapshot.trustedLite.rootGroup.entries.isEmpty() && snapshot.trustedLite.deletedObjects.isEmpty()
        )
    }

    /**
     * 生产合并入口对照：本地未修改、远端删除条目。
     *
     * - 基线可信（与本地字节相同）：删除被确认，合并结果不含 `Deleted`；
     * - 无基线（旧判据拒绝后的退化形态）：`Deleted` 被当作「本地独有」并集保留 ⇒ 删除复活。
     */
    @Test
    fun `相等基线下远端删除生效而无基线对照会复活删除`() = runTest(testDispatcher) {
        val trusted = runMerge(key = "trusted-equal", baseForMerge = { it.baseBytes })
        assertEquals(
            "可信基线必须让远端删除生效（本地未修改 ⇒ 确认删除）",
            setOf("Keep"),
            trusted
        )

        val untrusted = runMerge(key = "untrusted-union", baseForMerge = { null })
        assertTrue(
            "对照：无基线时并集语义会复活远端已删除的条目（旧判据的后果形态）: $untrusted",
            "Deleted" in untrusted
        )
        assertTrue("对照：保留条目不得丢失", "Keep" in untrusted)
    }

    /** 驱动生产合并入口；[baseForMerge] 由夹具决定传入哪份基线（null = 空库底版）。 */
    private suspend fun runMerge(
        key: String,
        baseForMerge: (Fixture) -> ByteArray?
    ): Set<String> {
        val fixture = createFixture(key)
        val remotePath = "/remote/$key.kdbx"
        val provider = MemorySyncProvider().apply {
            remoteStorage[remotePath] = fixture.remoteBytes to "etag-1"
        }
        val workDir = File(tempFolder.root, "$key-work").apply { mkdirs() }
        val cache = SyncCache(File(workDir, "cache").apply { mkdirs() })
        val engine = SyncEngine(provider, cache)
        val controller = SyncConflictController(
            databaseSession = fixture.session,
            codec = fixture.codec,
            strings = BASE_TRUST_STRINGS,
            session = SyncSessionState(),
            debugLog = DebugLogBuffer()
        )

        val outcome = controller.handleConflictMerge(
            syncEngine = engine,
            syncCache = cache,
            remotePath = remotePath,
            // 本地字节即基线字节：本地内容未修改时复用缓存快照的生产形态
            localBytes = fixture.baseBytes,
            remoteBytes = fixture.remoteBytes,
            baseSnapshotBytes = baseForMerge(fixture),
            remoteEtag = "etag-1",
            strategy = SyncConflictStrategy.AUTO_MERGE,
            localDbOverride = fixture.session.databaseFlow.value,
            expectedSessionSnapshot = fixture.session.databaseFlow.value
        )

        assertEquals(
            "本地未修改 + 远端删除应自动合并并上传（key=$key）: $outcome",
            SyncOutcome.MergedAndUploaded,
            outcome
        )
        val adopted = fixture.session.databaseFlow.value
        assertTrue("合并后会话库不得丢失保留条目（key=$key）", "Keep" in titlesOf(adopted))
        return titlesOf(adopted)
    }

    /** 最小远端替身：条件写上传，ETag 随内容变化（与同目录既有用例同形）。 */
    private class MemorySyncProvider : SyncProvider {
        val remoteStorage = mutableMapOf<String, Pair<ByteArray, String>>()

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val item = remoteStorage[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("远端不存在: $remotePath"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = 0L,
                    etag = item.second
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            val item = remoteStorage[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("远端不存在: $remotePath"))
            sink.write(item.first)
            return Result.success(Unit)
        }

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
                        message = "预条件失败"
                    )
                )
            }
            val etag = "etag-${data.size}"
            remoteStorage[remotePath] = data.copyOf() to etag
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remoteStorage.remove(remotePath)
            return Result.success(Unit)
        }
    }
}
