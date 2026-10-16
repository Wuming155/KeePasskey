package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileDriftMergeConflict
import com.keepasskey.app.security.VaultFileDriftPrompt
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ISSUE-P2-379 / P2-378 / P3-381 / P3-382 / P3-385：设置页新增门面与外部修改待决状态。
 * 自 SettingsViewModel 拆出，控 tier1 行数；ViewModel 仅做单语句委托。
 *
 * `ISSUE-P1-631`：追加合并冲突待决裁决状态（漂移合并发现双侧同字段修改时）——
 * 与三选提示互斥呈现，裁决结果走同一 [VaultRepository.applyExternalModificationChoice] 入口。
 */
internal class SettingsIssueWiring(
    private val vaultRepository: VaultRepository,
    private val vaultFileDriftCoordinator: VaultFileDriftCoordinator?,
    private val preferencesController: SettingsPreferencesController
) {
    private val _pending = MutableStateFlow<VaultFileDriftPrompt?>(null)
    val pending: StateFlow<VaultFileDriftPrompt?> = _pending.asStateFlow()

    /** `ISSUE-P1-631`：待裁决的漂移合并冲突（null = 无待决） */
    private val _mergeConflict = MutableStateFlow<VaultFileDriftMergeConflict?>(null)
    val mergeConflict: StateFlow<VaultFileDriftMergeConflict?> = _mergeConflict.asStateFlow()

    fun publishPending(prompt: VaultFileDriftPrompt?) {
        _pending.value = prompt
    }

    /** `ISSUE-P1-631`：下发合并冲突待决状态（由协调器流收集驱动） */
    fun publishMergeConflict(conflict: VaultFileDriftMergeConflict?) {
        _mergeConflict.value = conflict
    }

    /**
     * `ISSUE-P1-631`：用户取消合并冲突裁决——关闭裁决提示并把三选重新交还用户
     * （本地改动仍在内存、磁盘版本未被覆盖；之后可选重载 / 合并 / 放弃）。
     */
    fun cancelMergeConflict() {
        val pathIdentifier = _mergeConflict.value?.pathIdentifier
        vaultFileDriftCoordinator?.clearMergeConflictPrompt()
        if (pathIdentifier != null) vaultFileDriftCoordinator?.requestPrompt(pathIdentifier)
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
        // ISSUE-P1-631：dismiss 只清三选提示——合并冲突裁决提示由 resolve 的终结路径显式管理，
        // 不得在此误杀刚置起的待裁决状态（dismiss 语义见 VaultFileDriftCoordinator）
        vaultFileDriftCoordinator?.dismiss()
        return result
    }
}
