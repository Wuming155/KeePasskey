package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncPreferences
import com.keepasskey.app.sync.SyncProviderResolver
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.SettingsSyncController
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.screens.settings.subscreens.CloudSyncScreen
import com.keepasskey.app.ui.theme.KeePasskeyTheme
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 仅 debug 变体：把**生产** `CloudSyncScreen` 挂到真实 `SettingsSyncController` 上，
 * 供 `ISSUE-P2-651/652` 的设备用例以无障碍节点驱动真实表单。
 *
 * 为什么不直接跑 `MainActivity`：设置页需要先解锁、导航到二级页，且控制器藏在 Hilt 图里；
 * 用例需要「同一控制器 + 真实 HTTPS 接收方计数 + 在途改存」三件事同时可控。这里挂的组合件与
 * 生产逐字相同（表单本地状态、`SecurePasswordField`、离页 `DisposableEffect`、两处保存入口的
 * `persistThenContinue` 守卫全在 `CloudSyncScreen` 内），差别仅在于控制器由用例注入。
 *
 * 水合与生产导航路由同序：生产 `settingsSyncRoute` 在进入页面时调用
 * `settingsViewModel.hydrateSyncUi()`（后台派发器做 Keystore 解密并填充一次性预填通道），
 * 本宿主照做——否则「离页后重进不得回显未保存草稿」这一断言会因为预填通道永远为空而失去判别力。
 */
class CloudSyncDeviceHostActivity : ComponentActivity() {

    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: SettingsSyncController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val supplied = requireNotNull(suppliedSetup) { "instrumented setup not supplied" }
        controller = supplied.controller
        val controllerRef = supplied.controller

        setContent {
            KeePasskeyTheme {
                LaunchedEffect(Unit) { controllerRef.hydrate() }
                val syncState by controllerRef.state.collectAsState()
                val webdavPrefill by controllerRef.webdavPasswordPrefill.collectAsState()
                val s3SecretPrefill by controllerRef.s3SecretKeyPrefill.collectAsState()
                val s3AccessPrefill by controllerRef.s3AccessKeyPrefill.collectAsState()
                CloudSyncScreen(
                    uiState = syncUiState(syncState),
                    onBackClick = { finish() },
                    onAutoSyncToggle = {},
                    onWifiOnlyToggle = {},
                    onTriggerSync = { controllerRef.triggerSync() },
                    onRecoverSyncByKeepingLocal = { controllerRef.recoverPendingSyncByKeepingLocal() },
                    onTestConnection = { controllerRef.testSyncConnection() },
                    onProviderChange = { controllerRef.setSyncProvider(it) },
                    onSyncAfterSave = { controllerRef.verifyConnectionThenSync() },
                    onUpdateWebDav = { url, username, password, remotePath ->
                        // 验收探针：点击是否真的到达按钮（否则「状态没变」会被误读成守卫未生效）
                        updateCalls.incrementAndGet()
                        lastSubmittedUrl = url
                        controllerRef.updateWebDavConfig(url, username, password, remotePath)
                    },
                    onUpdateS3 = { endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle ->
                        controllerRef.updateS3Config(
                            endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle
                        )
                    },
                    webdavPasswordPrefill = webdavPrefill,
                    s3SecretKeyPrefill = s3SecretPrefill,
                    s3AccessKeyPrefill = s3AccessPrefill,
                    onWebDavPasswordEdited = { controllerRef.clearWebDavPasswordPrefill() },
                    onS3SecretKeyEdited = { controllerRef.clearS3SecretKeyPrefill() },
                    onS3AccessKeyEdited = { controllerRef.clearS3AccessKeyPrefill() }
                )
            }
        }
    }

    /**
     * 只投影与同步表单相关的字段；其余取默认值。
     *
     * 不调用生产的 `buildSettingsUiState`：那条路径要求健康 / 库配置等九路输入流，
     * 本宿主不承担那些面，构造假值只会把无关状态混进断言。
     */
    private fun syncUiState(sync: SettingsSyncController.SyncUiState): SettingsUiState = SettingsUiState(
        syncProvider = sync.provider,
        webdavUrl = sync.webdavUrl,
        webdavUsername = sync.webdavUsername,
        webdavRemotePath = sync.webdavRemotePath,
        s3Endpoint = sync.s3Endpoint,
        s3Bucket = sync.s3Bucket,
        s3Region = sync.s3Region,
        s3ObjectKey = sync.s3ObjectKey,
        s3UsePathStyle = sync.s3UsePathStyle,
        isSyncing = sync.isSyncing,
        syncFeedbackMessage = sync.syncFeedbackMessage,
        isConnectionVerified = sync.isConnectionVerified,
        syncRecoveryRequired = sync.syncRecoveryRequired,
        syncRecoveryCanKeepLocal = sync.syncRecoveryCanKeepLocal,
        wifiOnlySync = sync.wifiOnlySync,
        syncSealHardwareBacked = sync.syncSealHardwareBacked
    )

    override fun onDestroy() {
        hostScope.cancel()
        super.onDestroy()
    }

    /** 用例注入的装配；字段与生产同型（真实 store / 真实 coordinator / 真实会话）。 */
    internal class Setup(
        val controller: SettingsSyncController,
        val coordinator: SyncCoordinator,
        val session: DatabaseSession
    )

    companion object {
        @Volatile
        internal var suppliedSetup: Setup? = null

        /** 验收探针：`onUpdateWebDav` 被调用次数（点击是否落到按钮上）。 */
        internal val updateCalls = java.util.concurrent.atomic.AtomicInteger(0)

        /** 验收探针：最后一次交给控制器的 URL（用于区分「点了但表单空」与「没点」）。 */
        @Volatile
        internal var lastSubmittedUrl: String = ""

        /** 供用例复用同一套生产装配，避免用例与宿主各写一份构造。 */
        internal fun buildSetup(
            applicationContext: android.content.Context,
            credentials: SyncCredentialsStore,
            session: DatabaseSession,
            scope: CoroutineScope,
            strings: StringsProvider
        ): Setup {
            val debugLog = DebugLogBuffer()
            // 必须显式注入同一 resolver：缺省时 `SyncCoordinator` 会自建一个默认实例，
            // 与用例装配的凭据库不是同一份（设备用例因此拿不到注入的 Provider 构造行为）。
            val resolver = SyncProviderResolver(
                credentials,
                SyncPreferences(debugLog, null),
                debugLog
            )
            val coordinator = SyncCoordinator(
                context = applicationContext,
                databaseSession = session,
                syncCredentialsStore = credentials,
                debugLog = debugLog,
                strings = strings,
                syncProviderResolver = resolver
            )
            val controller = SettingsSyncController(
                credentials,
                coordinator,
                ExtendedSettingsStore(applicationContext),
                strings,
                scope
            )
            return Setup(controller, coordinator, session)
        }
    }
}
