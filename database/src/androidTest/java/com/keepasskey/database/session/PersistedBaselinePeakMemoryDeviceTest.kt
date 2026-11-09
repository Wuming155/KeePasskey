package com.keepasskey.database.session

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * `ENG-20261028-18`：持久化基线的**峰值内存读数**（评估测量，非回归闸门）。
 *
 * ## 交付范围
 *
 * 关闭条件要求「测量大库打开、保存和漂移快照的峰值」。本用例给三处读数，另加两处与
 * 「已有旧基线的替换」直接相关的读数：
 *
 * | 测量点 | 为什么是这一处 |
 * |---|---|
 * | ① 打开会话 | `SessionOpener.openStream` 在解析成功后捕获源文件字节并登记基线，这一段同时产生「捕获缓冲」「capturedBytes 副本」「基线克隆」三份整库密文 |
 * | ② 一次保存 | `SessionPersistence.saveLocked`：序列化缓冲 + `toByteArray()` 副本，基线走 `retainPersistedBaselineOwned` 所有权移交（零克隆） |
 * | ③ 漂移快照读取 | `DatabaseSession.persistedBaselineSnapshot()` → `SessionCore.persistedBaselineClone()`：调用方拿到的是一份整库克隆 |
 * | ④ 已有旧基线时的保存 | 替换路径：旧基线仍然存活时再产生新序列化产物，是「替换」这一步的真实内存形状 |
 * | ⑤ 登记失败收口 | `invalidatePersistedBaselineAfterFailedRetain()`（= `clearPersistedBaseline()`）是留存失败的唯一终点；它不分配，读数用于证明「失败收口不产生新的整库副本」 |
 *
 * 「同时存活几份整库密文」只有**代码路径推断**（见 [reportWholeLibraryCopyCounts]），
 * 本件没有逐缓冲打点，故不得当作设备读数引用。
 *
 * ## 库体积基线（首轮实跑在此失败后的修正）
 *
 * 保存会新增条目 ⇒ 正式文件与持久化基线**都会变大**。因此：
 * - **每个测量点各自记录「该点库体积」**（打开前 / 保存后 / 替换后），`峰值/库体积` 的分母取该点值；
 * - 基线/快照的校验不与某个固定常量比对，而是与**该点当前已落盘文件**做**长度 + SHA-256 摘要**
 *   双核对（打开点对源文件、保存点对本次写出文件、快照点对当前文件）；
 * - 于是「基线 == 当前已提交版本」这一语义在每个点都被逐字节验证，而不是靠体积巧合。
 *
 * ⑤ 的**语义**（旧基线就地清零、快照置空、修订前移）已由确定性用例覆盖，不在本件重复断言：
 * 见 `database/src/test/java/com/keepasskey/database/session/SessionPersistCommitBoundaryTest.kt`
 * 的「基线登记失败后旧基线必须作废且修订前移」，以及 `PersistedBaselineRealSizeBoundaryTest.kt`
 * 的「超限捕获不得把旧基线留成可信当前版本」。本件只断言「这一处读数不是空转」（执行后快照为 null）。
 *
 * ## 采样口径（如实说明，**不**以 Runtime 前后差冒充峰值）
 *
 * - `usedHeap` = `Runtime.totalMemory() - freeMemory()`：这是**已从堆里分配出去**的字节数，
 *   包含尚未被 GC 回收的垃圾，因此是「存活 + 垃圾」的上界口径，不是精确活对象大小；
 * - `peak` 由一条**专用采样线程**按 [SAMPLE_INTERVAL_MS] 轮询取最大值得出：介于两次采样之间的
 *   瞬时尖峰会被漏掉，故读数是**峰值下界**；两处误差方向相反，不做相互抵消的宣称；
 * - 每个测量点前执行 `System.gc()` 两次（间隔 180 ms）取得稳定基线；`System.gc()` 只是请求，
 *   不等于强制回收，故基线本身也有抖动；
 * - 另打印 `Debug.getNativeHeapAllocatedSize()` 增量作对照：本仓整库密文/明文树都在 Java 堆上，
 *   原生堆读数预期接近 0，用于排除「被原生库吃掉」的误读；
 * - 同时打印 `Runtime.maxMemory()`，比值读数的分母含库体积，分子含明文树，**两者不可互相抵消**，
 *   故比值只作量级对照，不作精确倍数宣称。
 *
 * ## 输出
 *
 * `PERF-PROBE|ENG18|` 前缀，`Log.i` + `println` 双路；**不做阈值断言**。
 * 设备：`adt`/CI 实跑；库规模目标 4–16 MiB（见 [ENTRY_COUNT] × [NOTES_CHARS]，高熵备注避免 GZip 压扁）。
 */
@RunWith(AndroidJUnit4::class)
class PersistedBaselinePeakMemoryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 大库打开保存与漂移快照的峰值内存读数() = runBlocking<Unit> {
        val dir = File(context.cacheDir, "eng18-${System.nanoTime()}").apply { mkdirs() }
        val vaultFile = File(dir, "eng18.kdbx")
        val session = DatabaseSession().apply { createBackupBeforeSave = false }
        try {
            buildVault(vaultFile)
            // 每个测量点各自记录「该点的库体积」——保存会新增条目，跑完之后文件必然变大，
            // 因此不能用打开前的体积去校验后续阶段的基线/快照（首轮实跑曾在此失败）。
            val openLibraryBytes = vaultFile.length()
            assertTrue(
                "前置：合成库体积必须足够大（实读 $openLibraryBytes 字节），否则峰值读数没有意义",
                openLibraryBytes >= 2L * 1024 * 1024
            )

            emit(
                "${PREFIX}口径|采样间隔ms=${SAMPLE_INTERVAL_MS}|usedHeap=totalMemory-freeMemory|" +
                    "nativeHeap=Debug.getNativeHeapAllocatedSize|maxMemory字节=${Runtime.getRuntime().maxMemory()}|" +
                    "峰值=采样线程最大值(下界)|基线=System.gc()两次后取值|含未回收垃圾|" +
                    "比值分母=该测量点当时的库体积(保存会改变库体积，故逐点记录)"
            )
            emit(
                "${PREFIX}规模|打开前库体积字节=$openLibraryBytes|条目数=$ENTRY_COUNT|备注字符每条=$NOTES_CHARS|" +
                    "KDF=AES-KDF(useArgon2=false)"
            )

            // ---------- ① 打开会话（含基线捕获与登记） ----------
            // 该点库体积 = 打开前的源文件；基线必须与源文件逐字节一致（摘要核对）
            val sourceDigest = digestOfFile(vaultFile)
            val openPhase = measure("①打开会话(含基线捕获与登记)") {
                val result = session.open(vaultFile, PASSWORD)
                assertTrue("打开失败", result is KdbxResult.Success)
            }
            reportPhase(openPhase, openLibraryBytes)
            val afterOpen = session.persistedBaselineSnapshot()
                ?: error("打开成功后必须已登记持久化基线")
            assertTrue(
                "打开后基线长度必须等于源文件字节（实读 ${afterOpen.bytes.size} vs $openLibraryBytes）",
                afterOpen.bytes.size.toLong() == openLibraryBytes
            )
            assertEquals(
                "打开后基线必须与源文件逐字节一致（摘要核对）",
                sourceDigest,
                digestOf(afterOpen.bytes)
            )
            val revisionAfterOpen = afterOpen.revision
            afterOpen.wipe()

            // ---------- ② 一次保存 ----------
            val savePhase = measure("②一次保存") {
                session.saveEntry(
                    KdbxEntry(
                        id = KdbxUuid.random(),
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("峰值探针-保存新增", false)
                        )
                    )
                )
                assertTrue("保存失败", session.save() is KdbxResult.Success)
            }
            // 该点库体积 = 保存后的正式文件（新增条目已写入，体积较打开前变大）
            val libraryBytesAfterSave = vaultFile.length()
            reportPhase(savePhase, libraryBytesAfterSave)
            val saveFileDigest = digestOfFile(vaultFile)
            val afterSave = session.persistedBaselineSnapshot()
                ?: error("保存成功后必须已登记持久化基线")
            assertTrue(
                "保存后基线修订必须前移（${afterSave.revision} > $revisionAfterOpen）",
                afterSave.revision > revisionAfterOpen
            )
            assertTrue(
                "保存后基线长度必须等于保存后的正式文件（实读 ${afterSave.bytes.size} vs $libraryBytesAfterSave）",
                afterSave.bytes.size.toLong() == libraryBytesAfterSave
            )
            assertEquals(
                "保存后基线必须等于本次写出的正式文件（摘要核对）",
                saveFileDigest,
                digestOf(afterSave.bytes)
            )
            val revisionAfterSave = afterSave.revision
            afterSave.wipe()

            // ---------- ③ 漂移快照读取 ----------
            // 该点库体积 = 当前正式文件；快照必须与它逐字节一致（漂移合并的共同底版就是这一版）
            val libraryBytesAtSnapshot = vaultFile.length()
            val snapshotExpectedDigest = digestOfFile(vaultFile)
            var snapshotBytesRead = 0L
            var snapshotDigest = ""
            val snapshotPhase = measure("③漂移快照读取(persistedBaselineSnapshot)") {
                val snapshot = session.persistedBaselineSnapshot()
                    ?: error("漂移快照读取必须返回基线（调用方拥有，用毕 wipe）")
                snapshotBytesRead = snapshot.bytes.size.toLong()
                snapshotDigest = digestOf(snapshot.bytes)
                snapshot.wipe()
            }
            reportPhase(snapshotPhase, libraryBytesAtSnapshot)
            assertTrue(
                "快照长度必须等于该点库体积（实读 $snapshotBytesRead vs $libraryBytesAtSnapshot）",
                snapshotBytesRead == libraryBytesAtSnapshot
            )
            assertEquals(
                "快照必须与当前已落盘版本逐字节一致（摘要核对）",
                snapshotExpectedDigest,
                snapshotDigest
            )

            // ---------- ④ 已有旧基线时的保存（替换路径） ----------
            val replacePhase = measure("④已有旧基线时的保存(基线替换)") {
                session.saveEntry(
                    KdbxEntry(
                        id = KdbxUuid.random(),
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("峰值探针-替换新增", false)
                        )
                    )
                )
                assertTrue("替换保存失败", session.save() is KdbxResult.Success)
            }
            val libraryBytesAfterReplace = vaultFile.length()
            reportPhase(replacePhase, libraryBytesAfterReplace)
            val replaceFileDigest = digestOfFile(vaultFile)
            val afterReplace = session.persistedBaselineSnapshot()
                ?: error("替换保存后基线必须仍存在")
            assertTrue(
                "替换保存后基线修订必须再次前移（${afterReplace.revision} > $revisionAfterSave）",
                afterReplace.revision > revisionAfterSave
            )
            assertTrue(
                "替换后基线长度必须等于替换后的正式文件（实读 ${afterReplace.bytes.size} vs $libraryBytesAfterReplace）",
                afterReplace.bytes.size.toLong() == libraryBytesAfterReplace
            )
            assertEquals(
                "替换后基线必须等于本次写出的正式文件（摘要核对）",
                replaceFileDigest,
                digestOf(afterReplace.bytes)
            )
            afterReplace.wipe()

            // ---------- ⑤ 登记失败收口（不分配） ----------
            val invalidatePhase = measure("⑤基线登记失败收口(作废旧基线)") {
                session.clearPersistedBaselineForTesting()
            }
            reportPhase(invalidatePhase, libraryBytesAfterReplace)
            assertNull(
                "失败收口后不得留下旧基线（旧值对应上一持久化版本，冒充最新版本会让合并底版错误）",
                session.persistedBaselineSnapshot()
            )

            reportWholeLibraryCopyCounts()
        } finally {
            session.close()
            dir.deleteRecursively()
        }
    }

    // ================= 「瞬时整库副本数」推断（代码路径推导，非实测） =================

    /**
     * 三条路径上**同时存活**的整库密文副本数——**代码路径推断值，不是实测值**。
     *
     * 判定依据只有源码（谁在什么时候持有哪个缓冲），没有任何运行时观测参与：本件**没有**
     * 逐缓冲打点（那需要给生产代码加探针接口，本任务明确不做）。因此：
     * - 数字的可信度 = 「这段代码路径读对了吗」，而非「设备上确实同时存在几份」；
     * - 与上面的峰值读数只能做**量级对照**（同一量级即不自相矛盾），不能互相证明；
     * - 输出行里以 `来源=代码路径推断(非实测)` 显式标注，避免被下游当成设备读数引用。
     */
    private fun reportWholeLibraryCopyCounts() {
        emit(
            "${PREFIX}副本数|来源=代码路径推断(非实测)|路径=打开|推断同时存活整库密文副本=3|" +
                "依据=SessionOpener.openStream: 捕获流内部缓冲(Wipable 扩容期另有旧/新缓冲并存) " +
                "+ capturedBytes()=sink.toByteArray() 副本 + retainPersistedBaseline 的 bytes.clone()；" +
                "登记完成后仅 1 份留存"
        )
        emit(
            "${PREFIX}副本数|来源=代码路径推断(非实测)|路径=保存|推断同时存活整库密文副本=2|" +
                "依据=SessionPersistence.serializeToBytes: Wipable 缓冲 + toByteArray() 副本；" +
                "基线登记走 retainPersistedBaselineOwned(所有权移交，零克隆)；旧基线在替换时与新缓冲短暂并存"
        )
        emit(
            "${PREFIX}副本数|来源=代码路径推断(非实测)|路径=漂移快照|推断同时存活整库密文副本=2|" +
                "依据=DatabaseSession.persistedBaselineSnapshot -> SessionCore.persistedBaselineClone：" +
                "常驻基线 1 份 + 调用方持有的克隆 1 份（用毕必须 wipe）"
        )
    }

    // ================= 测量基建 =================

    private data class Phase(
        val label: String,
        val elapsedMs: Long,
        val beforeJava: Long,
        val afterJava: Long,
        val peakJava: Long,
        val peakNativeDelta: Long
    )

    private suspend fun measure(label: String, block: suspend () -> Unit): Phase {
        settleHeap()
        val beforeJava = usedJavaBytes()
        val beforeNative = usedNativeBytes()
        val sampler = HeapSampler().also { it.start() }
        val startedAt = System.nanoTime()
        var failure: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            failure = t
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val peakJava = sampler.stop()
        val afterJava = usedJavaBytes()
        failure?.let { throw it }
        return Phase(label, elapsedMs, beforeJava, afterJava, peakJava, sampler.peakNative.get() - beforeNative)
    }

    /**
     * @param libraryBytes **该测量点当时的库体积**（打开前 / 保存后 / 替换后各不相同），
     *   既作 `峰值/库体积` 的分母，也用于与同点的基线长度对照。
     */
    private fun reportPhase(phase: Phase, libraryBytes: Long) {
        val peakDelta = phase.peakJava - phase.beforeJava
        emit(
            "${PREFIX}测量点=${phase.label}|该点库体积字节=$libraryBytes|" +
                "基线字节=${phase.beforeJava}|采样峰值字节=${phase.peakJava}|峰值增量字节=$peakDelta|" +
                "峰值/该点库体积=${fmt(peakDelta.toDouble() / libraryBytes.toDouble())}x|" +
                "结束字节=${phase.afterJava}|结束增量字节=${phase.afterJava - phase.beforeJava}|" +
                "native峰值增量字节=${phase.peakNativeDelta}|耗时ms=${phase.elapsedMs}"
        )
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    private fun fmt(value: Double) = String.format("%.2f", value)

    // ================= 合成大库 =================

    private fun buildVault(file: File) {
        val random = Random(SEED)
        val entries = List(ENTRY_COUNT) { index ->
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = linkedMapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("内存规模条目-$index", false),
                    KdbxConstants.Fields.USER_NAME to ProtectedString("user-$index", false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Peak#pass-$index", true),
                    // 高熵备注：GZip 压不动，库体积才由内容决定（否则合成库只剩几百 KiB）
                    KdbxConstants.Fields.NOTES to ProtectedString(randomText(random, NOTES_CHARS), false)
                )
            )
        }
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "Eng18Peak",
            rootGroup = KdbxGroup(name = "Root", entries = entries)
        )
        file.outputStream().use { KdbxFile.save(it, db, PASSWORD) }
        db.clearSensitiveData()
    }

    private fun randomText(random: Random, chars: Int): String {
        val bytes = ByteArray(chars * 3 / 4)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes).take(chars)
    }

    private companion object {
        const val TAG = "ENG18Mem"
        const val PREFIX = "PERF-PROBE|ENG18|"

        /** 采样间隔：1 ms 量级；两次采样之间的尖峰会被漏掉（读数因此是峰值下界）。 */
        const val SAMPLE_INTERVAL_MS = 2L

        /** 1400 条 × 5 KiB 高熵备注 ≈ 7.2 MB 明文，AES-GZip 后约 5–6 MiB（落在 4–16 MiB 内）。 */
        const val ENTRY_COUNT = 1400
        const val NOTES_CHARS = 5 * 1024
        const val SEED = 0x18E18L

        /** 合成凭据（虚构值，非任何真实口令）。 */
        val PASSWORD = "{{SECRET_pk8n0h9k}}".toCharArray()
    }
}

// ================= 堆采样（文件作用域，避免嵌套类访问外层成员） =================

/** 整库字节的 SHA-256（只读，不产生额外整库副本；输出 32 字节摘要）。 */
private fun digestOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** 读文件算摘要后**立即清零**（整库密文不留在堆上等 GC）。 */
private fun digestOfFile(file: File): String {
    val bytes = file.readBytes()
    return try {
        digestOf(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun usedJavaBytes(): Long =
    Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

private fun usedNativeBytes(): Long = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)

/** 每点前取得稳定基线；`System.gc()` 只是请求，非强制回收。 */
private fun settleHeap() {
    repeat(2) {
        System.gc()
        try {
            Thread.sleep(180)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** 采样线程：轮询 usedHeap / nativeHeap 取最大值（峰值**下界**，见类 KDoc 的口径说明）。 */
private class HeapSampler {
    private val peakJava = AtomicLong()
    val peakNative = AtomicLong()
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        peakJava.set(usedJavaBytes())
        peakNative.set(usedNativeBytes())
        running = true
        thread = Thread({
            while (running) {
                val java = usedJavaBytes()
                if (java > peakJava.get()) peakJava.set(java)
                val native = usedNativeBytes()
                if (native > peakNative.get()) peakNative.set(native)
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "eng18-heap-sampler").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop(): Long {
        running = false
        thread?.interrupt()
        thread?.join(1_000)
        return peakJava.get()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 2L
    }
}
