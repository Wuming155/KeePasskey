package com.keepasskey.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.ui.model.StringsProvider
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P3-381：回前台 / 网络恢复时的远端变更探测编排。
 *
 * 口径（批次留痕）：
 * - 策略层 [ResumeSyncProbePolicy] 已落（开关 + 30s 节流 + 锁定态跳过）；
 * - 本类负责挂点：进程回前台（`AutoLockManager.onStart`）与网络恢复回调，**不直接 syncNow**；
 * - 探测结果只作提示，写路径仍归用户显式同步（与解锁后自动同步避免双写）；
 * - 周期 WorkManager 默认关闭的现状不变；
 * - ISSUE-P2-496：节流基线在探测**前**快照（[ResumeSyncProbePolicy.planProbe]），时间戳探测
 *   **后**才回写；结论文案经 [notice] 供给设置页状态行（不再有零消费者成员）。
 */
@Singleton
class ResumeSyncProbeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncCoordinator: SyncCoordinator,
    private val autoLockManager: AutoLockManager,
    private val strings: StringsProvider
) {

    /** 进程级探测作用域（不走 Hilt CoroutineScope 绑定——该绑定在本图中不存在） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lastProbeAtMillis = MutableStateFlow<Long?>(null)

    /**
     * ISSUE-P2-496：最近一次探测的**结论文案**（已本地化）；null = 无可呈现结论
     * （开关关闭 / 未配置同步 / 尚未探测）。由设置页「回前台探测」开关下方状态行消费——
     * 修复「开关可拨、结论永不呈现」（`Skipped` 不再写入空串，改为 null 不呈现）。
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var initialized = false
    private var resumeListener: (() -> Unit)? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        val listener: () -> Unit = { maybeProbe() }
        resumeListener = listener
        autoLockManager.addOnForegroundResumeListener(listener)
        registerNetworkCallback()
    }

    /** 网络恢复 / 首次联网时触发一次探测（受策略层节流与开关门控） */
    fun onNetworkAvailable() {
        maybeProbe()
    }

    private fun maybeProbe() {
        scope.launch {
            val settings = settingsRepository.getSettings().first()
            val isLocked = autoLockManager.isLocked.value
            // ISSUE-P2-496：先取**探测前**基线快照，节流判据与 classify 基线同源于它。
            val plan = ResumeSyncProbePolicy.planProbe(
                probeEnabled = settings.syncProbeOnResumeEnabled,
                isLocked = isLocked,
                lastProbeAtMillis = _lastProbeAtMillis.value
            )
            if (!plan.shouldProbe) return@launch
            val outcome = probeRemote(
                probeEnabled = settings.syncProbeOnResumeEnabled,
                isLocked = isLocked,
                classifyBaselineMillis = plan.classifyBaselineMillis
            )
            // ISSUE-P2-496：时间戳在探测**完成后**才回写——探测内的 classify 复核用的是旧基线，
            // 不再消费「本次刚刷新」的值（原实现先写时间戳再探测 ⇒ 自败节流）。
            _lastProbeAtMillis.value = System.currentTimeMillis()
            _notice.value = describeOutcome(outcome).takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun probeRemote(
        probeEnabled: Boolean,
        isLocked: Boolean,
        /** 探测前基线（见 [ResumeSyncProbePolicy.planProbe]）；三处 classify 一律消费它 */
        classifyBaselineMillis: Long?
    ): ResumeSyncProbePolicy.ProbeOutcome = withContext(Dispatchers.IO) {
        try {
            if (!syncCoordinator.isSyncConfigured()) {
                return@withContext ResumeSyncProbePolicy.ProbeOutcome.Skipped
            }
            val provider = syncCoordinator.testSyncProvider
                ?: return@withContext ResumeSyncProbePolicy.ProbeOutcome.Failed("未配置云同步")
            val remotePath = syncCoordinator.resolveRemotePathForProbe()
                ?: return@withContext ResumeSyncProbePolicy.ProbeOutcome.Skipped
            val meta = provider.getMetadata(remotePath)
            if (meta.isFailure) {
                return@withContext ResumeSyncProbePolicy.classify(
                    probeEnabled = probeEnabled,
                    isLocked = isLocked,
                    lastProbeAtMillis = classifyBaselineMillis,
                    hasRemote = false,
                    remoteChanged = false
                )
            }
            val etag = meta.getOrNull()?.etag.orEmpty()
            val lastEtag = syncCoordinator.lastRemoteEtagForProbe()
            // 无基线时：只记录当前 etag，不提示变化（避免首探误报）
            if (lastEtag.isBlank()) {
                if (etag.isNotBlank()) syncCoordinator.rememberRemoteEtagForProbe(etag)
                return@withContext ResumeSyncProbePolicy.classify(
                    probeEnabled = probeEnabled,
                    isLocked = isLocked,
                    lastProbeAtMillis = classifyBaselineMillis,
                    hasRemote = true,
                    remoteChanged = false
                )
            }
            val remoteChanged = etag.isNotBlank() && etag != lastEtag
            if (remoteChanged && etag.isNotBlank()) {
                syncCoordinator.rememberRemoteEtagForProbe(etag)
            }
            ResumeSyncProbePolicy.classify(
                probeEnabled = probeEnabled,
                isLocked = isLocked,
                lastProbeAtMillis = classifyBaselineMillis,
                hasRemote = true,
                remoteChanged = remoteChanged
            )
        } catch (e: Exception) {
            ResumeSyncProbePolicy.ProbeOutcome.Failed(e.message ?: "探测失败")
        }
    }

    private fun describeOutcome(outcome: ResumeSyncProbePolicy.ProbeOutcome): String = when (outcome) {
        is ResumeSyncProbePolicy.ProbeOutcome.RemoteChanged ->
            strings.get(com.keepasskey.app.R.string.sync_probe_remote_changed)
        ResumeSyncProbePolicy.ProbeOutcome.Unchanged ->
            strings.get(com.keepasskey.app.R.string.sync_probe_unchanged)
        is ResumeSyncProbePolicy.ProbeOutcome.Failed ->
            strings.get(com.keepasskey.app.R.string.sync_probe_failed, outcome.reason)
        ResumeSyncProbePolicy.ProbeOutcome.Skipped -> ""
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkAvailable()
            }
        }
        networkCallback = callback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (_: Exception) {
            // 注册失败仅失去网络恢复挂点；回前台挂点仍生效
        }
    }
}
