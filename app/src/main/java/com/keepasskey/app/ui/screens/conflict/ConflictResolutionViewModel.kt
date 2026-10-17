package com.keepasskey.app.ui.screens.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.sync.userText
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.sync.launchGuarded
import com.keepasskey.app.ui.model.RelativeTimeFormatter
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.core.security.readCharsForDisplayOrNull
import com.keepasskey.core.security.readStringForDisplayOrEmpty
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.FieldResolutionChoice
import com.keepasskey.sync.merge.KdbxGroupConflictKeys
import com.keepasskey.sync.merge.KdbxMetaConflictKeys
import com.keepasskey.sync.merge.MetaFieldConflict
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

    private val diffMapper = ConflictResolutionDiffMapper(strings)

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
                            // ISSUE-P2-534：冲突条目标题属**展示面**（本页在同步刚结束时渲染），
                            // 与会话整树替换的就地擦除并发 ⇒ 走展示面读口
                            title = pair.localEntry.displayTitle().ifBlank { pair.remoteEntry.displayTitle() },
                            groupPath = strings.get(R.string.conflict_group_path),
                            fields = pair.modifiedFields.map { key -> diffMapper.entryField(pair, key) }
                        )
                    }
                    // H1 整改：两侧修改时间取冲突条目中最新的真实 lastModificationTime，
                    // 不再展示写死的演示时间戳（原为「今天 10:25/10:22」，会误导用户裁决）
                    val localNewest = conflicts.maxOfOrNull { it.localEntry.times.lastModificationTime }
                    val remoteNewest = conflicts.maxOfOrNull { it.remoteEntry.times.lastModificationTime }
                    // ISSUE-P3-360 AC①：按 Instant 现场比较标注「较新」一侧（格式化文案不可比）
                    val newerSide = when {
                        localNewest == null || remoteNewest == null -> null
                        localNewest > remoteNewest -> ModifiedTimeSide.LOCAL
                        remoteNewest > localNewest -> ModifiedTimeSide.REMOTE
                        else -> null
                    }
                    _uiState.update {
                        it.copy(
                            entries = items,
                            localModifiedTime = localNewest?.let(::formatConflictTime).orEmpty()
                                .ifEmpty { strings.get(R.string.conflict_time_unknown) },
                            remoteModifiedTime = remoteNewest?.let(::formatConflictTime).orEmpty()
                                .ifEmpty { strings.get(R.string.conflict_time_unknown) },
                            newerSide = newerSide
                        )
                    }
                }
            }
        }
        // ISSUE-P2-660：分组冲突与条目冲突分通道到达，独立收集后并进同一份 UI 状态——
        // 两边各自 update 只改自己的字段，互不清空对方。
        // ISSUE-P2-659：库级 Meta 冲突独立通道；与条目 / 分组各自 update 自己的字段。
        viewModelScope.launch {
            syncCoordinator.metaConflictFlow.collect { conflicts ->
                val fields = conflicts.map { conflict -> diffMapper.metaField(conflict) }
                _uiState.update {
                    it.copy(meta = if (fields.isEmpty()) null else ConflictedMetaItem(fields = fields))
                }
            }
        }
        viewModelScope.launch {
            syncCoordinator.groupConflictFlow.collect { groups ->
                val items = groups.map { pair ->
                    ConflictedGroupItem(
                        id = pair.groupId,
                        name = pair.localGroup.name.ifBlank { pair.remoteGroup.name },
                        fields = pair.modifiedFields.map { key -> diffMapper.groupField(pair, key) }
                    )
                }
                _uiState.update { it.copy(groups = items) }
            }
        }
    }

    /**
     * ISSUE-P3-552：判据与文案统一收敛到 [RelativeTimeFormatter]
     * （另两处库列表页与设置页同批收敛，行为与文案不变）。
     */
    private fun formatConflictTime(instant: Instant): String =
        RelativeTimeFormatter.formatInstant(strings, instant)

    /** [fieldKey] 为差异键（[ConflictedField.fieldKey]，与 `modifiedFields` 同词汇表） */
    fun selectFieldChoice(entryId: String, fieldKey: String, choice: FieldChoice) {
        _uiState.update { state ->
            val updated = state.entries.map { entry ->
                if (entry.id == entryId) {
                    val newFields = entry.fields.map { field ->
                        if (field.fieldKey == fieldKey) {
                            field.copy(selectedChoice = normalizedFieldChoice(field, choice))
                        } else field
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
                val newFields = entry.fields.map {
                    it.copy(selectedChoice = normalizedFieldChoice(it, choice))
                }
                entry.copy(fields = newFields)
            }
            state.copy(entries = updated)
        }
    }

    private fun normalizedFieldChoice(field: ConflictedField, requested: FieldChoice): FieldChoice = when (requested) {
        FieldChoice.LOCAL -> FieldChoice.LOCAL
        FieldChoice.REMOTE -> if (field.remoteMissing) FieldChoice.DELETE_REMOTE else FieldChoice.REMOTE
        FieldChoice.DELETE_REMOTE -> if (field.remoteMissing) FieldChoice.DELETE_REMOTE else FieldChoice.LOCAL
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

    /** `ISSUE-P2-660`：分组级裁决方式（整组取本地 / 整组取云端 / 双方保留）。 */
    fun selectGroupMode(groupId: String, mode: GroupResolutionMode) {
        _uiState.update { state ->
            val updated = state.groups.map { group ->
                if (group.id == groupId) group.copy(mode = mode) else group
            }
            state.copy(groups = updated)
        }
    }

    /** `ISSUE-P2-659`：库级 Meta 的逐字段选择（只有本地 / 云端两档）。 */
    fun selectMetaFieldChoice(fieldKey: String, choice: FieldChoice) {
        _uiState.update { state ->
            val meta = state.meta ?: return@update state
            val updated = meta.fields.map { field ->
                if (field.fieldKey == fieldKey) {
                    // 标量无「云端删除该字段」语义：DELETE_REMOTE 归一为保留本地
                    field.copy(
                        selectedChoice = if (choice == FieldChoice.REMOTE) FieldChoice.REMOTE else FieldChoice.LOCAL
                    )
                } else {
                    field
                }
            }
            state.copy(meta = meta.copy(fields = updated))
        }
    }

    fun applyMerge() {
        // ISSUE-P0-531：用户裁决链路同样不得让异常逃逸——逃逸即杀进程，且 isResolving 会卡在 true
        // （`resolveConflicts` 内含落库与上传收尾，异常点不止引擎内部）
        viewModelScope.launchGuarded(
            onFailure = { e ->
                _uiState.update {
                    it.copy(
                        isResolving = false,
                        userMessage = UiMessage(
                            R.string.sync_feedback_error,
                            listOf(e.javaClass.simpleName)
                        )
                    )
                }
            }
        ) {
            _uiState.update { it.copy(isResolving = true) }
            // TASK-30 整改：逐字段的用户选择不再塌缩为整条目二选一——每个条目生成
            // 「字段键 → 决策」映射交由 SyncCoordinator 按字段粒度合并
            // （本地为底版，选择「云端」的字段以远端值覆写）。
            // ISSUE-P2-281：条目级裁决方式为非逐字段时，整条决策走 resolutions 通道
            // （fieldResolutions 不含该条目 ⇒ resolveConflict 整条分支，DUPLICATE_BOTH 由此可达）
            val resolutions = mutableMapOf<String, ConflictResolutionChoice>()
            val fieldResolutions = mutableMapOf<String, Map<String, FieldResolutionChoice>>()
            _uiState.value.entries.forEach { entry ->
                when (entry.mode) {
                    EntryResolutionMode.FIELD_BY_FIELD -> {
                        val perField = entry.fields.associate { field ->
                            field.fieldKey to when (field.selectedChoice) {
                                FieldChoice.LOCAL -> FieldResolutionChoice.KEEP_LOCAL
                                FieldChoice.REMOTE -> FieldResolutionChoice.KEEP_REMOTE
                                FieldChoice.DELETE_REMOTE -> FieldResolutionChoice.DELETE_REMOTE
                            }
                        }
                        // 条目级决策保留为兜底语义：任一字段选择云端即视为 KEEP_REMOTE
                        val hasRemote = perField.values.any { it != FieldResolutionChoice.KEEP_LOCAL }
                        resolutions[entry.id] =
                            if (hasRemote) ConflictResolutionChoice.KEEP_REMOTE else ConflictResolutionChoice.KEEP_LOCAL
                        fieldResolutions[entry.id] = perField
                    }
                    EntryResolutionMode.KEEP_LOCAL -> resolutions[entry.id] = ConflictResolutionChoice.KEEP_LOCAL
                    EntryResolutionMode.KEEP_REMOTE -> resolutions[entry.id] = ConflictResolutionChoice.KEEP_REMOTE
                    EntryResolutionMode.DUPLICATE_BOTH -> resolutions[entry.id] = ConflictResolutionChoice.DUPLICATE_BOTH
                }
            }
            // ISSUE-P2-660：分组裁决与条目裁决同批提交。默认「整组取本地」，与条目侧
            // 「未显式选择即保留本地」同口径；「双方保留」由落树侧复制整棵子树。
            val groupResolutions = mutableMapOf<String, ConflictResolutionChoice>()
            _uiState.value.groups.forEach { group ->
                groupResolutions[group.id] = when (group.mode) {
                    GroupResolutionMode.KEEP_LOCAL -> ConflictResolutionChoice.KEEP_LOCAL
                    GroupResolutionMode.KEEP_REMOTE -> ConflictResolutionChoice.KEEP_REMOTE
                    GroupResolutionMode.DUPLICATE_BOTH -> ConflictResolutionChoice.DUPLICATE_BOTH
                }
            }
            // ISSUE-P2-659：Meta 字段决策与条目同批提交；未选择的字段保持合并产物（本地为底版）
            val metaResolutions = mutableMapOf<String, FieldResolutionChoice>()
            _uiState.value.meta?.fields?.forEach { field ->
                metaResolutions[field.fieldKey] = when (field.selectedChoice) {
                    FieldChoice.REMOTE -> FieldResolutionChoice.KEEP_REMOTE
                    FieldChoice.LOCAL, FieldChoice.DELETE_REMOTE -> FieldResolutionChoice.KEEP_LOCAL
                }
            }
            val outcome = syncCoordinator.resolveConflicts(
                resolutions,
                fieldResolutions,
                groupResolutions,
                metaResolutions
            )
            val isSuccess = outcome is SyncOutcome.MergedAndUploaded
            _uiState.update {
                it.copy(
                    isResolving = false,
                    userMessage = if (isSuccess) {
                        UiMessage(R.string.conflict_resolved_msg)
                    } else {
                        UiMessage(
                            R.string.sync_feedback_error,
                            // ISSUE-P3-550：经 [SyncOutcome.Error.userText] 命名出口取文案
                            listOf((outcome as? SyncOutcome.Error)?.userText ?: strings.get(R.string.conflict_merge_failed))
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
