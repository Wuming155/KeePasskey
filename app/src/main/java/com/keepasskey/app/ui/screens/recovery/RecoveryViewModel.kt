package com.keepasskey.app.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.security.VaultRecoveryCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.textArg
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
        val previewText: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val awaitingOverwriteConfirm: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var previewRequestGeneration: Long = 0L

    /** 上次「未能保全」告警待展示（冷启动消费）。 */
    private val _preservationAlert = MutableStateFlow(false)
    val preservationAlert: StateFlow<Boolean> = _preservationAlert.asStateFlow()

    init {
        refresh()
        _preservationAlert.value = coordinator.consumePreservationAlert() != null
    }

    fun refresh() {
        coordinator.refresh()
        val entries = coordinator.pending.value
        _uiState.update { state ->
            state.copy(
                pending = entries,
                selectedKey = state.selectedKey?.takeIf { key -> entries.any { keyOf(it) == key } }
            )
        }
    }

    fun dismissPreservationAlert() { _preservationAlert.value = false }

    fun dismissMessage() { _uiState.update { it.copy(message = null) } }

    fun select(key: String?) {
        previewRequestGeneration += 1
        _uiState.update {
            it.copy(
                selectedKey = key,
                preview = null,
                previewText = null,
                busy = false,
                awaitingOverwriteConfirm = false
            )
        }
    }

    /** 解锁并比较差异（密码副本由调用方所有，本方法用毕清零）。 */
    fun preview(target: VaultRecoveryCoordinator.Pending, password: CharArray) {
        val targetKey = keyOf(target)
        val requestGeneration = ++previewRequestGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, selectedKey = targetKey, preview = null, previewText = null, message = null) }
            val result = try {
                coordinator.preview(target, password)
            } finally {
                password.fill('0')
            }
            if (requestGeneration != previewRequestGeneration || _uiState.value.selectedKey != targetKey) return@launch
            _uiState.update { state ->
                when (result) {
                    is KdbxResult.Success -> state.copy(
                        busy = false,
                        preview = result.data,
                        previewText = previewText(result.data)
                    )

                    is KdbxResult.Failure -> state.copy(
                        busy = false,
                        preview = null,
                        previewText = result.textArg(strings)
                    )
                }
            }
        }
    }

    /** 恢复为正式库；首次（未确认覆盖）失败于 RECOVERY_OVERWRITE_BLOCKED 时要求确认。 */
    fun restore(target: VaultRecoveryCoordinator.Pending, forceOverwrite: Boolean) {
        val current = _uiState.value
        if (current.selectedKey != keyOf(target) || current.preview == null || current.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            when (val result = coordinator.restore(target, forceOverwrite)) {
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

    fun keep() { _uiState.update { it.copy(selectedKey = null, preview = null, previewText = null) } }

    fun cancelOverwriteConfirm() { _uiState.update { it.copy(awaitingOverwriteConfirm = false) } }

    private fun finish(message: String) {
        coordinator.refresh()
        _uiState.update {
            it.copy(
                pending = coordinator.pending.value,
                selectedKey = null,
                preview = null,
                previewText = null,
                busy = false,
                awaitingOverwriteConfirm = false,
                message = message
            )
        }
    }

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
