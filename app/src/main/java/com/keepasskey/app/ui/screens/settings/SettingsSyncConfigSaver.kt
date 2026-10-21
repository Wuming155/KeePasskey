package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.sync.SyncProviderConfigSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 同步凭据表单的保存回执归属（`ISSUE-P2-645` 拆分自 `SettingsSyncController`，纯搬运）。
 *
 * 持「最近一次成功保存的密封回执」与「当前修订」，供随后的测试连接 + 同步使用同一份快照；
 * 任何新的保存、恢复或提供商切换都先作废旧回执，避免用先前配置验证或同步后来目标。
 */
internal class SettingsSyncConfigSaver(
    private val state: MutableStateFlow<SettingsSyncController.SyncUiState>,
    private val credentialForm: SettingsSyncCredentialForm
) {
    private var pendingSnapshot: SyncProviderConfigSnapshot? = null
    private var latestRevision: String? = null

    val webdavPasswordPrefill: StateFlow<CharArray?> get() = credentialForm.webdavPasswordPrefill
    val s3SecretKeyPrefill: StateFlow<CharArray?> get() = credentialForm.s3SecretKeyPrefill
    val s3AccessKeyPrefill: StateFlow<CharArray?> get() = credentialForm.s3AccessKeyPrefill

    fun clearWebDavPasswordPrefill() = credentialForm.clearWebDavPasswordPrefill()
    fun clearS3SecretKeyPrefill() = credentialForm.clearS3SecretKeyPrefill()
    fun clearS3AccessKeyPrefill() = credentialForm.clearS3AccessKeyPrefill()

    fun restore() {
        reset()
        credentialForm.restoreSyncCredentials()
    }

    fun setProvider(provider: CloudSyncProvider) {
        reset()
        credentialForm.setSyncProvider(provider)
    }

    fun updateWebDav(url: String, username: String, password: CharArray, remotePath: String): Boolean {
        reset()
        return credentialForm.updateWebDavConfig(url, username, password, remotePath) { remember(it) }
    }

    fun updateS3(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = state.value.s3UsePathStyle
    ): Boolean {
        reset()
        return credentialForm.updateS3Config(
            endpoint, bucket, region, accessKey, secretKey, objectKey, usePathStyle
        ) { remember(it) }
    }

    /** 取出并作废本次保存回执——只允许被一次「测试 + 同步」编排消费。 */
    fun consumeSnapshot(): SyncProviderConfigSnapshot? = pendingSnapshot.also { pendingSnapshot = null }

    /** 该修订是否仍是最近一次成功保存（迟到的旧结果不得标记新版已验证）。 */
    fun isLatest(revision: String): Boolean = latestRevision == revision

    /** 本轮编排开始时的修订；与 [matchesLatest] 配对判断「期间是否又保存了新配置」。 */
    fun latestRevisionOrNull(): String? = latestRevision

    /**
     * 起始修订是否仍是当前修订（含「起始时尚未保存任何配置」这一情形）。
     *
     * 不用 `isLatest(revision)` 直接替代：起始值为 null 时那会退化成「当前也无保存」才成立，
     * 而普通同步在从未保存过配置时同样要走成功结论。
     */
    fun matchesLatest(revisionAtStart: String?): Boolean = revisionAtStart == latestRevision

    private fun remember(snapshot: SyncProviderConfigSnapshot) {
        pendingSnapshot = snapshot
        latestRevision = snapshot.revision
    }

    private fun reset() {
        pendingSnapshot = null
        latestRevision = null
    }
}
