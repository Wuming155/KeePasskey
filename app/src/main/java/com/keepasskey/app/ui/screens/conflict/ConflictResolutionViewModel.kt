package com.keepasskey.app.ui.screens.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.KdbxMerger
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
    private val syncCoordinator: SyncCoordinator,
    // TASK-21：非 Compose 层文案资源解析通道（生产 DI 注入真实现；单测注入假实现）
    private val stringsProvider: StringsProvider? = null
) : ViewModel() {

    // P3-23：null 时回退空串实现（生产 Hilt 恒注入 StringsProviderModule 真实现）
    private val strings: StringsProvider = stringsProvider ?: StringsProvider { _, _ -> "" }

    private val _uiState = MutableStateFlow(ConflictResolutionUiState(entries = emptyList()))
    val uiState: StateFlow<ConflictResolutionUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ConflictResolutionEvent>()
    val events: SharedFlow<ConflictResolutionEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            syncCoordinator.conflictFlow.collect { conflicts ->
                if (conflicts.isNotEmpty()) {
                    // ISSUE-P2-281 AC①：差异来源 = 合并器产出的 modifiedFields（单一真相源），
                    // 本类不再自造五字段 diff（此前自定义字段 / 图标等分歧在界面不可见、无从裁决）
                    val items = conflicts.map { pair ->
                        ConflictedEntryItem(
                            id = pair.entryId,
                            title = pair.localEntry.title.ifBlank { pair.remoteEntry.title },
                            groupPath = strings.get(R.string.conflict_group_path),
                            fields = pair.modifiedFields.map { key -> buildConflictedField(pair, key) }
                        )
                    }
                    // H1 整改：两侧修改时间取冲突条目中最新的真实 lastModificationTime，
                    // 不再展示写死的演示时间戳（原为「今天 10:25/10:22」，会误导用户裁决）
                    val localNewest = conflicts.maxOfOrNull { it.localEntry.times.lastModificationTime }
                    val remoteNewest = conflicts.maxOfOrNull { it.remoteEntry.times.lastModificationTime }
                    _uiState.update {
                        it.copy(
                            entries = items,
                            localModifiedTime = localNewest?.let(::formatConflictTime).orEmpty()
                                .ifEmpty { strings.get(R.string.conflict_time_unknown) },
                            remoteModifiedTime = remoteNewest?.let(::formatConflictTime).orEmpty()
                                .ifEmpty { strings.get(R.string.conflict_time_unknown) }
                        )
                    }
                }
            }
        }
    }

    private fun formatConflictTime(instant: Instant): String {
        val local = instant.atZone(ZoneId.systemDefault())
        val datePrefix = when (local.toLocalDate()) {
            java.time.LocalDate.now() -> strings.get(R.string.time_today)
            java.time.LocalDate.now().minusDays(1) -> strings.get(R.string.time_yesterday)
            else -> local.format(DateTimeFormatter.ofPattern(strings.get(R.string.date_pattern_month_day)))
        }
        return "$datePrefix ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    /**
     * ISSUE-P2-281：把 `modifiedFields` 的一个差异键映射为可裁决的界面行
     * （键的词汇与合并器同表——标准字段键 / `custom:` 前缀自定义字段键 / 四个标量键）。
     * 敏感值（密码 / `isProtected` 的自定义字段）一律掩码，不物化明文。
     */
    private fun buildConflictedField(
        pair: com.keepasskey.sync.merge.ConflictedEntryPair,
        key: String
    ): ConflictedField {
        val local = pair.localEntry
        val remote = pair.remoteEntry
        return when {
            key == KdbxConstants.Fields.PASSWORD -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_field_password),
                localValue = strings.get(R.string.conflict_mask_local),
                remoteValue = strings.get(R.string.conflict_mask_remote),
                isSensitive = true
            )
            key == KdbxConstants.Fields.TITLE || key == KdbxConstants.Fields.USER_NAME ||
                key == KdbxConstants.Fields.URL || key == KdbxConstants.Fields.NOTES -> {
                val labelRes = when (key) {
                    KdbxConstants.Fields.TITLE -> R.string.conflict_field_title
                    KdbxConstants.Fields.USER_NAME -> R.string.conflict_field_username
                    KdbxConstants.Fields.URL -> R.string.conflict_field_url
                    else -> R.string.conflict_field_notes
                }
                ConflictedField(
                    fieldKey = key,
                    fieldName = strings.get(labelRes),
                    localValue = local.fields[key]?.readString().orEmpty(),
                    remoteValue = remote.fields[key]?.readString().orEmpty()
                )
            }
            key.startsWith(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX) -> {
                val customKey = key.removePrefix(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX)
                val lv = local.customFields.firstOrNull { it.key == customKey }?.value
                val rv = remote.customFields.firstOrNull { it.key == customKey }?.value
                val sensitive = lv?.isProtected == true || rv?.isProtected == true
                ConflictedField(
                    fieldKey = key,
                    fieldName = customKey,
                    localValue = if (sensitive) strings.get(R.string.conflict_mask_local) else lv?.readString().orEmpty(),
                    remoteValue = if (sensitive) strings.get(R.string.conflict_mask_remote) else rv?.readString().orEmpty(),
                    isSensitive = sensitive
                )
            }
            key == KdbxMerger.CONFLICT_KEY_ICON_ID -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_field_icon),
                localValue = local.iconId.toString(),
                remoteValue = remote.iconId.toString()
            )
            key == KdbxMerger.CONFLICT_KEY_CUSTOM_ICON_ID -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_field_custom_icon),
                localValue = local.customIconId?.toHexString() ?: strings.get(R.string.conflict_value_none),
                remoteValue = remote.customIconId?.toHexString() ?: strings.get(R.string.conflict_value_none)
            )
            key == KdbxMerger.CONFLICT_KEY_OVERRIDE_URL -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_field_override_url),
                localValue = local.overrideUrl ?: strings.get(R.string.conflict_value_none),
                remoteValue = remote.overrideUrl ?: strings.get(R.string.conflict_value_none)
            )
            key == KdbxMerger.CONFLICT_KEY_QUALITY_CHECK -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_field_quality_check),
                localValue = strings.get(if (local.qualityCheck) R.string.conflict_value_true else R.string.conflict_value_false),
                remoteValue = strings.get(if (remote.qualityCheck) R.string.conflict_value_true else R.string.conflict_value_false)
            )
            // 词汇外的键：可见呈现（禁静默丢弃差异），resolveConflictByFields 对未知键为安全 no-op
            else -> ConflictedField(
                fieldKey = key,
                fieldName = key,
                localValue = strings.get(R.string.conflict_value_changed),
                remoteValue = strings.get(R.string.conflict_value_changed)
            )
        }
    }

    /** [fieldKey] 为差异键（[ConflictedField.fieldKey]，与 `modifiedFields` 同词汇表） */
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

    /** ISSUE-P2-281 AC②／AC③：条目级裁决方式（整条兜底入口 / 双方保留）。 */
    fun selectEntryMode(entryId: String, mode: EntryResolutionMode) {
        _uiState.update { state ->
            val updated = state.entries.map { entry ->
                if (entry.id == entryId) entry.copy(mode = mode) else entry
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
            // ISSUE-P2-281：条目级裁决方式为非逐字段时，整条决策走 resolutions 通道
            // （fieldResolutions 不含该条目 ⇒ resolveConflict 整条分支，DUPLICATE_BOTH 由此可达）
            val resolutions = mutableMapOf<String, ConflictResolutionChoice>()
            val fieldResolutions = mutableMapOf<String, Map<String, ConflictResolutionChoice>>()
            _uiState.value.entries.forEach { entry ->
                when (entry.mode) {
                    EntryResolutionMode.FIELD_BY_FIELD -> {
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
                    EntryResolutionMode.KEEP_LOCAL -> resolutions[entry.id] = ConflictResolutionChoice.KEEP_LOCAL
                    EntryResolutionMode.KEEP_REMOTE -> resolutions[entry.id] = ConflictResolutionChoice.KEEP_REMOTE
                    EntryResolutionMode.DUPLICATE_BOTH -> resolutions[entry.id] = ConflictResolutionChoice.DUPLICATE_BOTH
                }
            }
            val outcome = syncCoordinator.resolveConflicts(resolutions, fieldResolutions)
            val isSuccess = outcome is SyncOutcome.MergedAndUploaded
            _uiState.update {
                it.copy(
                    isResolving = false,
                    userMessage = if (isSuccess) {
                        UiMessage(R.string.conflict_resolved_msg)
                    } else {
                        UiMessage(
                            R.string.sync_feedback_error,
                            listOf((outcome as? SyncOutcome.Error)?.message ?: strings.get(R.string.conflict_merge_failed))
                        )
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
