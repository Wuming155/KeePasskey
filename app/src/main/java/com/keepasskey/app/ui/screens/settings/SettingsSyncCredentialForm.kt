package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.sync.SyncProviderConfigSnapshot
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 云端同步「凭据表单」面（`ISSUE-P2-645`：自 [SettingsSyncController] 拆分，纯结构性）。
 *
 * 承担：WebDAV / S3 配置的保存与校验（Wave 14 / 15 语义）、明文一次性预填通道、
 * 封印密钥硬件落位实测。状态流由控制器注入并共享，`SyncUiState` 结构未变。
 *
 * 不变量：明文只经 [CharArray] 预填通道下发；每次消费后立即擦除，绝不回写状态流。
 */
internal class SettingsSyncCredentialForm(
    private val syncCredentialsStore: SyncCredentialsStore,
    private val state: MutableStateFlow<SettingsSyncController.SyncUiState>
) {

    /** ISSUE-P2-285：实测封印密钥落位（判据落点唯一，与解锁面 SOFTWARE / UNKNOWN 同判）。 */
    private fun probeSealHardwareBacked(): Boolean =
        when (syncCredentialsStore.syncSealSecurityLevel()) {
            com.keepasskey.app.security.KeystoreManager.KeySecurityLevel.STRONGBOX,
            com.keepasskey.app.security.KeystoreManager.KeySecurityLevel.TRUSTED_ENVIRONMENT -> true
            else -> false
        }

    /** ISSUE-P2-285：封印密钥可能在保存后新生成 / 轮换，保存成功路径后重测。 */
    fun refreshSealHardwareBacked() {
        state.update { it.copy(syncSealHardwareBacked = probeSealHardwareBacked()) }
    }

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
     * 不再以 String 驻留状态流；WebDAV 密码与 S3 SecretKey 经预填通道下发至
     * SecurePasswordField。ISSUE-P2-01：S3 AccessKey ID 亦经独立 CharArray 预填通道下发，
     * 不再以 String 投影驻留状态流。
     */
    fun restoreSyncCredentials() {
        val store = syncCredentialsStore
        // ISSUE-P2-285：进入设置页即重测封印密钥落位（覆盖历史会话生成的密钥）
        refreshSealHardwareBacked()
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
        state.update { cur ->
            cur.copy(
                provider = savedProvider,
                // 已保存配置优先；无配置时预填坚果云端点，避免每次重敲示例 URL
                webdavUrl = savedWebDav?.url?.takeIf { it.isNotBlank() }
                    ?: com.keepasskey.app.sync.WebDavDefaults.NUTSTORE_URL,
                webdavUsername = savedWebDav?.username ?: cur.webdavUsername,
                webdavRemotePath = savedWebDav?.remotePath?.takeIf { it.isNotBlank() }
                    ?: com.keepasskey.app.sync.WebDavDefaults.DEFAULT_REMOTE_PATH,
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
        state.update { it.copy(provider = provider, isConnectionVerified = false) }
    }

    /**
     * Wave 15 整改：密码以 [CharArray] **所有权转移**方式提交——表单传入的是自己草稿的独立副本
     * （见 `withSyncCredentialSubmissionCopy`），本方法消费并擦除的是该副本，不会动表单草稿；
     * 明文不再回写状态流。返回保存结果——Wave 14 https 校验拒绝或 Wave 15 凭据封印失败时
     * 如实回传 false 并上浮反馈，不再无条件谎报「已保存」。
     */
    fun updateWebDavConfig(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String,
        onSavedSnapshot: (SyncProviderConfigSnapshot) -> Unit = {}
    ): Boolean {
        // Wave 14 全站强制 HTTPS：保存时即时校验端点（fail-fast），
        // 显式 http:// 直接拒绝并反馈；无 scheme 输入自动归一化为 https://
        val normalizedUrl = normalizeHttpsEndpoint(url)
        if (normalizedUrl == null) {
            state.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_error_https_required, listOf("WebDAV")))
            }
            password.fill('0')
            return false
        }
        val snapshot = syncCredentialsStore.saveWebDavConfigWithSnapshot(
            normalizedUrl, username, password, remotePath
        )
        password.fill('0')
        if (snapshot == null) {
            state.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_config_save_failed))
            }
            return false
        }
        onSavedSnapshot(snapshot)
        state.update {
            it.copy(
                webdavUrl = normalizedUrl,
                webdavUsername = username,
                webdavRemotePath = remotePath,
                // 配置变更后须重新验证连接
                isConnectionVerified = false,
                // 保存成功须清掉上一次失败留下的提示，避免「已保存」与「保存失败」同时可见
                syncFeedbackMessage = null
            )
        }
        // 保存成功后旧预填通道失效（最新凭据已由存储库持有，重进页面将重新恢复）
        _webdavPasswordPrefill.value?.fill('0')
        _webdavPasswordPrefill.value = null
        // ISSUE-P2-285：封印密钥可能本次保存新生成，重测硬件落位
        refreshSealHardwareBacked()
        return true
    }

    /**
     * Wave 15 整改：SecretKey 以 [CharArray] **所有权转移**方式提交（同上：提交方传入独立副本，
     * 擦除的是副本而非表单草稿），明文不再回写状态流；
     * ISSUE-P2-01：AccessKey ID 同法（消费后即擦除提交副本，不再驻留状态流）。
     * 返回保存结果（语义同 [updateWebDavConfig]）。
     */
    fun updateS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = state.value.s3UsePathStyle,
        onSavedSnapshot: (SyncProviderConfigSnapshot) -> Unit = {}
    ): Boolean {
        // Wave 14 全站强制 HTTPS：与 WebDAV 一致的保存期端点校验
        val normalizedEndpoint = normalizeHttpsEndpoint(endpoint)
        if (normalizedEndpoint == null) {
            state.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_error_https_required, listOf("S3")))
            }
            accessKey.fill('0')
            secretKey.fill('0')
            return false
        }
        // AccessKey ID 与 SecretKey 均由提交方转入所有权：存储库封印时擦除其副本，此处兜底再擦一次
        val snapshot = syncCredentialsStore.saveS3ConfigWithSnapshot(
            normalizedEndpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle
        )
        accessKey.fill('0')
        secretKey.fill('0')
        if (snapshot == null) {
            state.update {
                it.copy(syncFeedbackMessage = UiMessage(R.string.sync_config_save_failed))
            }
            return false
        }
        onSavedSnapshot(snapshot)
        state.update {
            it.copy(
                s3Endpoint = normalizedEndpoint,
                s3Bucket = bucket,
                s3Region = region,
                s3ObjectKey = objectKey,
                s3UsePathStyle = usePathStyle,
                // 配置变更后须重新验证连接
                isConnectionVerified = false,
                // 保存成功须清掉上一次失败留下的提示（语义同 WebDAV 分支）
                syncFeedbackMessage = null
            )
        }
        // 保存成功后旧预填通道失效（最新凭据已由存储库持有，重进页面将重新恢复）
        _s3AccessKeyPrefill.value?.fill('0')
        _s3AccessKeyPrefill.value = null
        _s3SecretKeyPrefill.value?.fill('0')
        _s3SecretKeyPrefill.value = null
        // ISSUE-P2-285：封印密钥可能本次保存新生成，重测硬件落位
        refreshSealHardwareBacked()
        return true
    }

    /**
     * Wave 14 全站强制 HTTPS：端点归一化与校验。
     * ISSUE-P2-399：实现抽出至 [HttpsEndpointPolicy] 单点（云端打开链路共用同一语义），此处委托。
     * 空串原样返回（允许清空配置）；无 scheme 输入自动补 https://；
     * 显式非 https scheme（http:// 等）返回 null 表示拒绝保存。
     */
    private fun normalizeHttpsEndpoint(raw: String): String? =
        com.keepasskey.app.sync.HttpsEndpointPolicy.normalize(raw)
}
