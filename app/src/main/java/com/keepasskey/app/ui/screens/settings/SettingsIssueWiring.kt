package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileDriftPrompt
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ISSUE-P2-379 / P2-378 / P3-381 / P3-382 / P3-385：设置页新增门面与外部修改待决状态。
 * 自 SettingsViewModel 拆出，控 tier1 行数；ViewModel 仅做单语句委托。
 */
internal class SettingsIssueWiring(
    private val vaultRepository: VaultRepository,
    private val vaultFileDriftCoordinator: VaultFileDriftCoordinator?,
    private val preferencesController: SettingsPreferencesController
) {
    private val _pending = MutableStateFlow<VaultFileDriftPrompt?>(null)
    val pending: StateFlow<VaultFileDriftPrompt?> = _pending.asStateFlow()

    private val _databaseMetaFeedback = MutableStateFlow<String?>(null)
    val databaseMetaFeedback: StateFlow<String?> = _databaseMetaFeedback.asStateFlow()

    fun publishPending(prompt: VaultFileDriftPrompt?) {
        _pending.value = prompt
    }

    fun clearDatabaseMetaFeedback() {
        _databaseMetaFeedback.value = null
    }

    fun setAutoLockForegroundEnabled(enabled: Boolean) =
        preferencesController.databaseMetaController.setAutoLockForegroundEnabled(enabled)

    fun setAutoLockForegroundTimeoutSeconds(seconds: Int) =
        preferencesController.databaseMetaController.setAutoLockForegroundTimeoutSeconds(seconds)

    fun setSyncProbeOnResumeEnabled(enabled: Boolean) =
        preferencesController.databaseMetaController.setSyncProbeOnResumeEnabled(enabled)

    fun setDatabaseMeta(
        databaseName: String?,
        databaseDescription: String?,
        defaultUserName: String?
    ) = preferencesController.databaseMetaController.setDatabaseMeta(
        databaseName,
        databaseDescription,
        defaultUserName
    )

    fun scanDuplicateEntries() = preferencesController.databaseMetaController.scanDuplicateEntries()

    suspend fun applyExternalModificationChoice(
        choice: com.keepasskey.app.security.ExternalModificationChoice
    ): KdbxResult<Unit> {
        val result = vaultRepository.applyExternalModificationChoice(choice)
        vaultFileDriftCoordinator?.dismiss()
        return result
    }
}
