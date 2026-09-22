package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.StringsProvider
import kotlinx.coroutines.CoroutineScope
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
 */
internal class SettingsSyncController(
    private val syncCredentialsStore: SyncCredentialsStore,
    private val syncCoordinator: SyncCoordinator,
    private val extendedSettingsStore: ExtendedSettingsStore,
    private val strings: StringsProvider,
    private val scope: CoroutineScope
) {

    /** 同步配置状态（含表单回显与同步过程反馈） */
    internal data class SyncUiState(
        val provider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
        // M2 整改：默认值一律空串，杜绝示例凭据（mypassword123 / AKIA 示例密钥对）
        // 被静默保存为真实云端凭据
        // Wave 15 整改：webdavPassword/s3SecretKey 明文不再驻留本状态流
        // （经 CharArray 一次性预填通道下发，保存后即擦除）
        // ISSUE-P2-01：s3AccessKey 同步改造——AccessKey ID 亦不再以 String 驻留本状态流，
        // 与 SecretKey 同走 CharArray 一次性预填通道
        val webdavUrl: String = "",
        val webdavUsername: String = "",
        val webdavRemotePath: String = "/keepasskey.kdbx",
        val s3Endpoint: String = "",
        val s3Bucket: String = "",
        val s3Region: String = "auto",
        val s3ObjectKey: String = "keepasskey.kdbx",
        val s3UsePathStyle: Boolean = false,
        val autoSyncEnabled: Boolean = true,
        val wifiOnlySync: Boolean = true,
        val isSyncing: Boolean = false,
        val syncFeedbackMessage: UiMessage? = null,
        // H1 整改：真实同步完成时刻文案（空串=本会话尚未同步成功过）
        val lastSyncTimeText: String = "",
        // 连接是否已验证（测试连接或同步成功后置位；配置变更后复位）
        val isConnectionVerified: Boolean = false
    )

    private val syncStateFlow = MutableStateFlow(
        SyncUiState(
            provider = CloudSyncProvider.WEBDAV,
            autoSyncEnabled = true,
            wifiOnlySync = true,
            isSyncing = false,
            syncFeedbackMessage = null
        )
    )

    val state: StateFlow<SyncUiState> = syncStateFlow.asStateFlow()

    // Wave 15 整改：同步凭据明文一次性预填通道（对齐 EntryEdit loadedPassword 模式）。
    // 解密结果以 CharArray 承载、绝不进入 UiState/StateFlow；组件消费（或用户开始编辑、
    // 保存成功、ViewModel 销毁）后即擦除置空。
    private val _webdavPasswordPrefill = MutableStateFlow<CharArray?>(null)
    val webdavPasswordPrefill: StateFlow<CharArray?> = _webdavPasswordPrefill.asStateFlow()

    private val _s3SecretKeyPrefill = MutableStateFlow<CharArray?>(null)
    val s3SecretKeyPrefill: StateFlow<CharArray?> = _s3SecretKeyPrefill.asStateFlow()

    // ISSUE-P2-01：S3 AccessKey ID 预填通道（语义同 SecretKey 通道）
    private val _s3AccessKeyPrefill = MutableStateFlow<CharArray?>(null)
    val s3AccessKeyPrefill: StateFlow<CharArray?> = _s3AccessKeyPrefill.asStateFlow()

    /** Wave 15 整改：用户开始编辑密码后终结预填通道生命周期（防旋转后旧值回写覆盖用户输入） */
    fun clearWebDavPasswordPrefill() {
        _webdavPasswordPrefill.value?.fill('0')
        _webdavPasswordPrefill.value = null
    }

    fun clearS3SecretKeyPrefill() {
        _s3SecretKeyPrefill.value?.fill('0')
        _s3SecretKeyPrefill.value = null
    }

    /** ISSUE-P2-01：用户开始编辑 AccessKey 后终结预填通道生命周期（语义同上） */
    fun clearS3AccessKeyPrefill() {
        _s3AccessKeyPrefill.value?.fill('0')
        _s3AccessKeyPrefill.value = null
    }

    /**
     * Wave 15 整改：凭据恢复改走 CharArray 一次性预填通道——解密出的密码/SecretKey
     * 不再以 String 驻留 syncStateFlow；WebDAV 密码与 S3 SecretKey 经预填通道下发至
     * SecurePasswordField。ISSUE-P2-01：S3 AccessKey ID 亦经独立 CharArray 预填通道下发，
     * 不再以 String 投影驻留状态流。
     */
    fun restoreSyncCredentials() {
        val store = syncCredentialsStore
        val savedProvider = store.loadProvider()
        val savedWebDav = store.loadWebDavConfig()
        val savedS3 = store.loadS3Config()
        savedWebDav?.let { cfg ->
            _webdavPasswordPrefill.value?.fill('0')
            _webdavPasswordPrefill.value = cfg.password
        }
        savedS3?.let { cfg ->
            _s3AccessKeyPrefill.value?.fill('0')
            _s3AccessKeyPrefill.value = cfg.accessKey
            _s3SecretKeyPrefill.value?.fill('0')
            _s3SecretKeyPrefill.value = cfg.secretKey
        }
        syncStateFlow.update { cur ->
            cur.copy(
                provider = savedProvider,
                webdavUrl = savedWebDav?.url ?: cur.webdavUrl,
                webdavUsername = savedWebDav?.username ?: cur.webdavUsername,
                webdavRemotePath = savedWebDav?.remotePath ?: cur.webdavRemotePath,
                s3Endpoint = savedS3?.endpoint ?: cur.s3Endpoint,
                s3Bucket = savedS3?.bucket ?: cur.s3Bucket,
                s3Region = savedS3?.region ?: cur.s3Region,
                s3ObjectKey = savedS3?.objectKey ?: cur.s3ObjectKey,
                s3UsePathStyle = savedS3?.usePathStyle ?: cur.s3UsePathStyle
            )
        }
    }

    fun setSyncProvider(provider: CloudSyncProvider) {
        syncCredentialsStore.saveProvider(provider)
        // 切换提供商后旧连接验证结论不再适用
        syncStateFlow.update { it.copy(provider = provider, isConnectionVerified = false) }
    }

    /**
     * Wave 15 整改：密码以 [CharArray] 借用语义提交（本方法消费后立即擦除），明文不再回写状态流；
     * 返回保存结果——Wave 14 https 校验拒绝或 Wave 15 凭据封印失败时如实回传 false 并上浮反馈，
     * 不再无条件谎报「已保存」。
     */
    fun updateWebDavConfig(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String
    ): Boolean {
        // Wave 14 全站强制 HTTPS：保存时即时校验端点（fail-fast），
        // 显式 http:// 直接拒绝并反馈；无 scheme 输入自动归一化为 https://
        val normalizedUrl = normalizeHttpsEndpoint(url)
        if (normalizedUrl == null) {
            syncStateFlow.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_error_https_required, listOf("WebDAV")))
            }
            password.fill('0')
            return false
        }
        val saved = syncCredentialsStore.saveWebDavConfig(normalizedUrl, username, password, remotePath)
        password.fill('0')
        if (!saved) {
            syncStateFlow.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_config_save_failed))
            }
            return false
        }
        syncStateFlow.update {
            it.copy(
                webdavUrl = normalizedUrl,
                webdavUsername = username,
                webdavRemotePath = remotePath,
                // 配置变更后须重新验证连接
                isConnectionVerified = false
            )
        }
        // 保存成功后旧预填通道失效（最新凭据已由存储库持有，重进页面将重新恢复）
        _webdavPasswordPrefill.value?.fill('0')
        _webdavPasswordPrefill.value = null
        return true
    }

    /**
     * Wave 15 整改：SecretKey 以 [CharArray] 借用语义提交（本方法消费后立即擦除），明文不再回写状态流；
     * ISSUE-P2-01：AccessKey ID 亦改为 [CharArray] 借用语义（消费后即擦除，不再驻留状态流）。
     * 返回保存结果（语义同 [updateWebDavConfig]）。
     */
    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = syncStateFlow.value.s3UsePathStyle
    ): Boolean {
        // Wave 14 全站强制 HTTPS：与 WebDAV 一致的保存期端点校验
        val normalizedEndpoint = normalizeHttpsEndpoint(endpoint)
        if (normalizedEndpoint == null) {
            syncStateFlow.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_error_https_required, listOf("S3")))
            }
            accessKey.fill('0')
            secretKey.fill('0')
            return false
        }
        // AccessKey ID 与 SecretKey 均为借用语义：存储库封印后由库内统一擦除，此处兜底再擦一次
        val saved = syncCredentialsStore.saveS3Config(
            normalizedEndpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle
        )
        accessKey.fill('0')
        secretKey.fill('0')
        if (!saved) {
            syncStateFlow.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_config_save_failed))
            }
            return false
        }
        syncStateFlow.update {
            it.copy(
                s3Endpoint = normalizedEndpoint,
                s3Bucket = bucket,
                s3Region = region,
                s3ObjectKey = objectKey,
                s3UsePathStyle = usePathStyle,
                // 配置变更后须重新验证连接
                isConnectionVerified = false
            )
        }
        // 保存成功后旧预填通道失效（最新凭据已由存储库持有，重进页面将重新恢复）
        _s3AccessKeyPrefill.value?.fill('0')
        _s3AccessKeyPrefill.value = null
        _s3SecretKeyPrefill.value?.fill('0')
        _s3SecretKeyPrefill.value = null
        return true
    }

    /**
     * Wave 14 全站强制 HTTPS：端点归一化与校验。
     * 空串原样返回（允许清空配置）；无 scheme 输入自动补 https://；
     * 显式非 https scheme（http:// 等）返回 null 表示拒绝保存。
     */
    private fun normalizeHttpsEndpoint(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return if (withScheme.startsWith("https://", ignoreCase = true)) withScheme else null
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        syncStateFlow.update { it.copy(autoSyncEnabled = enabled) }
    }

    /** 内存态即时更新（持久化与周期任务重排由 ViewModel 统一编排） */
    fun updateWifiOnlySync(enabled: Boolean) {
        syncStateFlow.update { it.copy(wifiOnlySync = enabled) }
    }

    fun currentWifiOnlySync(): Boolean = syncStateFlow.value.wifiOnlySync

    fun triggerSync() {
        if (syncStateFlow.value.isSyncing) return
        val provider = syncStateFlow.value.provider
        scope.launch {
            syncStateFlow.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_feedback_connecting, listOf(provider.protocol))
                )
            }

            val outcome = syncCoordinator.syncNow()
            val feedback = when (outcome) {
                is SyncOutcome.UpToDate -> UiMessage(R.string.sync_feedback_done, listOf(provider.protocol))
                is SyncOutcome.UploadedLocal -> UiMessage(R.string.sync_feedback_uploaded, listOf(provider.protocol))
                is SyncOutcome.MergedAndUploaded -> UiMessage(R.string.sync_feedback_merged, listOf(provider.protocol))
                is SyncOutcome.ConflictNeedsUser -> UiMessage(R.string.sync_feedback_conflict)
                is SyncOutcome.Offline -> UiMessage(R.string.sync_feedback_offline)
                is SyncOutcome.Error -> UiMessage(R.string.sync_feedback_error, listOf(outcome.message))
            }
            // H1 整改：syncLastTime 由真实同步完成时刻填充，不再展示写死的演示文案
            val syncedNow = outcome is SyncOutcome.UpToDate ||
                    outcome is SyncOutcome.UploadedLocal ||
                    outcome is SyncOutcome.MergedAndUploaded
            syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncFeedbackMessage = feedback,
                    lastSyncTimeText = if (syncedNow) formatSyncTimestamp() else syncStateFlow.value.lastSyncTimeText,
                    // 成功同步即视为连接可用；失败/冲突/离线不置位
                    isConnectionVerified = if (syncedNow) true else it.isConnectionVerified
                )
            }
        }
    }

    /** 将本次同步完成时刻格式化为「今天/昨天/M月d日 HH:mm」本地文案（文案经资源解析） */
    fun formatSyncTimestamp(): String {
        val dateTime = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val datePrefix = when (dateTime.toLocalDate()) {
            today -> strings.get(R.string.time_today)
            today.minusDays(1) -> strings.get(R.string.time_yesterday)
            else -> dateTime.format(java.time.format.DateTimeFormatter.ofPattern(strings.get(R.string.date_pattern_month_day)))
        }
        return "$datePrefix ${dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    /**
     * 测试连接。
     *
     * @param onResult 测试完成后的回调（参数为是否通过）。用于把「测试通过才继续下一步」这类
     *   **顺序动作**的编排收在本层（如「保存并同步」：保存 → 测试 → 通过才同步）。
     *   回调在**状态写入之后、同一协程内**执行，因此在回调里直接调用 [triggerSync]
     *   不会撞上其 `isSyncing` 早退守卫（此刻已置回 false）。
     */
    fun testSyncConnection(onResult: ((Boolean) -> Unit)? = null) {
        if (syncStateFlow.value.isSyncing) return
        val provider = syncStateFlow.value.provider
        scope.launch {
            syncStateFlow.update {
                it.copy(
                    isSyncing = true,
                    syncFeedbackMessage = UiMessage(R.string.sync_feedback_connecting, listOf(provider.protocol))
                )
            }
            val result = syncCoordinator.testConnection()
            val verified = result.isSuccess
            val feedback = if (verified) {
                UiMessage(R.string.sync_feedback_done, listOf(provider.protocol))
            } else {
                UiMessage(
                    R.string.sync_feedback_error,
                    listOf(result.exceptionOrNull()?.message ?: strings.get(R.string.sync_test_connection_failed))
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

    fun clearSyncFeedbackMessage() {
        syncStateFlow.update { it.copy(syncFeedbackMessage = null) }
    }
}
