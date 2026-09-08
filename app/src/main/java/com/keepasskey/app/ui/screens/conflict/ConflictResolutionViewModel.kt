package com.keepasskey.app.ui.screens.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.sync.merge.ConflictResolutionChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed interface ConflictResolutionEvent {
    data object ResolveSuccess : ConflictResolutionEvent
}

@HiltViewModel
class ConflictResolutionViewModel @Inject constructor(
    private val syncCoordinator: SyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConflictResolutionUiState(entries = emptyList()))
    val uiState: StateFlow<ConflictResolutionUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ConflictResolutionEvent>()
    val events: SharedFlow<ConflictResolutionEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            syncCoordinator.conflictFlow.collect { conflicts ->
                if (conflicts.isNotEmpty()) {
                    val items = conflicts.map { pair ->
                        val fieldList = mutableListOf<ConflictedField>()
                        if (pair.localEntry.title != pair.remoteEntry.title) {
                            fieldList.add(
                                ConflictedField(
                                    KdbxConstants.Fields.TITLE,
                                    "标题 (Title)",
                                    pair.localEntry.title,
                                    pair.remoteEntry.title
                                )
                            )
                        }
                        if (pair.localEntry.userName != pair.remoteEntry.userName) {
                            fieldList.add(
                                ConflictedField(
                                    KdbxConstants.Fields.USER_NAME,
                                    "用户名 (Username)",
                                    pair.localEntry.userName,
                                    pair.remoteEntry.userName
                                )
                            )
                        }
                        // 敏感数据铁律：冲突对比界面不物化密码明文，仅以掩码呈现「两侧不一致」事实
                        if (pair.localEntry.password != pair.remoteEntry.password) {
                            fieldList.add(
                                ConflictedField(
                                    KdbxConstants.Fields.PASSWORD,
                                    "密码 (Password)",
                                    "••••••••（本地版本）",
                                    "••••••••（云端版本）",
                                    isSensitive = true
                                )
                            )
                        }
                        if (pair.localEntry.url != pair.remoteEntry.url) {
                            fieldList.add(
                                ConflictedField(
                                    KdbxConstants.Fields.URL,
                                    "网址 (URL)",
                                    pair.localEntry.url,
                                    pair.remoteEntry.url
                                )
                            )
                        }
                        if (pair.localEntry.notes != pair.remoteEntry.notes) {
                            fieldList.add(
                                ConflictedField(
                                    KdbxConstants.Fields.NOTES,
                                    "备注 (Notes)",
                                    pair.localEntry.notes,
                                    pair.remoteEntry.notes
                                )
                            )
                        }
                        ConflictedEntryItem(
                            id = pair.entryId,
                            title = pair.localEntry.title.ifBlank { pair.remoteEntry.title },
                            groupPath = "根目录 / 同步冲突",
                            fields = fieldList
                        )
                    }
                    // H1 整改：两侧修改时间取冲突条目中最新的真实 lastModificationTime，
                    // 不再展示写死的演示时间戳（原为「今天 10:25/10:22」，会误导用户裁决）
                    val localNewest = conflicts.maxOfOrNull { it.localEntry.times.lastModificationTime }
                    val remoteNewest = conflicts.maxOfOrNull { it.remoteEntry.times.lastModificationTime }
                    _uiState.update {
                        it.copy(
                            entries = items,
                            localModifiedTime = localNewest?.let(::formatConflictTime).orEmpty().ifEmpty { "未知" },
                            remoteModifiedTime = remoteNewest?.let(::formatConflictTime).orEmpty().ifEmpty { "未知" }
                        )
                    }
                }
            }
        }
    }

    private fun formatConflictTime(instant: Instant): String {
        val local = instant.atZone(ZoneId.systemDefault())
        val datePrefix = when (local.toLocalDate()) {
            java.time.LocalDate.now() -> "今天"
            java.time.LocalDate.now().minusDays(1) -> "昨天"
            else -> local.format(DateTimeFormatter.ofPattern("M月d日"))
        }
        return "$datePrefix ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    /** [fieldKey] 为字段标准键（[ConflictedField.fieldKey]） */
    fun selectFieldChoice(entryId: String, fieldKey: String, choice: FieldChoice) {
        _uiState.update { state ->
            val updated = state.entries.map { entry ->
                if (entry.id == entryId) {
                    val newFields = entry.fields.map { field ->
                        if (field.fieldKey == fieldKey) field.copy(selectedChoice = choice) else field
                    }
                    entry.copy(fields = newFields)
                } else entry
            }
            state.copy(entries = updated)
        }
    }

    fun selectAll(choice: FieldChoice) {
        _uiState.update { state ->
            val updated = state.entries.map { entry ->
                val newFields = entry.fields.map { it.copy(selectedChoice = choice) }
                entry.copy(fields = newFields)
            }
            state.copy(entries = updated)
        }
    }

    fun applyMerge() {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true) }
            // TASK-30 整改：逐字段的用户选择不再塌缩为整条目二选一——每个条目生成
            // 「字段键 → 决策」映射交由 SyncCoordinator 按字段粒度合并
            // （本地为底版，选择「云端」的字段以远端值覆写）。
            val resolutions = mutableMapOf<String, ConflictResolutionChoice>()
            val fieldResolutions = mutableMapOf<String, Map<String, ConflictResolutionChoice>>()
            _uiState.value.entries.forEach { entry ->
                val perField = entry.fields.associate { field ->
                    field.fieldKey to when (field.selectedChoice) {
                        FieldChoice.LOCAL -> ConflictResolutionChoice.KEEP_LOCAL
                        FieldChoice.REMOTE -> ConflictResolutionChoice.KEEP_REMOTE
                    }
                }
                // 条目级决策保留为兜底语义：任一字段选择云端即视为 KEEP_REMOTE
                val hasRemote = perField.values.any { it == ConflictResolutionChoice.KEEP_REMOTE }
                resolutions[entry.id] =
                    if (hasRemote) ConflictResolutionChoice.KEEP_REMOTE else ConflictResolutionChoice.KEEP_LOCAL
                fieldResolutions[entry.id] = perField
            }
            val outcome = syncCoordinator.resolveConflicts(resolutions, fieldResolutions)
            val isSuccess = outcome is SyncOutcome.MergedAndUploaded
            _uiState.update {
                it.copy(
                    isResolving = false,
                    userMessage = if (isSuccess) {
                        UiMessage(R.string.conflict_resolved_msg)
                    } else {
                        UiMessage(R.string.sync_feedback_error, listOf((outcome as? SyncOutcome.Error)?.message ?: "合并失败"))
                    }
                )
            }
            if (isSuccess) {
                _events.emit(ConflictResolutionEvent.ResolveSuccess)
            }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
