package com.keepasskey.app.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream

/**
 * ISSUE-P3-302 AC①：大库下拉刷新的**真机量级读数**（§274 AC④ 显式残余项的设备面）。
 *
 * ## 为什么这一层只能在设备上拿
 *
 * `SyncAssemblyOffMainThreadTest`（JVM）证的是**调度归属**（装配段四类重活不在主线程），
 * 它按定义拿不到**量级**：宿主既没有真实的 AndroidKeyStore（凭据探针是恒等桩），也没有
 * 真机文件系统的 `fd.sync()` 与 ICU/ART 运行时。本用例把同一入口搬到真机，并把恒等桩换成
 * **真实凭据链**（`customDecryptor` 不注入 ⇒ Keystore 解密真的执行），读数即 AC① 所要的四项：
 * 装配段耗时、主线程最长连续阻塞、掉帧、同步总时长。
 *
 * ## 与整改前口径的对应
 *
 * §274 的整改依据「大库下数百毫秒至秒级」是**推理**（同一主线程上做 Keystore 解密 + 整库密文读 +
 * 全库逐字段比较 + 整库写 + `fd.sync()`）。本用例另在观察窗之外**单独直调**
 * [KdbxContentComparator.changed]，拿下的正是「整改前该步独占主线程」的同一工作量级，
 * 与观察窗内的主线程读数构成一组对照。
 *
 * ## 网络面口径（如实声明，勿据此推定已证）
 *
 * 被测轮置 `isOfflineMode`（引擎在装配段**之后**早退，`SyncEngine.openRemote` 的离线分支），
 * 故本用例的「同步总时长」**不含网络往返**；含网络往返的总时长与 `ISSUE-P3-300`（真实 DAV 矩阵）
 * 一并留在服务器环境可得时另测。
 *
 * ## 判据（AC② 的「推翻」口径）
 *
 * ① 装配段三类重活探针（整库缓存读 / 基准内容读 / 原子写）必须被观测到、归因于装配段、
 *    且**不在主线程**上——设备侧复证 `ISSUE-P2-277`；
 * ② 观察窗内主线程最长连续阻塞 **< 1 秒**（AC 明示「仍见秒级主线程阻塞」即为推翻）；
 * ③ 大库确实被比较出差异（`changed` 为真），否则「量级」读数为空转。
 * 读数一律 `Log` + 落盘到夹具目录，**先落盘再断言**，失败时证据仍可读。
 */
@RunWith(AndroidJUnit4::class)
class SyncAssemblyScaleDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val readings = StringBuilder()

    /** 探针观测通道：由生产代码在装配段内回调写入（`var` 通道，非本地可求值）。 */
    private val records = mutableListOf<ProbeRecord>()

    private val stall = MainThreadStallWatcher()

    private data class ProbeRecord(
        val probe: String,
        val durationMs: Long,
        val threadName: String,
        val fromAssemblySegment: Boolean
    )

    @Test
    fun `大库装配段真机量级读数与主线程无秒级阻塞（ISSUE-P3-302 AC①）`() {
        val outcome = runBlocking { measureAssemblyOnMainThread() }

        report("周期结果", "$outcome")
        report("主线程最长连续阻塞", "${stall.maxGapMs} ms（tick 名义 ${stall.tickMs} ms，读数含调度开销）")
        report("主线程观察窗内 tick 数（名义间隔 ${stall.tickMs} ms）", "${stall.ticks}")
        report("vsync 可用性", stall.vsyncAvailable)
        report("帧面读数", stall.frameReadings())
        for (record in records.sortedByDescending { it.durationMs }) {
            report("装配段探针", "${record.probe} 用时 ${record.durationMs} ms 线程=${record.threadName} 归因装配段=${record.fromAssemblySegment}")
        }
        dumpReadingsToDisk()

        val required = listOf(PROBE_CACHE_READ, PROBE_BASE_READ, PROBE_CACHE_WRITE)
        for (probe in required) {
            assertTrue(
                "探针「$probe」未被观测到或不属装配段，量级读数对该类不成立: $records",
                records.any { it.probe == probe && it.fromAssemblySegment }
            )
            assertTrue(
                "装配段重活「$probe」落在主线程上（ISSUE-P2-277 真机回归）: " +
                    records.filter { it.probe == probe && it.threadName == stall.mainThreadName },
                records.none { it.probe == probe && it.threadName == stall.mainThreadName }
            )
        }
        assertTrue(
            "主线程出现秒级连续阻塞，§274 的「阻塞已移走」被推翻: ${stall.maxGapMs} ms",
            stall.maxGapMs < MAX_MAIN_THREAD_STALL_MS
        )
    }

    /** 在**主线程**上驱动一次同步周期（与前台下拉刷新同一 `Main.immediate` 入口），同时泵主线程观察窗。 */
    private suspend fun measureAssemblyOnMainThread(): SyncOutcome {
        report("设备", "${System.getProperty("ro.product.model") ?: android.os.Build.MODEL} / " +
            "Android SDK ${android.os.Build.VERSION.SDK_INT} / ${android.os.Build.BRAND}")

        val password = "ScaleP3302#2026".toCharArray()
        val fixtureDir = context.getDir(FIXTURE_DIR_NAME, Context.MODE_PRIVATE).apply {
            listFiles()?.forEach { it.delete() }
        }
        File(context.cacheDir, SyncCache.CACHE_DIR_NAME).deleteRecursively()

        val databaseSession = DatabaseSession()
        assertTrue(
            "夹具建库失败",
            databaseSession.create(
                file = File(fixtureDir, VAULT_FILE_NAME),
                name = "ScaleP3302",
                passwordChars = password,
                useArgon2 = false
            ) is KdbxResult.Success
        )

        // ── 大库夹具：万级条目 + 一个超 1 MiB 落盘阈值的附件 + 每 50 条一份历史快照 ──
        val seeded = seedLargeDatabase(databaseSession)
        report("夹具规模", "条目 ${seeded.first}（含历史快照 ${seeded.second} 份）")

        val debugLog = DebugLogBuffer()
        val session = SyncSessionState()
        val preferences = SyncPreferences(debugLog, null)
        val credentialsStore = SyncCredentialsStore(
            context,
            // 真机面关键差异：JVM 守卫传 null + 恒等加解密桩，本用例注入**真实 KeystoreManager**，
            // 让凭据封印/解除封印真的走 AndroidKeyStore（`keystoreManager == null` 时
            // `SyncCredentialSealer.encrypt` 直接静默返回 null，JVM 侧从未暴露这一点）。
            KeystoreManager(context, debugLog),
            debugLog
        )
        val codec = SyncDatabaseCodec(databaseSession, debugLog)
        val cycle = SyncCycleRunner(
            context = context,
            databaseSession = databaseSession,
            session = session,
            providerResolver = SyncProviderResolver(credentialsStore, preferences, debugLog),
            codec = codec,
            conflicts = SyncConflictController(databaseSession, codec, SCALE_STRINGS, session, debugLog),
            changes = SyncContentChangeDetector(codec, session),
            preferences = preferences,
            strings = SCALE_STRINGS
        )
        cycle.syncCacheFactory = { dir -> TimingCache(dir) }

        // ── 凭据链走**真实 AndroidKeyStore**（JVM 守卫用恒等桩，故该步量级此前从未有读数）──
        assertTrue(
            "预置 WebDAV 凭据失败（Keystore 封印未通）",
            credentialsStore.saveWebDavConfig(
                // 必须是公网 https 端点：`WebDavSyncProvider` 构造期的 SSRF 防护拒内网 / 保留网段；
                // 本用例两轮均置测试 Provider / 离线模式，构造之后不触网。
                url = "https://dav.example.com/dav",
                username = "scale-p3302",
                password = "placeholder#1".toCharArray(),
                remotePath = REMOTE_PATH
            )
        )
        val keystoreDecryptMs = elapsedMs {
            val loaded = credentialsStore.loadWebDavConfig()
            assertTrue("真实 Keystore 往返未取回凭据，凭据读取量的读数为空转", loaded != null)
            // 借用语义：`loadWebDavConfig` 返回的 CharArray 归调用方清零（敏感数据铁律）
            loaded?.password?.fill('0')
        }
        report("凭据解封（Keystore AES-GCM 解密，直调）", "$keystoreDecryptMs ms")

        // ── 第一轮（不计入观察窗）：进程内 Provider 建立远端基线与会话内存基线，全程不触网 ──
        session.testSyncProvider = InMemoryProvider()
        val baseline = cycle.runSyncCycle()
        assertTrue("首轮应在远端建立基线: $baseline", baseline is SyncOutcome.UploadedLocal)
        val baselineReference = session.lastSyncedDb
        assertTrue("首轮后应已建立会话内存基线（深比较的前提）", baselineReference != null)

        // ── 首轮之后再追加一条并落盘：活动树成为新实例，被测轮的深比较才会走完全库 ──
        report("追加条目并保存（非被测段）", "${appendEntryAndSave(databaseSession)} ms")

        val reference = baselineReference!!
        val current = databaseSession.databaseFlow.value ?: error("追加后内存树为空")
        assertTrue(
            "追加后活动树仍与会话基线为同一实例，装配段深比较会走同一性短路使量级读数失真",
            current !== reference
        )

        // ── 观察窗之外单独直调全库深比较：整改前该步独占主线程，此读数即其量级对照 ──
        val deepCompareMs = elapsedMs {
            val changed = KdbxContentComparator.changed(current, reference)
            assertTrue("大库深比较应判为有变化（否则装配段走了早退分支，量级读数为空转）", changed)
        }
        report("全库逐字段深比较（直调，整改前主线程独占量级对照）", "$deepCompareMs ms")

        // ── 整库序列化 + 加密单独直调：与被测轮装配段内同一个 `codec.serializeLocalDatabase` 同函数，
        //    用于把观察窗内的秒级成本归因到该步（深比较实测只占毫秒级）──
        val serializeMs = elapsedMs {
            val bytes = codec.serializeLocalDatabase(current)
            assertTrue("整库序列化未产出字节，归因读数为空转", bytes != null && bytes.isNotEmpty())
        }
        report("整库序列化 + 加密（直调，装配段内同函数）", "$serializeMs ms")

        // ── 被测轮：撤测试 Provider ⇒ 走真实凭据链解析 Provider；置离线 ⇒ 装配段之后早退 ──
        session.testSyncProvider = null
        session.isOfflineMode = true
        records.clear()
        stall.reset()

        var outcome: SyncOutcome? = null
        withContext(Dispatchers.Main) {
            stall.start()
            val cycleJob = launch(Dispatchers.Main.immediate) { outcome = cycle.runSyncCycle() }
            while (cycleJob.isActive) {
                stall.pump()
                delay(stall.tickMs)
            }
            stall.stop()
        }
        report("装配段三类探针耗时", records.joinToString("; ") { "${it.probe}=${it.durationMs}ms" })
        return outcome ?: error("被测轮未产出周期结果")
    }

    /** 用生产写入管线把万级条目灌进会话并落盘，返回（条目数，历史快照数）。 */
    private suspend fun seedLargeDatabase(databaseSession: DatabaseSession): Pair<Int, Int> {
        val base = databaseSession.databaseFlow.value ?: error("建库后内存树为空")
        var historyCount = 0
        val bigAttachment = ByteArray(SPOILLED_ATTACHMENT_BYTES) { (it % 251).toByte() }
        val entries = List(ENTRY_COUNT) { index ->
            val fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("条目-$index", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("user$index", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Scale#pass-$index", false),
                KdbxConstants.Fields.URL to ProtectedString("https://scale.example.test/$index", false)
            )
            val attachments = if (index == 0) {
                listOf(KdbxAttachment(name = "scale-big.bin", data = bigAttachment))
            } else {
                emptyList()
            }
            val history = if (index % HISTORY_EVERY != 0) {
                emptyList()
            } else {
                historyCount++
                listOf(
                    KdbxEntry(
                        id = KdbxUuid.random(),
                        fields = fields + (KdbxConstants.Fields.TITLE to ProtectedString("旧标题-$index", false))
                    )
                )
            }
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = fields,
                customFields = listOf(KdbxCustomField("规模备注", ProtectedString("备注值-$index", false))),
                history = history,
                attachments = attachments
            )
        }
        databaseSession.setDatabaseForTesting(base.copy(rootGroup = base.rootGroup.copy(entries = entries)))
        val seedSaveMs = elapsedMs {
            assertTrue("夹具落盘失败", databaseSession.save() is KdbxResult.Success)
        }
        report("夹具写出（整库序列化 + 加密 + 原子写，非被测段）", "$seedSaveMs ms")
        return Pair(entries.size, historyCount)
    }

    /**
     * 首轮周期**之后**追加一条并落盘：使活动树成为新实例，装配段的
     * `resolveLocalContentChanged(活动树, lastSyncedDb)` 才会真的走完全库。
     *
     * 追加若发生在首轮之前，`lastSyncedDb` 记录的就是同一实例，深比较按同一性短路返回
     * `false`（首轮实测即栽在此处），量级读数会全部退化为「无变化」分支。
     */
    private suspend fun appendEntryAndSave(databaseSession: DatabaseSession): Long = elapsedMs {
        databaseSession.saveEntry(
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("被测轮新增", false))
            )
        )
        assertTrue("追加条目后保存失败", databaseSession.save() is KdbxResult.Success)
    }

    /** 整库缓存读 / 基准内容读 / 原子写的记录型替身，兼记**自身耗时**与执行线程。 */
    private inner class TimingCache(dir: File) : SyncCache(dir) {

        override fun readCache(remotePath: String): ByteArray? {
            var value: ByteArray? = null
            record(PROBE_CACHE_READ) { value = super.readCache(remotePath) }
            return value
        }

        override fun readBaseContent(remotePath: String): ByteArray? {
            var value: ByteArray? = null
            record(PROBE_BASE_READ) { value = super.readBaseContent(remotePath) }
            return value
        }

        override fun writeCache(
            remotePath: String,
            data: ByteArray,
            updateVersion: Boolean,
            precomputedDigest: String?
        ): String {
            var digest = ""
            record(PROBE_CACHE_WRITE) {
                digest = super.writeCache(remotePath, data, updateVersion, precomputedDigest)
            }
            return digest
        }
    }

    private fun record(probe: String, work: () -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        work()
        records += ProbeRecord(
            probe = probe,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            threadName = Thread.currentThread().name,
            fromAssemblySegment = Thread.currentThread().stackTrace.any {
                it.className == ASSEMBLY_SEGMENT_CLASS
            }
        )
    }

    private fun report(key: String, value: String) {
        readings.append(key).append(" = ").append(value).append('\n')
        Log.i(TAG, "$key = $value")
    }

    private fun dumpReadingsToDisk() {
        runCatching {
            File(context.getDir(FIXTURE_DIR_NAME, Context.MODE_PRIVATE), READINGS_FILE_NAME)
                .writeText(readings.toString())
        }.onFailure { Log.w(TAG, "读数落盘失败，仅 logcat 可读: ${it.message}") }
    }

    private inline fun elapsedMs(block: () -> Unit): Long {
        val startedAt = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - startedAt
    }

    /**
     * 主线程连续阻塞观察窗：在主线程上以 [tickMs] 为名义间隔自我泵起，两次泵之间的**实测间隔**
     * 即主线程被占用的时长（含调度开销，故为上限口径）。
     *
     * 帧面另走一条**自续 `Choreographer` 回调链**（不占据泵循环，否则 vsync 不投递时的超时会
     * 直接灌进主线程读数）：真机在 instrument 无可见窗口时 vsync 可能不投递，故先标定，
     * 不可用时如实登记并只保留 tick 推算口径。
     */
    private class MainThreadStallWatcher {

        val tickMs = 5L
        var maxGapMs = 0L
            private set
        var ticks = 0
            private set
        var vsyncAvailable = "未标定"
            private set
        var mainThreadName = ""
            private set

        private var lastPumpAt = 0L
        private val frameTimes = mutableListOf<Long>()
        private var sampling = false

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!sampling) return
                frameTimes += frameTimeNanos / 1_000_000L
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun reset() {
            maxGapMs = 0
            ticks = 0
            lastPumpAt = 0
            frameTimes.clear()
        }

        suspend fun start() {
            mainThreadName = Thread.currentThread().name
            vsyncAvailable = if (awaitFrameTime() == null) {
                "不可用（vsync 未投递 ⇒ 本设备不给掉帧读数，不以 tick 推算冒充）"
            } else {
                sampling = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
                "可用"
            }
            lastPumpAt = SystemClock.uptimeMillis()
        }

        /** 让出主线程一次并结算实测间隔；纯观察通道，不读取任何生产数据。 */
        suspend fun pump() {
            delay(tickMs)
            val now = SystemClock.uptimeMillis()
            val gap = now - lastPumpAt
            lastPumpAt = now
            ticks++
            if (gap > maxGapMs) maxGapMs = gap
        }

        fun stop() {
            sampling = false
        }

        /** 实测 vsync 帧数与按帧间隔推算的掉帧数（每 `16.7 ms` 一帧）。 */
        fun frameReadings(): String {
            if (frameTimes.size < 2) return "帧数=${frameTimes.size}（样本不足）"
            val dropped = frameTimes.zipWithNext { previous, next ->
                ((next - previous) / DROP_FRAME_INTERVAL_MS - 1).coerceAtLeast(0L)
            }.sum()
            return "帧数=${frameTimes.size} 帧间隔合计=${frameTimes.last() - frameTimes.first()} ms 掉帧=$dropped"
        }


        private suspend fun awaitFrameTime(): Long? = withTimeoutOrNull(VSYNC_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
                    continuation.resume(frameTimeNanos / 1_000_000L)
                }
            }
        }
    }

    /** 进程内假 Provider（无网络；与 `SyncAssemblyOffMainThreadTest` 同口径）。 */
    private class InMemoryProvider : SyncProvider {

        private val remote = mutableMapOf<String, Pair<ByteArray, String>>()

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> {
            val item = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("远端不存在: $remotePath"))
            return Result.success(
                RemoteFileMetadata(
                    path = remotePath,
                    contentLength = item.first.size.toLong(),
                    lastModifiedMillis = System.currentTimeMillis(),
                    etag = item.second
                )
            )
        }

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> {
            val item = remote[remotePath]
                ?: return Result.failure(SyncException.FileNotFound("远端不存在: $remotePath"))
            sink.write(item.first)
            return Result.success(Unit)
        }

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> {
            val etag = "etag_${data.size}"
            remote[remotePath] = Pair(data, etag)
            return Result.success(etag)
        }

        override suspend fun delete(remotePath: String): Result<Unit> {
            remote.remove(remotePath)
            return Result.success(Unit)
        }
    }

    private companion object {
        const val TAG = "P3302Scale"
        const val FIXTURE_DIR_NAME = "p3302scale"
        const val VAULT_FILE_NAME = "scale_p3302.kdbx"
        const val READINGS_FILE_NAME = "readings.txt"
        const val REMOTE_PATH = "/scale/p3302-scale.kdbx"

        /** AC① 的「万级条目」口径。 */
        const val ENTRY_COUNT = 10_000

        /** 每 N 条挂一份历史快照（AC① 的「历史快照」项）。 */
        const val HISTORY_EVERY = 50

        /** 严格大于 `BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`（1 MiB），确保附件走落盘分支。 */
        const val SPOILLED_ATTACHMENT_BYTES = 2 * 1024 * 1024

        /** AC② 的「推翻」判据：仍见**秒级**主线程阻塞即推翻 §274。 */
        const val MAX_MAIN_THREAD_STALL_MS = 1_000L

        const val DROP_FRAME_INTERVAL_MS = 16L
        /** vsync 标定等待上限：超时可判定本机在 instrument 无窗口场景下拿不到帧。 */
        const val VSYNC_TIMEOUT_MS = 2_000L

        const val PROBE_CACHE_READ = "整库缓存读 readCache"
        const val PROBE_BASE_READ = "基准内容读 readBaseContent"
        const val PROBE_CACHE_WRITE = "原子写 writeCache"

        /** 装配段所在文件 `SyncCycleSetup.kt` 的产物类名（归因判据，与 JVM 守卫同一口径）。 */
        const val ASSEMBLY_SEGMENT_CLASS = "com.keepasskey.app.sync.SyncCycleSetupKt"

        val SCALE_STRINGS = StringsProvider { _, _ -> "" }
    }
}
