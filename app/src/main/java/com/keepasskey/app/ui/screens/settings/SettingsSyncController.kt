package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.sync.userText
import com.keepasskey.app.sync.SyncProviderConfigSnapshot
import com.keepasskey.app.sync.launchGuarded
import com.keepasskey.app.ui.model.RelativeTimeFormatter
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.StringsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * TASK-21 拆分：设置页「云端多协议同步」状态控制器。
 * 持有同步状态流与凭据明文一次性预填通道，承接凭据恢复、配置保存（Wave 14/15 语义）、
 * 手动同步 / 连接测试、「保存并同步」顺序编排与反馈文案生成；ViewModel 仅做委托转发
 * （顺序编排随 ISSUE-P3-257 自 ViewModel 下沉，KDoc 立规缘由随函数整体迁入本类）。
 *
 * `ISSUE-P2-645`：凭据表单面（预填通道 / WebDAV 与 S3 配置保存 / 封印密钥实测）拆到
 * [SettingsSyncCredentialForm]，本类保留状态流与同步编排；公开 API 形状不变。
 */
internal class SettingsSyncController(
    private val syncCredentialsStore: SyncCredentialsStore,
    private val syncCoordinator: SyncCoordinator,
    private val extendedSettingsStore: ExtendedSettingsStore,
    private val strings: StringsProvider,
    private val scope: CoroutineScope
) {

    fun isSyncConfigured(): Boolean = try {
        syncCoordinator.isSyncConfigured()
    } catch (_: Exception) {
        false
    }

    /** 同步配置状态（含表单回显与同步过程反馈） */
    internal data class SyncUiState(
        val provider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
        // M2 整改：默认值一律空串，杜绝示例凭据（mypassword123 / AKIA 示例密钥对）
        // 被静默保存为真实云端凭据
        // Wave 15 整改：webdavPassword/s3SecretKey 明文不再驻留本状态流
        // （经 CharArray 一次性预填通道下发，保存后即擦除）
        // ISSUE-P2-01：s3AccessKey 同步改造——AccessKey ID 亦不再以 String 驻留本状态流，
        // 与 SecretKey 同走 CharArray 一次性预填通道
        val webdavUrl: String = com.keepasskey.app.sync.WebDavDefaults.NUTSTORE_URL,
        val webdavUsername: String = "",
        val webdavRemotePath: String = com.keepasskey.app.sync.WebDavDefaults.DEFAULT_REMOTE_PATH,
        val s3Endpoint: String = "",
        val s3Bucket: String = "",
        val s3Region: String = "auto",
        val s3ObjectKey: String = "keepasskey.kdbx",
        val s3UsePathStyle: Boolean = false,
        val wifiOnlySync: Boolean = true,
        val isSyncing: Boolean = false,
        val syncFeedbackMessage: UiMessage? = null,
        val syncRecoveryRequired: Boolean = false,
        val syncRecoveryCanKeepLocal: Boolean = false,
        // H1 整改：真实同步完成时刻文案（空串=本会话尚未同步成功过）
        val lastSyncTimeText: String = "",
        // 连接是否已验证（测试连接或同步成功后置位；配置变更后复位）
        val isConnectionVerified: Boolean = false,
        // ISSUE-P2-285 AC②：同步凭据封印密钥实测为硬件级（TEE / StrongBox）——
        // 设置页「凭据封印」声明按此条件渲染（false = 软件级 / 未生成，如实降级呈现）
        val syncSealHardwareBacked: Boolean = false
    )

    private val syncStateFlow = MutableStateFlow(
        SyncUiState(
            provider = CloudSyncProvider.WEBDAV,
            wifiOnlySync = true,
            isSyncing = false,
            syncFeedbackMessage = null,
            // ISSUE-P2-406：构造期**禁止**探测 Keystore——导航组合线程上不得做硬件级查询；
            // 实测值由 hydrate / save / restore 路径在后台派发器刷新（ISSUE-P2-285 语义不变）
            syncSealHardwareBacked = false
        )
    )

    private val credentialForm = SettingsSyncCredentialForm(syncCredentialsStore, syncStateFlow)
    /** 保存回执与预填通道的归属（拆分自本类，语义原样保留）。 */
    private val configSaver = SettingsSyncConfigSaver(syncStateFlow, credentialForm)
    /** 「保存后测连 + 同步」的编排（拆分自本类，语义原样保留）。 */
    private val savedFlow = SettingsSyncSavedFlow(
        coordinator = syncCoordinator,
        state = syncStateFlow,
        scope = scope,
        configSaver = configSaver,
        onFailure = ::onSyncFailure,
        onRecoveryRefresh = { refreshSyncRecoveryRequired() },
        formatTimestamp = ::formatSyncTimestamp,
        clearBusyIfStillBusy = ::clearSyncBusyIfStillBusy
    )
    /** 待恢复采纳的探测与用户处置（拆分自本类，语义原样保留）。 */
    private val recovery = SettingsSyncRecovery(
        syncCoordinator = syncCoordinator,
        state = syncStateFlow,
        strings = strings,
        scope = scope,
        onFailure = ::onSyncFailure,
        formatTimestamp = ::formatSyncTimestamp
    )

    /**
     * ISSUE-P2-406：同步 UI 水合（wifi 偏好回填 + 凭据预填恢复 + 封印等级探测）。
     *
     * **必须在后台派发器执行**：`restoreSyncCredentials` 含 Keystore AES-GCM 解密，
     * 阻塞导航组合线程会表现为「点进设置二级页卡一下」。仅同步页需要；其余设置页零成本。
     */
    fun hydrate() {
        scope.launch(Dispatchers.IO) {
            updateWifiOnlySync(extendedSettingsStore.loadWifiOnlySync())
            restoreSyncCredentials()
            refreshSyncRecoveryRequired()
        }
    }

    private suspend fun refreshSyncRecoveryRequired() = recovery.refresh()

    /** User-confirmed recovery: retain the current local database and conditionally publish it. */
    fun recoverPendingSyncByKeepingLocal() = recovery.keepLocal()

    val state: StateFlow<SyncUiState> = syncStateFlow.asStateFlow()

    /** 明文一次性预填通道与配置保存回执（见 [SettingsSyncConfigSaver]）。 */
    val webdavPasswordPrefill: StateFlow<CharArray?> get() = configSaver.webdavPasswordPrefill
    val s3SecretKeyPrefill: StateFlow<CharArray?> get() = configSaver.s3SecretKeyPrefill
    val s3AccessKeyPrefill: StateFlow<CharArray?> get() = configSaver.s3AccessKeyPrefill

    fun clearWebDavPasswordPrefill() = configSaver.clearWebDavPasswordPrefill()

    fun clearS3SecretKeyPrefill() = configSaver.clearS3SecretKeyPrefill()

    /** ISSUE-P2-01：用户开始编辑 AccessKey 后终结预填通道生命周期（语义同上） */
    fun clearS3AccessKeyPrefill() = configSaver.clearS3AccessKeyPrefill()

    /** Wave 15 / ISSUE-P2-01：凭据恢复（语义见 [SettingsSyncCredentialForm.restoreSyncCredentials]）。 */
    fun restoreSyncCredentials() = configSaver.restore()

    fun setSyncProvider(provider: CloudSyncProvider) = configSaver.setProvider(provider)

    /** Wave 15 / Wave 14：WebDAV 配置保存（借用语义 + HTTPS 强制校验），返回是否成功。 */
    fun updateWebDavConfig(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String
    ): Boolean = configSaver.updateWebDav(url, username, password, remotePath)

    /** Wave 15 / ISSUE-P2-01：S3 配置保存（AccessKey 与 SecretKey 均借用语义），返回是否成功。 */
    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = syncStateFlow.value.s3UsePathStyle
    ): Boolean = configSaver.updateS3(endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle)

    /**
     * ISSUE-P3-272：自动同步总开关改走进阶偏好统一通道（内存快照 + 持久化原子完成）——
     * 原实现只写本控制器内存 StateFlow（不落盘、无消费方），重启即回默认值的假开关。
     * 真实消费点为 `VaultListViewModel` init 的「解锁后自动同步一次」触发点。
     */
    fun setAutoSyncEnabled(enabled: Boolean) {
        val next = extendedSettingsStore.settings.value.copy(autoSyncEnabled = enabled)
        extendedSettingsStore.publish(next)
        extendedSettingsStore.save(next)
    }

    /** 内存态即时更新（持久化与周期任务重排由 ViewModel 统一编排） */
    fun updateWifiOnlySync(enabled: Boolean) {
        syncStateFlow.update { it.copy(wifiOnlySync = enabled) }
    }

    fun currentWifiOnlySync(): Boolean = syncStateFlow.value.wifiOnlySync

    fun triggerSync() {
        if (syncStateFlow.value.isSyncing) return
        val provider = syncStateFlow.value.provider
        // 本轮开始时的保存修订：期间若又保存了新配置，本轮的成功结论只对旧配置成立，
        // 不得把新配置标成已验证（ISSUE-P2-652 的同一判据，普通同步入口此前缺失）。
        val revisionAtStart = configSaver.latestRevisionOrNull()
        // ISSUE-P0-531：同步编排段异常不得逃逸杀进程（见 launchGuarded KDoc），失败即复位 isSyncing
        scope.launchGuarded(onFailure = ::onSyncFailure) {
            syncStateFlow.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_feedback_connecting, listOf(provider.protocol))
                )
            }
            try {
                val outcome = syncCoordinator.syncNow()
                refreshSyncRecoveryRequired()
                val feedback = syncOutcomeFeedback(outcome, provider.protocol)
                // H1 整改：syncLastTime 由真实同步完成时刻填充，不再展示写死的演示文案
                val syncedNow = outcome.isSuccessfulSync()
                val revisionStillCurrent = configSaver.matchesLatest(revisionAtStart)
                syncStateFlow.update {
                    it.copy(
                        isSyncing = false,
                        syncFeedbackMessage = feedback,
                        lastSyncTimeText = if (syncedNow) formatSyncTimestamp() else it.lastSyncTimeText,
                        // 成功同步即视为连接可用；失败/冲突/离线不置位。
                        // 迟到的成功结论不得把期间新保存的配置标成已验证。
                        isConnectionVerified = if (syncedNow && revisionStillCurrent) {
                            true
                        } else {
                            it.isConnectionVerified
                        }
                    )
                }
            } finally {
                // 协程取消（锁库 / 作用域销毁）不经 onFailure；不在这里复位，指示器会永久停在「同步中」。
                clearSyncBusyIfStillBusy()
            }
        }
    }

    /** 取消路径的忙态复位：只在仍为 true 时写回，避免覆盖并发收尾已写入的终态。 */
    private fun clearSyncBusyIfStillBusy() {
        syncStateFlow.update { if (it.isSyncing) it.copy(isSyncing = false) else it }
    }

    /**
     * `ISSUE-P0-531`：同步 / 连接测试编排段的**未预期异常**如实上浮（脱敏：只用异常类名，
     * 沿用 `SyncCycleRunner` 报错文案的既有口径），并复位 `isSyncing` —— 否则一次失败会让
     * 设置页指示器永久停在「同步中」。
     */
    private fun onSyncFailure(e: Throwable) {
        syncStateFlow.update {
            it.copy(
                isSyncing = false,
                syncFeedbackMessage = UiMessage(R.string.sync_feedback_error, listOf(e.javaClass.simpleName))
            )
        }
    }

    /**
     * 将本次同步完成时刻格式化为「今天/昨天/M月d日 HH:mm」本地文案（文案经资源解析）。
     *
     * ISSUE-P3-552：判据与文案统一收敛到 [RelativeTimeFormatter]（另两处同批收敛）。
     */
    fun formatSyncTimestamp(): String =
        RelativeTimeFormatter.formatInstant(strings, java.time.Instant.now())

    /**
     * 测试连接。
     *
     * @param onResult 测试完成后的回调（参数为是否通过）。顺序编排入口（verifyConnectionThenSync）
     *   在回调里再触发同步；本方法本身**不**自动同步，避免与编排层双跑。
     */
    fun testSyncConnection(onResult: ((Boolean) -> Unit)? = null) {
        if (syncStateFlow.value.isSyncing) return
        val provider = syncStateFlow.value.provider
        // ISSUE-P0-531：连接测试编排段异常不得逃逸；异常一律按「未通过」上报顺序编排层
        scope.launchGuarded(
            onFailure = { e ->
                onSyncFailure(e)
                onResult?.invoke(false)
            }
        ) {
            syncStateFlow.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_feedback_connecting, listOf(provider.protocol))
                )
            }
            val result = syncCoordinator.testConnection()
            val verified = result.isSuccess
            val feedback = if (verified) {
                UiMessage(
                    R.string.sync_feedback_done,
                    listOf(provider.protocol),
                    durationMillis = UiMessage.SYNC_STATUS_DURATION_MS
                )
            } else {
                UiMessage(
                    R.string.sync_feedback_error,
                    // ISSUE-P3-311 项 3：测试连接的异常 message 可能内嵌服务器响应内容，只出固定文案
                    listOf(strings.get(R.string.sync_test_connection_failed))
                )
            }
            syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncFeedbackMessage = feedback,
                    isConnectionVerified = verified
                )
            }
            onResult?.invoke(verified)
        }
    }

    /**
     * 上浮一条同步反馈文案。
     *
     * 供编排层在「前置守卫未通过」这类分支给出**明确结论**（如「连接测试未通过，已跳过同步」），
     * 避免顺序动作在半途静默中止、用户只看到前一步的提示而不知道整体结果。
     */
    fun publishFeedback(message: UiMessage) {
        syncStateFlow.update { it.copy(syncFeedbackMessage = message) }
    }

    /**
     * 「保存并同步」的**顺序编排**：保存已成功 →（未验证时先）测试连接 → 通过则同步。
     *
     * 立规缘由：WebDAV / S3 配置页把「保存配置」「测试连接」「立即同步」做成三个独立入口，
     * 而「立即同步」在未验证连接时禁用（`enabled = !isSyncing && isConnectionVerified`，
     * 见 `CloudSyncComponents.kt` 的就地注释）⇒ 填完配置想让它生效，最少是
     * 「保存(1) → 测试连接(2) → 立即同步(3)」三次点击。
     *
     * 该守卫**不变**，但由「解锁同步按钮的条件」改为**顺序动作的前置步骤**：
     * 1. **同步走已保存的配置**——本方法**只**在前一步保存成功后才被调用，且自身不接收任何
     *    表单实参（凭据 `CharArray` 已在保存时被消费擦除），故不存在「拿表单内存态去同步」的路径；
     * 2. **已验证则跳过重复测试**，直接同步；
     * 3. **任一环节失败即停并上浮**：测试未通过时明确提示「已跳过同步」，绝不静默中止；
     * 4. 忙态（`isSyncing`）由控制器既有守卫拦截，不新增并发面。
     *
     * ISSUE-P3-257：实现体随 KDoc 自 `SettingsViewModel` 整体下沉，公开 API 形状不变。
     */
    fun verifyConnectionThenSync() {
        val savedSnapshot = configSaver.consumeSnapshot()
        if (savedSnapshot != null) {
            verifyAndSyncSavedConfiguration(savedSnapshot)
            return
        }
        val state = syncStateFlow.value
        if (state.isSyncing) return
        if (state.isConnectionVerified) {
            triggerSync()
            return
        }
        testSyncConnection { verified ->
            if (verified) {
                triggerSync()
            } else {
                publishFeedback(UiMessage(R.string.sync_gate_test_failed))
            }
        }
    }

    /** ISSUE-P2-652：以保存回执执行测连 + 同步（实现见 [SettingsSyncSavedFlow]）。 */
    private fun verifyAndSyncSavedConfiguration(snapshot: SyncProviderConfigSnapshot) =
        savedFlow.run(snapshot)

    fun clearSyncFeedbackMessage() {
        syncStateFlow.update { it.copy(syncFeedbackMessage = null) }
    }
}
