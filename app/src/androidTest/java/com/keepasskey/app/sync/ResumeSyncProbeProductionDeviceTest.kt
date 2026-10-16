package com.keepasskey.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P3-628：持久化 WebDAV 配置下，网络恢复入口必须经生产 Provider 发出 HTTPS 元数据请求。 */
@RunWith(AndroidJUnit4::class)
class ResumeSyncProbeProductionDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun networkAvailableTriggersProductionHttpsProbe() = runBlocking {
        exerciseProductionProbe(SCENARIO_NETWORK_AVAILABLE)
    }

    @Test
    fun realNetworkDisconnectAndRecoveryTriggersProductionHttpsProbe() = runBlocking(Dispatchers.IO) {
        exerciseProductionProbe(SCENARIO_NETWORK_TRANSITION)
    }

    @Test
    fun targetChangeDuringProductionHttpsProbeDropsLateResult() = runBlocking {
        exerciseProductionProbe(SCENARIO_TARGET_SWITCH)
    }

    private suspend fun exerciseProductionProbe(scenario: String) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val webdavPort = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_WEBDAV_PORT)?.toIntOrNull()) {
            "缺少 p3_628_webdav_port"
        }
        val vaultFile = File(context.filesDir, "p3-628-probe-$runId.kdbx")
        val switchedVaultFile = File(context.filesDir, "p3-628-probe-switched-$runId.kdbx")
        val remotePath = REMOTE_PATH
        val previousActiveId = ActiveDatabaseIdStore(context).load()
        val log = DebugLogBuffer()
        val session = DatabaseSession()
        val switchedDatabaseSession = DatabaseSession()
        val settings = RealSettingsRepository(context)
        val originalProbeEnabled = settings.getSettings().first().syncProbeOnResumeEnabled
        val originalAutoLockSettings = settings.getSettings().first()
        val credentials = SyncCredentialsStore(
            context,
            KeystoreManager(context, log),
            log,
            SyncCacheEvictor(context, log),
            session
        )
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val initialNetworkAvailable = CompletableDeferred<Unit>()
        val networkWasLost = CompletableDeferred<Unit>()
        val hadNetwork = AtomicBoolean(false)
        var probeOnNetworkCallback: ResumeSyncProbeCoordinator? = null
        val networkObserver = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hadNetwork.set(true)
                initialNetworkAvailable.complete(Unit)
            }

            override fun onLost(network: Network) {
                if (hadNetwork.get()) networkWasLost.complete(Unit)
            }
        }
        val switchTargetReceived = CompletableDeferred<Unit>()
        val networkRestoredSignal = CompletableDeferred<Unit>()
        val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == ACTION_SWITCH_TARGET) {
                    switchTargetReceived.complete(Unit)
                } else if (intent?.action == ACTION_NETWORK_RESTORED) {
                    probeOnNetworkCallback?.onNetworkAvailable()
                    networkRestoredSignal.complete(Unit)
                }
            }
        }
        var switchReceiverRegistered = false
        try {
            val password = MASTER_PASSWORD.toCharArray()
            try {
                assertTrue(
                    "探测合成库创建失败",
                    session.create(vaultFile, VAULT_NAME, password, useArgon2 = false) is KdbxResult.Success
                )
            } finally {
                password.fill('\u0000')
            }

            ActiveDatabaseIdStore(context).save(vaultFile.absolutePath)
            assertTrue(
                "生产 WebDAV 配置封存失败",
                credentials.saveWebDavConfig(
                    url = "https://127.0.0.1:$webdavPort",
                    username = WEBDAV_USER,
                    password = WEBDAV_PASSWORD.toCharArray(),
                    remotePath = remotePath,
                    dbId = vaultFile.absolutePath
                )
            )
            credentials.saveProvider(CloudSyncProvider.WEBDAV, vaultFile.absolutePath)

            if (scenario == SCENARIO_TARGET_SWITCH) {
                val switchedPassword = MASTER_PASSWORD.toCharArray()
                try {
                    assertTrue(
                        "切换目标合成库创建失败",
                        switchedDatabaseSession.create(
                            switchedVaultFile,
                            "$VAULT_NAME switched",
                            switchedPassword,
                            useArgon2 = false
                        ) is KdbxResult.Success
                    )
                } finally {
                    switchedPassword.fill('\u0000')
                    switchedDatabaseSession.close()
                }
                assertTrue(
                    "切换目标生产 WebDAV 配置封存失败",
                    credentials.saveWebDavConfig(
                        url = "https://127.0.0.1:$webdavPort",
                        username = WEBDAV_USER,
                        password = WEBDAV_PASSWORD.toCharArray(),
                        remotePath = remotePath,
                        dbId = switchedVaultFile.absolutePath
                    )
                )
                credentials.saveProvider(CloudSyncProvider.WEBDAV, switchedVaultFile.absolutePath)
            }

            val coordinator = SyncCoordinator(
                context,
                session,
                credentials,
                log,
                StringsProvider { id, args -> context.getString(id, *args) }
            )
            assertNull("P3-628 设备验收不得注入测试 Provider", coordinator.testSyncProvider)

            val target = requireNotNull(coordinator.resolveProbeTarget())
            coordinator.rememberRemoteEtagForProbe("synthetic-stale-etag", target.identity)
            settings.setLockWhenScreenOff(false)
            settings.setAutoLockBackground(false)
            settings.setAutoLockForegroundEnabled(false)
            settings.setSyncProbeOnResumeEnabled(true)
            val autoLock = AutoLockManager(
                context,
                AutoLockSessionGuard(session, settings, log),
                settings
            )
            val probe = ResumeSyncProbeCoordinator(
                context,
                settings,
                coordinator,
                autoLock,
                StringsProvider { id, args -> context.getString(id, *args) }
            )
            when (scenario) {
                SCENARIO_NETWORK_AVAILABLE -> {
                    // 首次默认网络 onAvailable 必须触发真实生产 HTTPS 元数据请求。
                    probe.initialize()
                    assertRemoteChangedNotice(probe, coordinator, target.identity)
                }

                SCENARIO_NETWORK_TRANSITION -> {
                    val activeNetwork = requireNotNull(connectivityManager.activeNetwork) {
                        "断网前必须存在真实默认网络"
                    }
                    val activeCapabilities = requireNotNull(connectivityManager.getNetworkCapabilities(activeNetwork))
                    assertTrue(
                        "实体机网络转变场景需要当前默认网络为 Wi-Fi",
                        activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    )
                    connectivityManager.registerDefaultNetworkCallback(networkObserver)
                    assertTrue(
                        "断网测试 observer 未观察到初始默认网络",
                        withTimeoutOrNull(NETWORK_CALLBACK_TIMEOUT_MS) { initialNetworkAvailable.await(); true } == true
                    )
                    setWifiEnabled(false)
                    assertTrue(
                        "关闭 Wi-Fi 后 observer 未收到默认网络 onLost",
                        withTimeoutOrNull(NETWORK_CALLBACK_TIMEOUT_MS) { networkWasLost.await(); true } == true
                    )
                    // 离线时才注册生产探测器，确保首次回调只能来自恢复后的 onAvailable。
                    probe.initialize()
                    probeOnNetworkCallback = probe
                    ContextCompat.registerReceiver(
                        context,
                        switchReceiver,
                        IntentFilter(ACTION_NETWORK_RESTORED),
                        ContextCompat.RECEIVER_EXPORTED
                    )
                    switchReceiverRegistered = true
                    val readyMarker = File(context.filesDir, NETWORK_TRANSITION_READY_FILE)
                    readyMarker.writeText("onLost received; waiting for host to restore Wi-Fi\n")
                    try {
                        withTimeout(NETWORK_CALLBACK_TIMEOUT_MS) { networkRestoredSignal.await() }
                        // 宿主只在 Wi-Fi 已重新连上后发信号；由该恢复事件驱动生产探测入口与 HTTPS Provider。
                        assertRemoteChangedNotice(probe, coordinator, target.identity)
                    } finally {
                        readyMarker.delete()
                    }
                }

                SCENARIO_TARGET_SWITCH -> {
                    ContextCompat.registerReceiver(
                        context,
                        switchReceiver,
                        IntentFilter(ACTION_SWITCH_TARGET),
                        ContextCompat.RECEIVER_EXPORTED
                    )
                    switchReceiverRegistered = true
                    probe.initialize()
                    withTimeout(TARGET_SWITCH_TIMEOUT_MS) { switchTargetReceived.await() }
                    session.close()
                    val switchedPassword = MASTER_PASSWORD.toCharArray()
                    try {
                        assertTrue(
                            "在途请求期间必须成功切换到另一数据库会话",
                            session.open(switchedVaultFile, switchedPassword) is KdbxResult.Success
                        )
                    } finally {
                        switchedPassword.fill('\u0000')
                    }
                    ActiveDatabaseIdStore(context).save(switchedVaultFile.absolutePath)
                    // 服务端响应保持延迟；等待该在途响应结束后，过期目标不得写回 ETag 或提示。
                    delay(REMOTE_REQUEST_SETTLE_MS)
                    assertNull("切换活动库后必须丢弃旧 HTTPS 探测结果", probe.notice.value)
                    assertEquals(
                        "切换库后必须按新库重新解析生产目标",
                        switchedVaultFile.absolutePath,
                        session.currentPathIdentifier
                    )
                }

                else -> error("未知 P3-628 场景：$scenario")
            }
            assertEquals("生产 Provider 应保持为空测试钩子", null, coordinator.testSyncProvider)
        } finally {
            if (switchReceiverRegistered) runCatching { context.unregisterReceiver(switchReceiver) }
            runCatching { connectivityManager.unregisterNetworkCallback(networkObserver) }
            runCatching { switchedDatabaseSession.close() }
            settings.setSyncProbeOnResumeEnabled(originalProbeEnabled)
            settings.setLockWhenScreenOff(originalAutoLockSettings.lockWhenScreenOff)
            settings.setAutoLockBackground(originalAutoLockSettings.autoLockBackground)
            settings.setAutoLockForegroundEnabled(originalAutoLockSettings.autoLockForegroundEnabled)
            session.close()
            credentials.clearFor(vaultFile.absolutePath)
            credentials.clearFor(switchedVaultFile.absolutePath)
            ActiveDatabaseIdStore(context).save(previousActiveId)
            File(vaultFile.absolutePath + ".bak").delete()
            vaultFile.delete()
        }
    }

    private suspend fun assertRemoteChangedNotice(
        probe: ResumeSyncProbeCoordinator,
        coordinator: SyncCoordinator,
        targetIdentity: String
    ) {
        val notice = withTimeout(PROBE_TIMEOUT_MS) { probe.notice.first { it != null } }
        assertEquals(context.getString(R.string.sync_probe_remote_changed), notice)
        assertTrue(
            "探测后 ETag 必须更新到真实远端响应",
            coordinator.lastRemoteEtagForProbe(targetIdentity) != "synthetic-stale-etag"
        )
    }

    private fun setWifiEnabled(enabled: Boolean) {
        val command = if (enabled) "svc wifi enable" else "svc wifi disable"
        executeShell(command)
        if (enabled) executeShell("cmd wifi start-scan")
    }

    private fun executeShell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val ARG_WEBDAV_PORT = "p3_628_webdav_port"
        const val WEBDAV_USER = "p2-627-test"
        const val WEBDAV_PASSWORD = "Synthetic-P2-627-WebDav-Password"
        const val MASTER_PASSWORD = "Synthetic-P3-628-Master-Password"
        const val VAULT_NAME = "P3-628 Probe"
        const val REMOTE_PATH = "/p3-628-probe.kdbx"
        const val PROBE_TIMEOUT_MS = 60_000L
        const val NETWORK_CALLBACK_TIMEOUT_MS = 90_000L
        const val INITIAL_NETWORK_CALLBACK_SETTLE_MS = 1_000L
        const val TARGET_SWITCH_TIMEOUT_MS = 30_000L
        const val REMOTE_REQUEST_SETTLE_MS = 20_000L
        const val SCENARIO_NETWORK_AVAILABLE = "network_available"
        const val SCENARIO_NETWORK_TRANSITION = "network_transition"
        const val SCENARIO_TARGET_SWITCH = "target_switch"
        const val ACTION_SWITCH_TARGET = "com.keepasskey.debug.P3_628_SWITCH_TARGET"
        const val ACTION_NETWORK_RESTORED = "com.keepasskey.debug.P3_628_NETWORK_RESTORED"
        const val NETWORK_TRANSITION_READY_FILE = ".p3-628-network-transition.ready"
    }
}
