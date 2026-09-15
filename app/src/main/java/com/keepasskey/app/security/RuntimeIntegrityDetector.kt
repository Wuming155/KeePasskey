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
    @ApplicationContext private val context: Context?
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
            hookFrameworkDetected = snapshot.signals.hookFrameworkDetected
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
            hookFrameworkDetected = false
        ).enforcement
    }

    /** 实时调试器附加信号（每次调用重新求值，不落缓存——ISSUE-P3-53） */
    private fun liveDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

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
            untrustedInstallSource = detectUntrustedInstallSource(ctx),
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
     * **不参与等级判定**：仅驱动主密码输入页的提示（见 [IntegritySignals] 该字段 KDoc）。
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
     */
    private fun detectHookFramework(): Boolean {
        val mapsHit = try {
            val maps = File(PROC_SELF_MAPS)
            if (!maps.exists()) {
                false
            } else {
                maps.bufferedReader().useLines { lines ->
                    lines.any { line ->
                        HOOK_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
                    }
                }
            }
        } catch (t: Throwable) {
            // 读取失败不视为攻击特征（部分 ROM 限制 /proc 访问），但必须留痕而非静默
            AppLog.w(TAG, "读取进程内存映射失败，跳过钩子库扫描", t)
            false
        }
        if (mapsHit) return true
        return HOOK_TRACE_PATHS.any { File(it).exists() }
    }

    /**
     * 安装来源判定：仅当能确定 installer 且不在受信任分发方集合中时升级风险；
     * 无法确定（installer 为 null，如 adb 直装 / 部分 ROM）一律不升级，避免误报。
     *
     * **ISSUE-P1-23 显式决策留痕（2026-09-13）**：`installer == null` → 不升级风险，系**显式产品决策**，非遗漏——
     * 1. 该信号只能证明「非商店渠道安装」，**无法证明「APK 未被篡改」**：自签重打包版的 installer
     *    同样为 null，且应用内任何自校验逻辑都可被重打包者一并 patch 掉——应用内无法建立该信任根；
     * 2. 对该威胁的有效缓解在应用外：README「官方签名指纹」公布 release 证书 SHA-256 供安装前核对；
     *    上架商店后接入平台完整性证明（Play Integrity 或同等服务，尚未上架，暂不适用）；
     * 3. 故本信号维持「仅可判定来源时才参与升级」语义：null 不当可疑（避免 adb / 企业分发误报），
     *    也不当安全（篡改防护交由签名指纹核对，不引入无效的「应用内签名自校验」）。
     */
    private fun detectUntrustedInstallSource(ctx: Context): Boolean {
        val installer = try {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } catch (t: Throwable) {
            AppLog.w(TAG, "查询安装来源失败，按来源不可判定处理", t)
            null
        } ?: return false
        return installer !in TRUSTED_INSTALLERS
    }

    companion object {
        private const val TAG = "RuntimeIntegrity"

        /** 等待首次扫描完成的兜底超时（超时按未判定保守策略处理） */
        private const val SCAN_AWAIT_TIMEOUT_MS = 1_000L

        /**
         * ISSUE-P2-63：后台周期重扫间隔。注入框架落点 / 内存映射变化在此间隔内进入快照，
         * 使非 suspend 门控（生物识别快速解锁）不再依赖启动态判定。
         */
        private const val LIVE_RESCAN_INTERVAL_MS = 30_000L

        /**
         * ISSUE-P2-63：快照新鲜度窗口。超过该时长未完成任何重扫（如进程挂起、IO 受限）
         * 即视为陈旧 → 非 suspend 门控转保守（fail-closed），容忍 4 次周期重扫缺失。
         */
        private const val SNAPSHOT_STALE_AFTER_MS = 120_000L

        private const val PROC_SELF_MAPS = "/proc/self/maps"

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

        /** Frida 服务端 / gadget 常见落点 */
        private val HOOK_TRACE_PATHS = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/data/local/tmp/frida",
            "/system/lib/libfrida-gadget.so",
            "/system/lib64/libfrida-gadget.so"
        )

        /** 内存映射中的注入框架特征串 */
        private val HOOK_MARKERS = listOf("frida", "xposed", "substrate", "edxposed", "lsposed", "libhook")

        /** 受信任安装来源（官方商店与主流开源分发渠道） */
        private val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "org.fdroid.fdroid"
        )
    }
}
