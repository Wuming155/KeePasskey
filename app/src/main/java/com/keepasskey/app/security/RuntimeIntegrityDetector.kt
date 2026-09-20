package com.keepasskey.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行环境完整性探测组件（ISSUE-P2-08 / ZT-13）。
 *
 * 设计约束：
 * - **零第三方依赖**：仅使用官方 API（[Debug]、[ApplicationInfo.FLAG_DEBUGGABLE]）与文件探测，
 *   不引入 RootBeer 等外部库；
 * - **后台 IO 执行**：全部磁盘探测（root / Magisk 路径、`/proc/self/maps`、安装来源）在
 *   [Dispatchers.IO] 协程内完成，禁止主线程磁盘扫描；
 * - **判定与探测分离**：风险裁决由纯函数 [RuntimeIntegrityPolicy] 承担（JVM 可测），
 *   本类只负责采集信号并持有可观察快照；
 * - **fail-closed**：首次扫描完成前暴露 [IntegrityEnforcement.UNDETERMINED] 保守策略，
 *   敏感通道等待结果而非放行。
 *
 * @param context 允许为 null 仅用于纯 JVM 单元测试（此时探测退化为干净信号）；
 *   生产 DI 注入 @ApplicationContext。
 */
@Singleton
class RuntimeIntegrityDetector @Inject constructor(
    @ApplicationContext private val context: Context?,
    /** ISSUE-P3-83：实时 ptrace 探测（`/proc/self/status` 的 `TracerPid`） */
    private val tracedProcessProbe: TracedProcessProbe
) : RuntimeIntegrityGate {

    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val rescanInFlight = AtomicBoolean(false)

    /** ISSUE-P2-63：上次扫描完成时刻（毫秒）；0 = 尚未扫描。由后台周期重扫与显式 refresh 推进。 */
    @Volatile
    private var lastScanAtMillis: Long = 0L

    private val _report = MutableStateFlow(RuntimeIntegrityReport.UNDETERMINED)

    /** 完整性扫描快照（UI 风险提示与敏感通道策略的唯一数据源） */
    val report: StateFlow<RuntimeIntegrityReport> = _report.asStateFlow()

    init {
        // 单例首次构造即异步启动扫描（后台 IO，不阻塞注入方）
        start()
    }

    /**
     * 幂等启动**周期重扫**；可被宿主 Application 重复调用。
     *
     * ISSUE-P2-63：原实现只扫一次，导致「冷启动后附加注入框架」不产生任何实时信号——
     * 非 suspend 门控（生物识别快速解锁）读到的是启动态快照。现改为周期重扫（后台 IO），
     * 使注入信号在 [LIVE_RESCAN_INTERVAL_MS] 内进入快照。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scanScope.launch {
            while (true) {
                runCatching { refresh() }
                delay(LIVE_RESCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * ISSUE-P2-63：非 suspend 门控裁决。
     *
     * 与旧实现的差异：
     * 1. **不再把钩子信号硬编码为 false**——快照内的钩子信号即最近一次（周期）重扫结果，
     *    显式并入实时升级，使「启动后附加 Frida」在重扫周期内即被捕获；
     * 2. **快照陈旧即 fail-closed**——超过 [SNAPSHOT_STALE_AFTER_MS] 未重扫（或首次扫描未完成）
     *    时返回保守策略 [IntegrityEnforcement.UNDETERMINED]，并顺带触发一次后台重扫，
     *    绝不用陈旧快照为高价值通道（解封主密码）放行。
     */
    override fun currentEnforcement(): IntegrityEnforcement {
        val snapshot = _report.value
        val stale = RuntimeIntegrityPolicy.isSnapshotStale(
            snapshotAtMillis = lastScanAtMillis,
            nowMillis = System.currentTimeMillis(),
            freshnessWindowMillis = SNAPSHOT_STALE_AFTER_MS
        )
        if (snapshot.level == RuntimeRiskLevel.UNDETERMINED || stale) {
            requestRescan()
            return IntegrityEnforcement.UNDETERMINED
        }
        return RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = snapshot,
            debuggerAttached = liveDebuggerAttached(),
            hookFrameworkDetected = snapshot.signals.hookFrameworkDetected,
            // ISSUE-P3-83：TracerPid 是**瞬时**信号，周期重扫会漏掉「附加→读取→脱离」窗口，
            // 故在此（非 suspend 门控的唯一入口）同步求值——生物快速解锁与 CM/自动填充
            // 两条通道因此同时获得 ptrace 信号。
            beingTraced = liveBeingTraced()
        ).enforcement
    }

    /** 触发一次后台重扫（去重：已有重扫在飞行中则不重复发起）。 */
    private fun requestRescan() {
        if (!rescanInFlight.compareAndSet(false, true)) return
        scanScope.launch {
            try {
                runCatching { refresh() }
            } finally {
                rescanInFlight.set(false)
            }
        }
    }

    override suspend fun awaitEnforcement(): IntegrityEnforcement {
        // ISSUE-P3-53：敏感操作（自动填充下发）前**重扫**，捕获冷启动后才出现的时变信号
        // （调试器附加 / 钩子框架落点等）；重扫失败时回退为等待首次扫描完成，
        // 超时同样返回 UNDETERMINED（fail-closed，绝不返回「默认放行」）。
        val refreshed = runCatching { refresh() }.getOrNull()
        val base = refreshed ?: withTimeoutOrNull(SCAN_AWAIT_TIMEOUT_MS) {
            report.first { it.level != RuntimeRiskLevel.UNDETERMINED }
        } ?: return IntegrityEnforcement.UNDETERMINED
        return RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = base,
            debuggerAttached = liveDebuggerAttached(),
            hookFrameworkDetected = false,
            beingTraced = liveBeingTraced()
        ).enforcement
    }

    /** 实时调试器附加信号（每次调用重新求值，不落缓存——ISSUE-P3-53） */
    private fun liveDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    /**
     * 实时 ptrace 信号（每次调用重新求值——ISSUE-P3-83）。
     *
     * 同步读 `/proc/self/status`：一次 KB 级 `/proc` 读，施加在解锁 / 填充这类低频入口上可接受。
     * 读不到即 `null` ⇒ [RuntimeIntegrityPolicy.isTraced] 判为「未检测到」（明示 fail-open 取舍，
     * 见 [IntegritySignals.beingTraced] KDoc）。
     */
    private fun liveBeingTraced(): Boolean =
        RuntimeIntegrityPolicy.isTraced(tracedProcessProbe.tracerPid())

    /** 重新采集信号并刷新快照（供显式复检；由 [start] 的周期重扫与敏感通道 await 触发） */
    suspend fun refresh(): RuntimeIntegrityReport {
        val report = RuntimeIntegrityPolicy.evaluate(detectSignals())
        _report.value = report
        // ISSUE-P2-63：记录扫描时刻，供非 suspend 门控判定快照新鲜度
        lastScanAtMillis = System.currentTimeMillis()
        AppLog.i(TAG, "运行完整性扫描完成: level=${report.level}")
        return report
    }

    private suspend fun detectSignals(): IntegritySignals = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext IntegritySignals.NONE
        IntegritySignals(
            debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger(),
            appDebuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            rootArtifactsDetected = ROOT_ARTIFACT_PATHS.any { File(it).exists() },
            magiskDetected = MAGISK_TRACE_PATHS.any { File(it).exists() },
            hookFrameworkDetected = detectHookFramework(),
            thirdPartyAccessibilityEnabled = detectThirdPartyAccessibility(ctx)
        )
    }

    /**
     * 无障碍服务探测（ISSUE-P2-44）。
     *
     * **口径**：`getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` 返回的任一服务，
     * 其包名 ≠ 本应用包名即为真（**含系统预装的 TalkBack**）——无障碍服务同等具备读取任意
     * 输入内容的能力，系统签名不改变该能力。只用官方 API，**无需任何权限**。
     *
     * **不参与等级判定**：仅驱动设置页安全分区的状态展示（见 [IntegritySignals] 该字段 KDoc）。
     *
     * 失败（服务不可用 / ROM 限制）一律按「未检测到」处理并落脱敏日志——
     * 该信号只影响提示，不构成 fail-closed 门控，故不上行异常。
     */
    private fun detectThirdPartyAccessibility(ctx: Context): Boolean {
        val manager = try {
            ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        } catch (t: Throwable) {
            AppLog.w(TAG, "获取 AccessibilityManager 失败，按未检测到处理", t)
            null
        } ?: return false
        return try {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val pkg = info.resolveInfo?.serviceInfo?.packageName
                    !pkg.isNullOrEmpty() && pkg != ctx.packageName
                }
        } catch (t: Throwable) {
            AppLog.w(TAG, "枚举无障碍服务失败，按未检测到处理", t)
            false
        }
    }

    /**
     * 钩子框架探测：优先扫描进程内存映射中已加载的可疑注入库，
     * 再兜底检查常见 Frida 服务端落点文件（均属磁盘 IO，已在 IO 调度器内执行）。
     *
     * ## 真机实测基线（**ISSUE-P3-120**，2026-09-16，Redmi 4X / LineageOS / Android 17 / API 37）
     *
     * 以 frida-server **17.15.3**（与宿主 frida 客户端同版本）实测三种形态，**3/3 全部命中**
     * ——完整性等级由基线 `ELEVATED` 升为 `COMPROMISED`：
     *
     * - **默认落点**：`/data/local/tmp/frida-server` **仅在案、未运行**即命中。本层是「落点存在性」
     *   检查，**不要求进程已注入**；
     * - **改名**：服务端改名到清单外路径并运行后，本层**漏报**（实测等级仍为 `ELEVATED`）；
     *   但一旦 attach，`/proc/self/maps` 出现 `/memfd:frida-agent-64.so (deleted)` ⇒ **maps 层命中**；
     * - **内存加载**：同一次实测中 agent 本就是以 **memfd** 载入、磁盘零落点（`(deleted)`），
     *   仍被 maps 层命中。
     *
     * 两条防线**互补**：落点层只在默认路径命中、但无需注入即可发现；maps 层覆盖「改名 + 已注入」。
     * 脱离 attach 后周期重扫可恢复为 `ELEVATED`（不误报残留）。
     *
     * ## **未经实测**的规避面（如实声明，不得读作已覆盖）
     *
     * 1. 把 agent 的 memfd 名一并改掉（如 `/memfd:agent-64.so`）⇒ 六条特征串均不命中——**未测**；
     * 2. 不产生**具名**映射的注入（匿名映射；或纯 `process_vm_readv` 读取、根本无需注入）⇒ maps
     *    无迹可寻——**未测**（该层固有边界另由 `ISSUE-P3-83` 的 `TracerPid` 信号部分补足）；
     * 3. hook 本进程的 `open`/`read`，使本函数读到空 maps 或伪造内容——**未测**，且原理上不可在
     *    应用层完全防御；
     * 4. **时序窗口**：周期重扫间隔为 30 秒（`LIVE_RESCAN_INTERVAL_MS`），若 `attach → 撤销 → 脱离`
     *    完整落在两次扫描之间，可不被捕获——**由实测矩阵推断的残余**（实测中 attach 持续约 75 秒，
     *    故被下一轮重扫覆盖）。
     *
     * 完整实测矩阵、原始证据与复现步骤见
     * `docs/records/运行完整性检测Frida实测基线.md`。
     */
    private fun detectHookFramework(): Boolean {
        val mapsHit = try {
            val maps = File(PROC_SELF_MAPS)
            maps.exists() && scanMapsForMarkers(maps)
        } catch (t: Throwable) {
            // 读取失败不视为攻击特征（部分 ROM 限制 /proc 访问），但必须留痕而非静默
            AppLog.w(TAG, "读取进程内存映射失败，跳过钩子库扫描", t)
            false
        }
        if (mapsHit) return true
        return HOOK_TRACE_PATHS.any { File(it).exists() }
    }

    /**
     * `ISSUE-P3-178`：maps 扫描改为**流式字节级匹配**。
     *
     * 原实现把整份 maps 逐行解码成 `String`，再对每行做 6 次 `contains(ignoreCase = true)`
     * ——典型进程数千行 ⇒ 每次敏感操作数千个 `String` + 6×数千次逐字符大小写折叠比较。
     * 现按固定块读取（块间保留 `maxMarkerLen - 1` 字节重叠，保证跨块特征串不漏），
     * 对每块做「首字节快速筛选 + 不区分大小写字节比对」。特征串清单 [HOOK_MARKERS]
     * **逐项未改**（该清单是 `ISSUE-P3-120` 真机实测基线的判据）。
     *
     * **有界读取**：总量上限 [MAPS_SCAN_MAX_BYTES]，超限即停止并落脱敏告警（fail-open，
     * 与「读取失败不视为攻击特征」同口径）——该上限同时封住「hook `read` 后喂无限流」的挂死面。
     */
    private fun scanMapsForMarkers(maps: File): Boolean =
        maps.inputStream().use { containsHookMarker(it) }

    /**
     * ISSUE-P3-231：安装来源信号（[untrustedInstallSource]）已整体移除。
     * `installingPackageName` 可被任意应用伪造，无法防伪；且误伤正常侧载 / 第三方商店用户，
     * 却未挡住重打包威胁。篡改防护改由签名指纹核对（README 公布 release 证书 SHA-256）与
     * 未接入的 Play Integrity 承担，故不再采集该信号，[TRUSTED_INSTALLERS] 一并删除。
     */
    companion object {
        private const val TAG = "RuntimeIntegrity"

        /** 等待首次扫描完成的兜底超时（超时按未判定保守策略处理） */
        private const val SCAN_AWAIT_TIMEOUT_MS = 1_000L

        /**
         * ISSUE-P2-63：后台周期重扫间隔。注入框架落点 / 内存映射变化在此间隔内进入快照，
         * 使非 suspend 门控（生物识别快速解锁）不再依赖启动态判定。
         *
         * ISSUE-P3-152 复核（2026-09-17）：本值**是安全参数，不得为省电放宽**——
         * 它同时是「附加注入框架后多久被抓到」的上界，[SNAPSHOT_STALE_AFTER_MS] 则保证
         * 连丢 4 次重扫即转 fail-closed。省下的只是每 30 s 一次的文件探测（IO 线程），
         * 与「高价值通道的判定新鲜度」不成比例，故**刻意不动**；该窗口作为**已接受残余风险**
         * 登记于 `docs/architecture/已知工程限界.md` §3.5。
         * `internal`（原 `private`）仅供 [`RuntimeIntegrityRescanContractTest`] 断言「不得放宽」。
         */
        internal const val LIVE_RESCAN_INTERVAL_MS = 30_000L

        /**
         * ISSUE-P2-63：快照新鲜度窗口。超过该时长未完成任何重扫（如进程挂起、IO 受限）
         * 即视为陈旧 → 非 suspend 门控转保守（fail-closed），容忍 4 次周期重扫缺失。
         *
         * 与 [LIVE_RESCAN_INTERVAL_MS] 同为安全参数，**不得放宽**（同见 §3.5 登记）。
         */
        internal const val SNAPSHOT_STALE_AFTER_MS = 120_000L

        private const val PROC_SELF_MAPS = "/proc/self/maps"

        /**
         * `ISSUE-P3-178`：maps 扫描的分块大小与总量上限。
         *
         * 分块读取避免「整份 maps 物化」（原实现逐行解码成 `String`，更差）；总量上限
         * [MAPS_SCAN_MAX_BYTES] 同时封住「hook `read` 后喂无限流」的挂死面——超限按未命中处理
         * 并落脱敏告警（fail-open，与「读取失败不视为攻击特征」同口径）。
         * 16 MiB 远超真实 maps 规模（典型数 MB 以内），不构成检测面收窄。
         */
        private const val MAPS_SCAN_CHUNK_BYTES = 64 * 1024
        private const val MAPS_SCAN_MAX_BYTES = 16L * 1024 * 1024

        /**
         * 流式扫描输入流中是否出现任一 [HOOK_MARKERS] 特征串（不区分大小写）。
         *
         * `ISSUE-P3-178`：逐块读取 + **块间重叠**（保留 `maxMarkerLen - 1` 字节，保证跨块特征串
         * 不漏），每块内做「首字节快速筛选 + 不区分大小写字节比对」，全程**零 `String` 分配**
         * （原实现把整份 maps 逐行解码成 `String` 并对每行做 6 次 `contains(ignoreCase = true)`）。
         *
         * **有界**：总量上限 [MAPS_SCAN_MAX_BYTES]，超限即停止并落脱敏告警（fail-open，与
         * 「读取失败不视为攻击特征」同口径）——该上限同时封住「hook `read` 后喂无限流」的挂死面。
         *
         * 放在伴生对象（而非实例成员）是因为它**不依赖任何实例状态**：maps 层检测的判据只由
         * [HOOK_MARKERS] 与本节常量决定。`internal` + 可选 [chunkSize] 供用例以**小块**
         * 驱动跨块边界场景（生产调用走默认分块）。
         */
        internal fun containsHookMarker(
            input: InputStream,
            chunkSize: Int = MAPS_SCAN_CHUNK_BYTES
        ): Boolean {
            val overlap = (HOOK_MARKERS.maxOf { it.length } - 1).coerceAtLeast(0)
            require(chunkSize > overlap) { "分块必须大于特征串最大长度，否则重叠窗口容纳不下跨块匹配" }

            val chunk = ByteArray(chunkSize)
            var carry = 0
            var total = 0L
            while (total < MAPS_SCAN_MAX_BYTES) {
                val read = input.read(chunk, carry, chunk.size - carry)
                if (read <= 0) return false
                val filled = carry + read
                total += read
                if (HOOK_MARKERS.any { indexOfIgnoreCaseAscii(chunk, 0, filled, it) >= 0 }) return true
                carry = if (filled <= overlap) {
                    filled
                } else {
                    System.arraycopy(chunk, filled - overlap, chunk, 0, overlap)
                    overlap
                }
            }
            AppLog.w(TAG, "内存映射扫描超出上限，按未命中处理（fail-open）")
            return false
        }

        /**
         * 在 `bytes[from, to)` 内查找 ASCII 串 [needle]（**不区分大小写**，按 ASCII 折叠），
         * 未命中返回 -1。
         *
         * 与 `String.contains(ignoreCase = true)` 的语义对齐，但全程在字节上完成（零 `String`
         * 分配）；先按首字节快速筛选再比对，均摊 `O(n)`。[HOOK_MARKERS] 为纯 ASCII 字符串，
         * 该前提由 `RuntimeIntegrityMarkerScanTest` 的「特征串清单必须全为 ASCII」一例锁定。
         */
        private fun indexOfIgnoreCaseAscii(
            bytes: ByteArray,
            from: Int,
            to: Int,
            needle: String
        ): Int {
            if (needle.isEmpty()) return from
            val last = to - needle.length
            val firstLower = lowerAscii(needle[0].code)
            var i = from
            while (i <= last) {
                if (lowerAscii(bytes[i].toInt() and 0xFF) == firstLower) {
                    var j = 1
                    while (j < needle.length && equalsIgnoreCaseAscii(bytes[i + j], needle[j].code)) j++
                    if (j == needle.length) return i
                }
                i++
            }
            return -1
        }

        private fun equalsIgnoreCaseAscii(byteValue: Byte, expectedCode: Int): Boolean =
            lowerAscii(byteValue.toInt() and 0xFF) == lowerAscii(expectedCode)

        private fun lowerAscii(value: Int): Int = if (value in 'A'.code..'Z'.code) value + 32 else value

        /** 常见 root 二进制 / Superuser 落点（文件探测，不执行任何外部命令） */
        private val ROOT_ARTIFACT_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su",
            "/system/app/Superuser.apk",
            "/system/etc/init.d/99SuperSUDaemon"
        )

        /** Magisk 常见痕迹路径 */
        private val MAGISK_TRACE_PATHS = listOf(
            "/sbin/.magisk",
            "/data/adb/magisk",
            "/data/adb/magisk.img",
            "/data/adb/modules",
            "/cache/magisk.log",
            "/dev/.magisk"
        )

        /**
         * Frida 服务端 / gadget 常见落点。
         *
         * **改动须知（ISSUE-P3-120）**：本清单与 [HOOK_MARKERS] 是
         * `docs/records/运行完整性检测Frida实测基线.md` 那份**真机实测矩阵的判据本身**——
         * 增删任何一项都会使「命中率 3/3」的结论**不再适用**，须重跑实测并更新该文档。
         * `internal` 供 `RuntimeIntegrityDetectionSurfaceTest` 逐项锁定。
         */
        internal val HOOK_TRACE_PATHS = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/data/local/tmp/frida",
            "/system/lib/libfrida-gadget.so",
            "/system/lib64/libfrida-gadget.so"
        )

        /**
         * 内存映射中的注入框架特征串。
         *
         * **改动须知（ISSUE-P3-120）**：同 [HOOK_TRACE_PATHS]——本清单是实测基线的判据，
         * 变更须重跑真机实测（尤其注意：实测已证实「agent 的 memfd 名」是唯一泄漏点，
         * 若想收紧应针对它，而不是无靶地扩充本清单）。
         */
        internal val HOOK_MARKERS =
            listOf("frida", "xposed", "substrate", "edxposed", "lsposed", "libhook")
    }
}
