package com.keepasskey.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
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
import com.keepasskey.sync.engine.SyncRollbackGuard
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

/**
 * `ENG-20261028-31`：同步合并三条路径（成功合并 / 冲突裁决 / 上传 412 重入）的
 * **整库序列化次数与设备耗时**读数（评估测量，非回归闸门）。
 *
 * ## 为什么需要这一层
 *
 * 结构性证据已有 `SyncMergePersistBoundaryTest`（JVM）：它断言「上传字节 == 本地写出字节」——
 * KDBX4 每次保存重生成 masterSeed / IV / KDF salt，二次序列化不可能得到同一字节，故该断言等价于
 * 「合并产物只序列化过一次」。但该用例**拿不到次数与耗时**：它没有统计一个真实周期里究竟发生了
 * 几次整库序列化，也没有设备上的墙钟读数。本探针补的就是这两项。
 *
 * ## 计数口径（可观测，不依赖源码文本 / 调用次数断言）
 *
 * 整库序列化在本仓只有三种可观测去向（每一次 `KdbxFile.save` 的产物必然落到其中之一）：
 * 1. **本地落盘** —— `openStream(saveWriter = …)` 注入的写出通道收到的字节；
 * 2. **远端上传** —— 测试用 `SyncProvider.upload` 收到的字节（`uploadAtomic` 默认委托它）；
 * 3. **同步缓存写入** —— `SyncCache.writeCache` 收到的字节（`SyncCache` 与该函数均为 `open`）。
 *
 * 判据：KDBX4 每次保存都重生成 masterSeed / IV / KDF salt ⇒ **不同序列化产物必不同字节**。
 * 因此「三处落点上出现过的**互异密文**个数」＝该场景实际发生的整库序列化次数。
 * 同一份产物的多次使用（借出上传 + 同缓冲落盘）共享同一摘要，只计一次——这正是 ENG-31 要求
 * 的「复用预备产物」，而不是「又序列化了一遍」。
 *
 * 附带读数：`上传与落盘同一实例`（上传收到的数组与落盘收到的数组是**同一引用**，比字节相等
 * 更强，直接证明「同一份预备密文的借用」，连一次整库拷贝都没有）。
 *
 * ## 输出
 *
 * 一律 `PERF-PROBE|ENG31|` 前缀，经 `Log.i` + `println` 双路落盘（logcat 与
 * `app/build/outputs/androidTest-results/connected/debug/<设备名>/logcat-*.txt`）。
 * **不做阈值断言**：次数与耗时由记录裁定，本用例只断言正确性不变量。
 *
 * ## 库规模与 KDF
 *
 * 合成库约 2 MiB 密文（600 条 × 4 KiB 高熵备注，GZip 压不动），远低于建议的 4 MiB 上限。
 * KDF 走 `useArgon2 = false`（AES-KDF，生产默认轮数），与既有设备用例同口径；单次序列化 /
 * 单次解析的墙钟另以「基准」行给出，便于把「次数」换算成可核对的时间量级。
 *
 * ## 复用的既有基建（形态对齐，不重复实现）
 *
 * - 记录型 writer / provider 与「上传字节 == 落盘字节」断言：沿用 `SyncMergePersistBoundaryTest`；
 * - 记录型 `SyncCache`（子类化 open 方法）：沿用 `SyncAssemblyScaleDeviceTest.TimingCache` 的做法；
 * - 412 触发方式（前 N 次 upload 一律 ConflictError）：沿用 `SyncCoordinatorTest.MemorySyncProvider`；
 * - 完整生产周期（`SyncCoordinator.syncNow()` / `resolveConflicts()`）而非直接调合并入口：
 *   这样读数包含装配段序列化与冲突前保存，正是本issue要量化的部分。
 */
@RunWith(AndroidJUnit4::class)
class SyncMergeSerializationProbeDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // ================= 场景 1：成功合并（无字段分叉 → 自动合并上传） =================

    @Test
    fun probe成功合并_序列化次数与耗时() = runBlocking {
        val fx = buildFixture("auto-merge")
        try {
            assertTrue("前置：首轮应建立远端基线: ${fx.baselineOutcome}", fx.baselineOutcome is SyncOutcome.UploadedLocal)

            fx.session.saveEntry(newEntry("LocalOnly-$LOCAL_TAG", "LocalOnly#pass"))
            assertTrue("前置：本地新增必须落盘", fx.session.save().isSuccess)
            publishRemoteAddOnly(fx, newEntry("RemoteOnly-$REMOTE_TAG", "RemoteOnly#pass"))

            val libraryBytes = fx.vaultFile.length()
            fx.census.reset()
            val startedAt = System.nanoTime()
            val outcome = fx.coordinator.syncNow()
            val totalMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("成功合并路径必须产出 MergedAndUploaded: $outcome", outcome is SyncOutcome.MergedAndUploaded)
            verifyVersionCorrespondence(fx)
            reportScenario(fx, "成功合并", totalMs, libraryBytes)
            reportCalibration(fx)
        } finally {
            fx.close()
        }
    }

    // ================= 场景 2：冲突 → 用户裁决（resolveConflicts） =================

    @Test
    fun probe用户裁决_序列化次数与耗时() = runBlocking {
        val fx = buildFixture("resolve")
        try {
            assertTrue("前置：首轮应建立远端基线: ${fx.baselineOutcome}", fx.baselineOutcome is SyncOutcome.UploadedLocal)

            // 双方改写同一条目的同一字段 → 必须进入用户决策
            fx.session.saveEntry(
                fx.commonEntry.copy(
                    fields = fx.commonEntry.fields +
                        (KdbxConstants.Fields.PASSWORD to ProtectedString("Local#2", true))
                )
            )
            assertTrue("前置：本地改动必须落盘", fx.session.save().isSuccess)
            publishRemoteFieldChange(fx, "Remote#3")

            // 阶段 A：检测轮（应产出 ConflictNeedsUser，不做合并提交）
            val detectStartedAt = System.nanoTime()
            val detectOutcome = fx.coordinator.syncNow()
            val detectMs = (System.nanoTime() - detectStartedAt) / 1_000_000
            assertTrue(
                "前提：同字段双侧异值必须进入用户决策: $detectOutcome",
                detectOutcome is SyncOutcome.ConflictNeedsUser
            )
            reportScenario(fx, "冲突检测轮", detectMs, fx.vaultFile.length())

            // 阶段 B：用户裁决提交（合并产物 → 上传 → 落盘）
            val conflictId = (detectOutcome as SyncOutcome.ConflictNeedsUser).conflicts.first().entryId
            fx.census.reset()
            val resolveStartedAt = System.nanoTime()
            val resolved = fx.coordinator.resolveConflicts(mapOf(conflictId to ConflictResolutionChoice.KEEP_LOCAL))
            val resolveMs = (System.nanoTime() - resolveStartedAt) / 1_000_000

            assertTrue("用户裁决必须收敛为 MergedAndUploaded: $resolved", resolved is SyncOutcome.MergedAndUploaded)
            verifyVersionCorrespondence(fx)
            reportScenario(fx, "用户裁决提交", resolveMs, fx.vaultFile.length())
            assertEquals(
                "裁决必须取本地口令",
                "Local#2",
                fx.session.databaseFlow.value!!.rootGroup.findEntry(fx.commonEntry.id)!!
                    .fields.getValue(KdbxConstants.Fields.PASSWORD).readString()
            )
            reportCalibration(fx)
        } finally {
            fx.close()
        }
    }

    // ================= 场景 3：合并上传 412 → 重入 → 二次合并 =================

    @Test
    fun probe上传412重入_序列化次数与耗时() = runBlocking {
        val fx = buildFixture("reentry-412")
        try {
            assertTrue("前置：首轮应建立远端基线: ${fx.baselineOutcome}", fx.baselineOutcome is SyncOutcome.UploadedLocal)

            fx.session.saveEntry(newEntry("LocalOnly-$LOCAL_TAG", "LocalOnly#pass"))
            assertTrue("前置：本地新增必须落盘", fx.session.save().isSuccess)
            publishRemoteAddOnly(fx, newEntry("RemoteOnly-$REMOTE_TAG", "RemoteOnly#pass"))

            // 前两次上传一律 412：① 合并产物上传 412 ⇒ 触发重入；
            // ② 重入前「取最新远端」的 commitLocal 上传也 412 ⇒ 携最新远端进入二次合并
            fx.provider.forceConflictUploads = 2

            val libraryBytes = fx.vaultFile.length()
            fx.census.reset()
            val startedAt = System.nanoTime()
            val outcome = fx.coordinator.syncNow()
            val totalMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("412 重入后二次合并上传必须成功: $outcome", outcome is SyncOutcome.MergedAndUploaded)
            verifyVersionCorrespondence(fx)
            reportScenario(fx, "上传412重入", totalMs, libraryBytes)
            val titles = fx.session.databaseFlow.value!!.rootGroup.allEntries()
                .mapNotNull { it.fields[KdbxConstants.Fields.TITLE]?.readString() }
            assertTrue(
                "双侧新增必须同时存活（重入不得丢数据）: $titles",
                titles.containsAll(listOf("LocalOnly-$LOCAL_TAG", "RemoteOnly-$REMOTE_TAG"))
            )
            reportCalibration(fx)
        } finally {
            fx.close()
        }
    }

    // ================= 夹具 =================

    private class Fixture(
        val session: DatabaseSession,
        val coordinator: SyncCoordinator,
        val provider: CensusProvider,
        val codec: SyncDatabaseCodec,
        val census: Census,
        val writer: RecordingWriter,
        val vaultFile: File,
        val remotePath: String,
        /**
         * 远端字节 ↔ 数据库树的编解码闭包。
         *
         * 之所以用闭包而不是在夹具上暴露口令操作：编解码是「测试侧构造远端版本」这一个用途，
         * 闭包在 `buildFixture` 内构造，天然只被本夹具使用；口令副本本身由 [password] 持有，
         * 供 [close] 确定性清零。
         */
        val openRemote: (ByteArray) -> KdbxDatabase,
        val writeRemote: (KdbxDatabase) -> ByteArray,
        /** 本夹具的主密码副本；由 [close] 清零（敏感数据铁律：不得依赖 GC）。 */
        val password: CharArray,
        val commonEntry: KdbxEntry,
        val baselineOutcome: SyncOutcome,
        private val dir: File
    ) {
        suspend fun close() {
            session.close()
            password.fill('\u0000')
            dir.deleteRecursively()
        }
    }

    /**
     * 记录型写出通道：统计「落盘序列化产物」，并保留一份**副本**用于事后字节比对。
     *
     * 必须复制的原因：`commitPreparedSave` 结束后会 `prepared.wipe()` 就地清零那份缓冲，
     * 而落盘与上传共享的就是该缓冲本体——不复制就只能读到全零。同一性判据另用
     * `System.identityHashCode` 在调用当时采样。
     */
    private class RecordingWriter(private val target: File, private val census: Census) {
        var lastWritten: ByteArray? = null
        var lastWrittenIdentity: Int = 0
        var lastWrittenDigest: String = ""

        suspend fun write(bytes: ByteArray) {
            census.record(SINK_LOCAL_WRITE, bytes)
            lastWrittenIdentity = System.identityHashCode(bytes)
            lastWrittenDigest = sha256Hex(bytes)
            lastWritten?.fill(0)
            lastWritten = bytes.copyOf()
            target.writeBytes(bytes)
        }
    }

    /** 计数型 Provider：`uploadAtomic` 默认委托 [upload]，故两者都被计入。 */
    private class CensusProvider(private val census: Census) : SyncProvider {
        val remote = mutableMapOf<String, Pair<ByteArray, String>>()
        var forceConflictUploads: Int = 0
        var uploadCalls: Int = 0
        var lastUploaded: ByteArray? = null
        var lastUploadedIdentity: Int = 0
        var lastUploadedDigest: String = ""
        private var etagSeq = 0

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val item = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("File not found: $remotePath"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    etag = item.second,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = 0L
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            val item = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("File not found: $remotePath"))
            sink.write(item.first)
            return Result.success(Unit)
        }

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            // 先计数再判 412：序列化产物已经产生，被拒的上传同样消耗了一次整库序列化
            census.record(SINK_UPLOAD, data)
            uploadCalls++
            lastUploadedIdentity = System.identityHashCode(data)
            lastUploadedDigest = sha256Hex(data)
            lastUploaded?.fill(0)
            lastUploaded = data.copyOf()

            val existing = remote[remotePath]
            if (forceConflictUploads > 0) {
                forceConflictUploads--
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing?.second.orEmpty(),
                        localExpectedEtag = expectedEtag.orEmpty(),
                        message = "Forced 412"
                    )
                )
            }
            if (expectedEtag != null && existing != null && existing.second != expectedEtag) {
                return Result.failure(
                    SyncException.ConflictError(
                        remoteEtag = existing.second,
                        localExpectedEtag = expectedEtag,
                        message = "Precondition Failed"
                    )
                )
            }
            val etag = "etag-${++etagSeq}"
            remote[remotePath] = data.copyOf() to etag
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }

    /** 只统计**写缓存**这一落点；读路径保持生产语义（父类实现）。 */
    private class CensusCache(dir: File, private val census: Census) : SyncCache(dir) {
        override fun writeCache(
            remotePath: String,
            data: ByteArray,
            updateVersion: Boolean,
            precomputedDigest: String?
        ): String {
            census.record(SINK_CACHE_WRITE, data)
            return super.writeCache(remotePath, data, updateVersion, precomputedDigest)
        }
    }

    /** 落点记录：一次观测＝「某个落点收到了某个整库密文」。摘要相同即同一份序列化产物。 */
    private class Census {
        data class Record(val sink: String, val digest: String, val size: Int, val identity: Int)

        private val lock = Any()
        private val records = mutableListOf<Record>()

        fun record(sink: String, bytes: ByteArray) {
            val entry = Record(sink, sha256Hex(bytes), bytes.size, System.identityHashCode(bytes))
            synchronized(lock) { records += entry }
        }

        fun reset() = synchronized(lock) { records.clear() }

        fun snapshot(): List<Record> = synchronized(lock) { records.toList() }

        fun distinctProducts(): List<Record> = snapshot().distinctBy { it.digest }

        fun countOf(sink: String): Int = snapshot().count { it.sink == sink }
    }

    /** 隔离用的 Context：把 cacheDir / filesDir 指到探针私有目录，不触碰应用的正式同步缓存。 */
    private class ProbeContext(base: Context, private val cache: File, private val files: File) :
        ContextWrapper(base) {
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
    }

    private suspend fun buildFixture(label: String): Fixture {
        val root = File(context.cacheDir, "eng31-$label-${System.nanoTime()}").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val filesDir = File(root, "files").apply { mkdirs() }
        val probeContext = ProbeContext(context, cacheDir, filesDir)
        val vaultFile = File(filesDir, "$label.kdbx")
        val password = TEST_PASSWORD.copyOf()

        // ---- 建库并灌入合成大库（这一步的序列化不计入任何场景读数）----
        val bootstrap = DatabaseSession()
        assertTrue(
            "前置：合成库创建失败",
            bootstrap.create(vaultFile, label, password, useArgon2 = false) is KdbxResult.Success
        )
        val seeded = seedEntries()
        // 关闭 bootstrap 会 clearSensitiveData() 掉 seeded 的全部字段实例，故只保留 UUID
        // （非敏感量）；场景 2 需要的那一条必须从**重开会话的活动树**里取，否则读到的是已清零实例。
        val commonId = seeded.first().id
        bootstrap.setDatabaseForTesting(
            bootstrap.databaseFlow.value!!.copy(rootGroup = KdbxGroup(name = "Root", entries = seeded))
        )
        assertTrue("前置：合成大库落盘失败", bootstrap.save().isSuccess)
        bootstrap.close()

        // ---- 以记录型写出通道重开会话（此后每次落盘都会被计数）----
        val census = Census()
        val writer = RecordingWriter(vaultFile, census)
        val session = DatabaseSession()
        assertTrue(
            "前置：带记录通道的会话打开失败",
            session.openStream(
                pathIdentifier = vaultFile.absolutePath,
                inputStreamProvider = { vaultFile.inputStream() },
                saveWriter = writer::write,
                passwordChars = password,
                associatedFile = vaultFile
            ).isSuccess
        )
        // 场景 2 的被改写条目：必须取活动树中的活实例（bootstrap 已擦除 seeded 的字段实例）
        val commonEntry = session.databaseFlow.value!!.rootGroup.entries
            .first { it.id == commonId }

        val remotePath = "/eng31/$label.kdbx"
        val provider = CensusProvider(census)
        val debugLog = DebugLogBuffer()
        val preferences = SyncPreferences(debugLog, null)
        val credentialsStore = SyncCredentialsStore(probeContext, null, debugLog)
        val resolver = SyncProviderResolver(credentialsStore, preferences, debugLog)
        val syncState = SyncSessionState().apply {
            testSyncProvider = provider
            testRemotePath = remotePath
        }
        val strings = StringsProvider { id, args -> probeContext.getString(id, *args) }
        val codec = SyncDatabaseCodec(session, debugLog)
        val conflicts = SyncConflictController(session, codec, strings, syncState, debugLog)
        val changes = SyncContentChangeDetector(codec, syncState)
        val cycle = SyncCycleRunner(
            context = probeContext,
            databaseSession = session,
            session = syncState,
            providerResolver = resolver,
            codec = codec,
            conflicts = conflicts,
            changes = changes,
            preferences = preferences,
            strings = strings,
            rollbackStateDir = File(filesDir, SyncRollbackGuard.STATE_DIR_NAME)
        ).apply {
            syncCacheFactory = { dir -> CensusCache(dir, census) }
        }
        val coordinator = SyncCoordinator(
            probeContext,
            session,
            credentialsStore,
            debugLog,
            strings,
            syncSession = syncState,
            syncPreferences = preferences,
            syncProviderResolver = resolver,
            syncDatabaseCodec = codec,
            syncContentChanges = changes,
            syncConflicts = conflicts,
            syncCycle = cycle
        )

        // ---- 首轮建立远端基线（不计入读数）----
        val baselineOutcome = coordinator.syncNow()
        census.reset()

        return Fixture(
            session = session,
            coordinator = coordinator,
            provider = provider,
            codec = codec,
            census = census,
            writer = writer,
            vaultFile = vaultFile,
            remotePath = remotePath,
            openRemote = { bytes -> KdbxFile.load(ByteArrayInputStream(bytes), password) },
            writeRemote = { db -> ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray() },
            password = password,
            commonEntry = commonEntry,
            baselineOutcome = baselineOutcome,
            dir = root
        )
    }

    // ================= 远端构造 =================

    /** 在远端版本上追加一条本地不存在的条目（无字段分叉 → 可自动合并），并前移 ETag。 */
    private fun publishRemoteAddOnly(fx: Fixture, extra: KdbxEntry) {
        rewriteRemote(fx) { db ->
            db.copy(rootGroup = db.rootGroup.copy(entries = db.rootGroup.entries + extra))
        }
    }

    /** 把远端某条目的口令改成给定值（与本地改动构成同字段分叉 → 必须用户裁决）。 */
    private fun publishRemoteFieldChange(fx: Fixture, newPassword: String) {
        rewriteRemote(fx) { db ->
            val changed = db.rootGroup.entries.map { entry ->
                if (entry.id == fx.commonEntry.id) {
                    entry.copy(
                        fields = entry.fields +
                            (KdbxConstants.Fields.PASSWORD to ProtectedString(newPassword, true))
                    )
                } else {
                    entry
                }
            }
            db.copy(rootGroup = db.rootGroup.copy(entries = changed))
        }
    }

    private fun rewriteRemote(fx: Fixture, transform: (KdbxDatabase) -> KdbxDatabase) {
        val current = fx.provider.remote.getValue(fx.remotePath).first
        val loaded = fx.openRemote(current)
        val updated = transform(loaded)
        val bytes = fx.writeRemote(updated)
        updated.clearSensitiveData()
        fx.provider.remote[fx.remotePath] = bytes to "etag-remote-${System.nanoTime()}"
    }

    // ================= 正确性不变量（「本地落盘版本与上传版本对应」判据） =================

    /**
     * 本仓对「本地落盘版本 == 上传版本」的判据（三层，逐层更强）：
     * 1. **同一实例**：上传收到的数组与落盘收到的数组是同一引用 ⇒ 上传借用的是同一份预备密文，
     *    连一次整库拷贝都没有（比字节相等更强）。
     * 2. **逐字节相同**：KDBX4 每次保存重生成 masterSeed / IV / KDF salt，二次序列化必然不同字节
     *    ⇒ 字节相等即同一版本。
     * 3. **持久化基线一致**：落盘确认后登记的整库密文基线 == 本次写出字节 ⇒ 后续漂移合并的共同
     *    底版就是这一版（不会拿旧版本冒充最新已提交版本）。
     */
    private suspend fun verifyVersionCorrespondence(fx: Fixture) {
        val uploaded = fx.provider.lastUploaded
        if (uploaded == null) {
            fail("本场景未发生任何上传，版本对应判据不成立")
            return
        }
        val written = fx.writer.lastWritten
        if (written == null) {
            fail("本场景未发生任何本地落盘，版本对应判据不成立")
            return
        }
        assertArrayEquals(
            "（判据 2）本地落盘字节必须与上传字节逐字节相同——KDBX4 每次保存重生成 " +
                "masterSeed/IV/KDF salt，二次序列化不可能同字节",
            written,
            uploaded
        )
        assertTrue(
            "（判据 1）上传与落盘必须是同一份序列化产物的借用（同一数组实例），" +
                "实际 identity=${fx.writer.lastWrittenIdentity} vs ${fx.provider.lastUploadedIdentity}",
            fx.writer.lastWrittenIdentity == fx.provider.lastUploadedIdentity
        )
        val onDisk = fx.vaultFile.readBytes()
        try {
            assertEquals(
                "正式文件内容必须等于本次写出字节",
                fx.writer.lastWrittenDigest,
                sha256Hex(onDisk)
            )
        } finally {
            onDisk.fill(0)
        }
        val baseline = fx.session.persistedBaselineSnapshot()
        assertNotNull("落盘确认成功后必须登记持久化基线", baseline)
        try {
            assertEquals(
                "（判据 3）持久化基线必须等于本次写出字节",
                fx.writer.lastWrittenDigest,
                sha256Hex(baseline!!.bytes)
            )
        } finally {
            baseline!!.wipe()
        }
    }

    // ================= 输出 =================

    private fun reportScenario(fx: Fixture, scenario: String, totalMs: Long, libraryBytes: Long) {
        val records = fx.census.snapshot()
        val products = fx.census.distinctProducts()
        emit(
            "${PREFIX}场景=$scenario|库体积字节=$libraryBytes|整库序列化次数(去重密文)=${products.size}|" +
                "落盘写出次数=${fx.census.countOf(SINK_LOCAL_WRITE)}|上传调用次数=${fx.census.countOf(SINK_UPLOAD)}|" +
                "缓存写入次数=${fx.census.countOf(SINK_CACHE_WRITE)}|场景耗时ms=$totalMs"
        )
        val sinksByProduct = products.joinToString(",") { product ->
            val sinks = records.filter { it.digest == product.digest }.map { it.sink }.distinct()
            "${product.digest.take(8)}:${product.size}B=[${sinks.joinToString("/")}]"
        }
        emit("${PREFIX}场景=$scenario|序列化产物明细=$sinksByProduct")
    }

    /** 单次整库序列化 / 单次整库解析的设备基准，用于把「次数」换算成可核对的时间量级。 */
    private suspend fun reportCalibration(fx: Fixture) {
        val serializeStartedAt = System.nanoTime()
        val bytes = fx.session.exportToBytes().getOrNull()
        val serializeMs = (System.nanoTime() - serializeStartedAt) / 1_000_000.0
        if (bytes == null) {
            fail("基准：整库序列化失败")
            return
        }
        try {
            val parseStartedAt = System.nanoTime()
            val parsed = fx.codec.parseKdbxBytes(bytes)
            val parseMs = (System.nanoTime() - parseStartedAt) / 1_000_000.0
            if (parsed == null) {
                fail("基准：整库解析失败")
                return
            }
            parsed.clearSensitiveData()
            emit(
                "${PREFIX}基准|单次整库序列化ms=${fmt(serializeMs)}|单次整库解析ms=${fmt(parseMs)}|" +
                    "库体积字节=${bytes.size}|KDF=AES-KDF(useArgon2=false)"
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    private fun fmt(value: Double) = String.format("%.1f", value)

    // ================= 合成数据 =================

    private fun seedEntries(): List<KdbxEntry> {
        val random = Random(SEED_RANDOM)
        return List(ENTRY_COUNT) { index ->
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = linkedMapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("规模条目-$index", false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Seed#pass-$index", true),
                    // 高熵备注：避免 GZip 把合成库压成几百 KiB，读数才反映真实整库成本
                    KdbxConstants.Fields.NOTES to ProtectedString(randomText(random, NOTES_CHARS), false)
                )
            )
        }
    }

    private fun newEntry(title: String, password: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(password, true)
        )
    )

    private fun randomText(random: Random, chars: Int): String {
        val bytes = ByteArray(chars * 3 / 4)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes).take(chars)
    }
}

// ================= 文件级常量与工具（放文件作用域，避免嵌套类访问外层 companion 私有成员的歧义） =================

private const val TAG = "ENG31Probe"
private const val PREFIX = "PERF-PROBE|ENG31|"

private const val SINK_LOCAL_WRITE = "localWrite"
private const val SINK_UPLOAD = "upload"
private const val SINK_CACHE_WRITE = "cacheWrite"

/** 合成库规模：600 条 × 4 KiB 高熵备注 ≈ 2.4 MB 明文，密文约 1.8–2.2 MiB。 */
private const val ENTRY_COUNT = 600
private const val NOTES_CHARS = 4 * 1024
private const val SEED_RANDOM = 0x31A1C0DEL

private const val LOCAL_TAG = "ENG31-L"
private const val REMOTE_TAG = "ENG31-R"

/** 合成凭据（虚构值，非任何真实口令）；每个夹具各取一份副本。 */
private val TEST_PASSWORD = "Eng31-Synthetic-Probe#2026".toCharArray()

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
