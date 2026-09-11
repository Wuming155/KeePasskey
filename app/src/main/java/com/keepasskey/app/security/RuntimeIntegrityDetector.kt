package com.keepasskey.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val _report = MutableStateFlow(RuntimeIntegrityReport.UNDETERMINED)

    /** 完整性扫描快照（UI 风险提示与敏感通道策略的唯一数据源） */
    val report: StateFlow<RuntimeIntegrityReport> = _report.asStateFlow()

    init {
        // 单例首次构造即异步启动扫描（后台 IO，不阻塞注入方）
        start()
    }

    /** 幂等启动扫描；可被宿主 Application 重复调用 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scanScope.launch { refresh() }
    }

    override fun currentEnforcement(): IntegrityEnforcement =
        // ISSUE-P3-53：非 suspend 路径（如生物识别放行）以**实时**调试器信号升级缓存快照——
        // `Debug.isDebuggerConnected()` 为廉价同步调用，可主线程安全求值；钩子框架为磁盘 IO，
        // 此处不扫（由 suspend 的 awaitEnforcement 重扫覆盖）。
        RuntimeIntegrityPolicy.escalateForLiveSignals(
            base = _report.value,
            debuggerAttached = liveDebuggerAttached(),
            hookFrameworkDetected = false
        ).enforcement

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

    /** 重新采集信号并刷新快照（供显式复检；默认由 [start] 触发一次） */
    suspend fun refresh(): RuntimeIntegrityReport {
        val report = RuntimeIntegrityPolicy.evaluate(detectSignals())
        _report.value = report
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
            untrustedInstallSource = detectUntrustedInstallSource(ctx)
        )
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
