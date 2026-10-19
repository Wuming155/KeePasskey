package com.keepasskey.app.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.R
import com.keepasskey.app.security.VaultRecoveryCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.textArg
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 恢复副本处置对话框状态机（`ISSUE-P1-592` 步骤 5）。
 *
 * 只承载**非敏感** UI 状态：待处置副本清单、预览读数（条目计数）、进行中标志与提示文案。
 * 主密码绝不进入本类状态——由对话框以最短生命周期持有并在用毕清零。
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val coordinator: VaultRecoveryCoordinator,
    private val strings: StringsProvider
) : ViewModel() {

    data class UiState(
        val pending: List<VaultRecoveryCoordinator.Pending> = emptyList(),
        val selectedKey: String? = null,
        val preview: VaultRecoveryCoordinator.Preview? = null,
        val previewBinding: PreviewBinding? = null,
        val previewText: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val awaitingOverwriteConfirm: Boolean = false
    )

    /** 非敏感的预览授权，绑定恢复副本元数据及发起请求的代次。 */
    class PreviewBinding internal constructor(
        internal val target: TargetIdentity,
        internal val requestGeneration: Long
    )

    internal data class TargetIdentity(
        val vaultId: String,
        val pathIdentifier: String,
        val payloadFileName: String,
        val payloadLength: Long,
        val payloadSha256: String
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private val previewRequestGate = RecoveryPreviewRequestGate()

    /** 可控预览操作入口，生产默认委托协调器；JVM 竞态测试以屏障替换。 */
    @VisibleForTesting
    internal var previewOperation: suspend (VaultRecoveryCoordinator.Pending, CharArray) -> KdbxResult<VaultRecoveryCoordinator.Preview> =
        coordinator::preview

    /** 上次「未能保全」告警待展示（冷启动消费）。 */
    private val _preservationAlert = MutableStateFlow(false)
    val preservationAlert: StateFlow<Boolean> = _preservationAlert.asStateFlow()

    init {
        refresh()
        _preservationAlert.value = coordinator.consumePreservationAlert() != null
    }

    fun refresh() {
        // 刷新可能替换同一路径下的恢复副本；使在途预览失效并要求重新预览。
        previewRequestGate.invalidate()
        coordinator.refresh()
        val entries = coordinator.pending.value
        _uiState.update { state ->
            state.copy(
                pending = entries,
                selectedKey = state.selectedKey?.takeIf { key -> entries.any { keyOf(it) == key } },
                preview = null,
                previewBinding = null,
                previewText = null,
                busy = false,
                awaitingOverwriteConfirm = false
            )
        }
    }

    fun dismissPreservationAlert() { _preservationAlert.value = false }

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    fun select(key: String?) {
        _uiState.update {
            if (it.busy) it else it.copy(
                selectedKey = key,
                preview = null,
                previewBinding = null,
                previewText = null,
                awaitingOverwriteConfirm = false
            )
        }
    }

    /** 解锁并比较差异（密码副本由调用方所有，本方法用毕清零）。 */
    fun preview(target: VaultRecoveryCoordinator.Pending, password: CharArray) {
        val state = _uiState.value
        val identity = identityOf(target)
        val currentTarget = state.pending.firstOrNull { keyOf(it) == state.selectedKey }
        if (state.busy || currentTarget == null || keyOf(target) != state.selectedKey || identityOf(currentTarget) != identity) {
            password.fill('0')
            return
        }
        val generation = previewRequestGate.begin(identity)
        _uiState.update {
            it.copy(
                busy = true,
                selectedKey = keyOf(target),
                preview = null,
                previewBinding = null,
                previewText = null,
                message = null,
                awaitingOverwriteConfirm = false
            )
        }
        viewModelScope.launch {
            val result = try {
                previewOperation(target, password)
            } catch (cancelled: CancellationException) {
                _uiState.update { state ->
                    if (previewRequestGate.matches(generation, identity) && state.selectedKey == keyOf(target)) {
                        state.copy(busy = false, preview = null, previewBinding = null, previewText = null)
                    } else {
                        state
                    }
                }
                throw cancelled
            } finally {
                password.fill('0')
            }
            _uiState.update { state ->
                if (!previewRequestGate.matches(generation, identity) || state.selectedKey != keyOf(target) ||
                    state.pending.none { keyOf(it) == state.selectedKey && identityOf(it) == identity }
                ) return@update state
                when (result) {
                    is KdbxResult.Success -> state.copy(
                        busy = false,
                        preview = result.data,
                        previewBinding = PreviewBinding(identity, generation),
                        previewText = previewText(result.data)
                    )

                    is KdbxResult.Failure -> state.copy(
                        busy = false,
                        preview = null,
                        previewBinding = null,
                        previewText = result.textArg(strings)
                    )
                }
            }
        }
    }

    /** 恢复为正式库；首次（未确认覆盖）失败于 RECOVERY_OVERWRITE_BLOCKED 时要求确认。 */
    fun restore(target: VaultRecoveryCoordinator.Pending, forceOverwrite: Boolean) {
        val current = _uiState.value
        if (!isPreviewBoundTo(current, target) || current.busy) return
        val generation = previewRequestGate.currentGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = coordinator.restore(target, forceOverwrite)
            if (!previewRequestGate.matches(generation, identityOf(target)) || !isPreviewBoundTo(_uiState.value, target)) return@launch
            when (result) {
                is KdbxResult.Success -> finish(strings.get(R.string.recovery_restored_message))
                is KdbxResult.Failure -> _uiState.update {
                    it.copy(
                        busy = false,
                        awaitingOverwriteConfirm =
                            result.code == com.keepasskey.core.result.KdbxError.RECOVERY_OVERWRITE_BLOCKED,
                        message = if (result.code == com.keepasskey.core.result.KdbxError.RECOVERY_OVERWRITE_BLOCKED) {
                            null
                        } else {
                            result.textArg(strings)
                        }
                    )
                }
            }
        }
    }

    fun saveAs(target: VaultRecoveryCoordinator.Pending) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            when (val result = coordinator.saveAs(target)) {
                is KdbxResult.Success -> finish(strings.get(R.string.recovery_saved_as_message, result.data))
                is KdbxResult.Failure -> _uiState.update { it.copy(busy = false, message = result.textArg(strings)) }
            }
        }
    }

    fun discard(target: VaultRecoveryCoordinator.Pending) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            coordinator.discard(target)
            finish(strings.get(R.string.recovery_discarded_message))
        }
    }

    fun keep() {
        previewRequestGate.invalidate()
        _uiState.update {
            it.copy(
                selectedKey = null,
                preview = null,
                previewBinding = null,
                previewText = null,
                busy = false,
                awaitingOverwriteConfirm = false
            )
        }
    }

    fun cancelOverwriteConfirm() { _uiState.update { it.copy(awaitingOverwriteConfirm = false) } }

    private fun finish(message: String) {
        coordinator.refresh()
        _uiState.update {
            it.copy(
                pending = coordinator.pending.value,
                selectedKey = null,
                preview = null,
                previewBinding = null,
                previewText = null,
                busy = false,
                awaitingOverwriteConfirm = false,
                message = message
            )
        }
    }

    private fun isPreviewBoundTo(state: UiState, target: VaultRecoveryCoordinator.Pending): Boolean {
        val binding = state.previewBinding ?: return false
        return state.selectedKey == keyOf(target) &&
            state.preview != null &&
            previewRequestGate.matches(binding.requestGeneration, binding.target) &&
            binding.target == identityOf(target) &&
            state.pending.any { keyOf(it) == state.selectedKey && identityOf(it) == binding.target }
    }

    private fun identityOf(target: VaultRecoveryCoordinator.Pending): TargetIdentity =
        TargetIdentity(
            vaultId = target.entry.vaultId,
            pathIdentifier = target.entry.pathIdentifier,
            payloadFileName = target.entry.payloadFileName,
            payloadLength = target.entry.payloadLength,
            payloadSha256 = target.entry.payloadSha256
        )

    private fun previewText(preview: VaultRecoveryCoordinator.Preview): String =
        if (preview.officialEntryCount == null || preview.addedEntries == null) {
            strings.get(R.string.recovery_preview_no_official)
        } else {
            strings.get(
                R.string.recovery_preview_summary,
                preview.snapshotEntryCount,
                preview.officialEntryCount,
                preview.addedEntries,
                preview.removedEntries,
                preview.modifiedEntries
            )
        }

    companion object {
        fun keyOf(target: VaultRecoveryCoordinator.Pending): String = target.entry.pathIdentifier
    }
}

/** 单调代次门：异步预览只有在请求与副本身份仍匹配时才能发布。 */
internal class RecoveryPreviewRequestGate {
    var currentGeneration: Long = 0L
        private set
    private var currentTarget: RecoveryViewModel.TargetIdentity? = null

    fun begin(target: RecoveryViewModel.TargetIdentity): Long {
        currentGeneration += 1
        currentTarget = target
        return currentGeneration
    }

    fun invalidate(): Long {
        currentGeneration += 1
        currentTarget = null
        return currentGeneration
    }

    fun matches(generation: Long, target: RecoveryViewModel.TargetIdentity): Boolean =
        generation == currentGeneration && target == currentTarget
}
