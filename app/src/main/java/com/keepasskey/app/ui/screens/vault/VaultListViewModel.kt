package com.keepasskey.app.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.sync.engine.SyncCacheEvent
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * 密码库列表状态容器 ViewModel，基于 Flow 响应式驱动 UI 状态组合
 */
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val clipboardSecurityManager: ClipboardSecurityManager? = null,
    private val syncCoordinator: com.keepasskey.app.sync.SyncCoordinator
) : ViewModel() {

    private val currentGroupIdFlow = MutableStateFlow<String?>(null)
    private val searchQueryFlow = MutableStateFlow("")
    private val isSearchActiveFlow = MutableStateFlow(false)
    private val sortOptionFlow = MutableStateFlow(VaultSortOption.DEFAULT)
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    // 批量管理状态
    private val isBatchModeFlow = MutableStateFlow(false)
    private val selectedEntryIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    // 云端同步指示状态
    private val syncStatusFlow = MutableStateFlow(VaultSyncStatus.SYNCED)
    private val isSyncingFlow = MutableStateFlow(false)
    // 上次同步完成时间戳，驱动下拉指示区的「上次同步」文案；0 表示本会话尚未同步过
    private val lastSyncTimeMillisFlow = MutableStateFlow(0L)

    // TOTP 剩余秒数倒计时，与验证器页共用 30 秒周期窗口
    private val totpRemainingSecondsFlow = MutableStateFlow(calculateCurrentRemainingSeconds())

    // 断点6 整改：周期翻转时按需重算的实时验证码表（entryId -> code）；
    // 原实现验证码仅在数据库流发射时计算一次，周期翻转后展示旧码
    private val liveTotpCodesFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    // H2 整改：存在待解决的冲突会话时驱动「去解决冲突」入口
    private val hasPendingConflictFlow = MutableStateFlow(false)

    // H4-只读整改：会话只读标志（解锁时刻确定，只读时禁用新增/批量编辑入口）
    private val isReadOnlyFlow = MutableStateFlow(vaultRepository.isSessionReadOnly())

    // 记录上一秒的剩余秒数，用于检测 TOTP 周期翻转
    private var previousTotpRemaining = -1

    init {
        // 每秒刷新 TOTP 剩余秒数，驱动列表内验证码环形倒计时实时跳动；
        // 周期翻转时对本组带 TOTP 的条目按需重算验证码
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(TOTP_TICK_INTERVAL_MS)
                val remaining = calculateCurrentRemainingSeconds()
                totpRemainingSecondsFlow.value = remaining
                if (previousTotpRemaining in 1..remaining || previousTotpRemaining == -1) {
                    refreshLiveTotpCodes()
                }
                previousTotpRemaining = remaining
            }
        }
        // H2 整改：订阅冲突会话流，冲突待解决时点亮列表页冲突入口
        viewModelScope.launch {
            syncCoordinator.conflictFlow.collect { conflicts ->
                hasPendingConflictFlow.value = conflicts.isNotEmpty()
            }
        }
        // 断点11 整改：解锁进入列表页即自动重同步一次（配置了云同步才触发），
        // 避免解锁后停留在缓存旧数据直到手动下拉刷新
        if (syncCoordinator.isSyncConfigured()) {
            triggerPullRefresh()
        }
    }

    /** 对当前列表中带 TOTP 的条目按需重算实时验证码（种子在数据层内解析，绝不外泄） */
    private fun refreshLiveTotpCodes() {
        viewModelScope.launch(Dispatchers.Default) {
            val updated = mutableMapOf<String, String>()
            for (entry in uiState.value.entries) {
                if (entry.totpCode == null) continue
                val snapshot = vaultRepository.calculateEntryTotp(entry.id) ?: continue
                updated[entry.id] = snapshot.code
            }
            liveTotpCodesFlow.value = updated
        }
    }

    private data class FilterParams(
        val query: String,
        val isSearchActive: Boolean,
        val sortOption: VaultSortOption
    )

    private val filterParamsFlow = combine(
        searchQueryFlow,
        isSearchActiveFlow,
        sortOptionFlow
    ) { query, isSearchActive, sortOption ->
        FilterParams(query, isSearchActive, sortOption)
    }

    private data class BatchAndSyncState(
        val isBatchMode: Boolean,
        val selectedEntryIds: Set<String>,
        val syncStatus: VaultSyncStatus,
        val isSyncing: Boolean,
        val lastSyncTimeText: String,
        val hasPendingConflict: Boolean = false,
        val isReadOnly: Boolean = false
    )

    private val batchAndSyncFlow = combine(
        isBatchModeFlow,
        selectedEntryIdsFlow,
        syncStatusFlow,
        isSyncingFlow,
        lastSyncTimeMillisFlow
    ) { isBatch, selected, status, syncing, lastSyncMillis ->
        BatchAndSyncState(isBatch, selected, status, syncing, formatLastSyncTime(lastSyncMillis))
    }.combine(hasPendingConflictFlow) { state, hasConflict ->
        state.copy(hasPendingConflict = hasConflict)
    }.combine(isReadOnlyFlow) { state, readOnly ->
        state.copy(isReadOnly = readOnly)
    }

    private data class SessionState(
        val currentGroupId: String?,
        val filterParams: FilterParams,
        val userMessage: UiMessage?,
        val totpRemainingSeconds: Int
    )

    private val sessionStateFlow = combine(
        currentGroupIdFlow,
        filterParamsFlow,
        userMessageFlow,
        totpRemainingSecondsFlow
    ) { groupId, params, message, totpSeconds ->
        SessionState(groupId, params, message, totpSeconds)
    }

    val uiState: StateFlow<VaultListUiState> = combine(
        vaultRepository.getGroups(),
        vaultRepository.getEntries(),
        settingsRepository.getSettings(),
        sessionStateFlow,
        batchAndSyncFlow
    ) { allGroups, allEntries, settings, session, batchSync ->
        val isSearching = session.filterParams.query.isNotBlank()

        // 计算当前面包屑路径
        val breadcrumbs = mutableListOf<VaultGroup>()
        var curId = session.currentGroupId
        while (curId != null) {
            val grp = allGroups.find { it.id == curId } ?: break
            breadcrumbs.add(0, grp)
            curId = grp.parentId
        }

        // H5 整改：回收站判定不再依赖 mock 常量字符串——以分组投影的 isRecycleBin 标记
        // 连同其全部后代分组构建回收站 id 集合
        val recycleBinGroupIds = buildSet {
            fun addDescendants(parentId: String) {
                allGroups.filter { it.parentId == parentId }.forEach { sub ->
                    add(sub.id)
                    addDescendants(sub.id)
                }
            }
            allGroups.filter { it.isRecycleBin }.forEach { bin ->
                add(bin.id)
                addDescendants(bin.id)
            }
        }
        val isInsideRecycleBin = breadcrumbs.any { it.isRecycleBin }

        // 根目录内容直显：currentGroupId == null 表示密码库顶层，
        // 应展示根分组内部内容（子分组 + 根级条目），而非把根分组自身渲染成一个节点
        val effectiveGroupId = session.currentGroupId
            ?: allGroups.firstOrNull { it.parentId == null }?.id

        // 1. 过滤条目：搜索时全局匹配（排除回收站内容），正常时只展示当前文件夹下的条目
        val targetEntries = if (isSearching) {
            allEntries.filter { if (!isInsideRecycleBin) it.groupId !in recycleBinGroupIds else true }
        } else {
            allEntries.filter { it.groupId == effectiveGroupId }
        }

        val filteredEntries = targetEntries.filter { entry ->
            session.filterParams.query.isBlank() ||
                    entry.title.contains(session.filterParams.query, ignoreCase = true) ||
                    entry.username.contains(session.filterParams.query, ignoreCase = true) ||
                    entry.url.contains(session.filterParams.query, ignoreCase = true)
        }

        // 2. 排序条目
        val sortedEntries = when (session.filterParams.sortOption) {
            VaultSortOption.DEFAULT -> filteredEntries.sortedBy { it.orderIndex }
            VaultSortOption.NAME_ASC -> filteredEntries.sortedBy { it.title.lowercase() }
            VaultSortOption.NAME_DESC -> filteredEntries.sortedByDescending { it.title.lowercase() }
            VaultSortOption.MODIFIED_DESC -> filteredEntries.sortedByDescending { it.updatedAt }
            VaultSortOption.MODIFIED_ASC -> filteredEntries.sortedBy { it.updatedAt }
            VaultSortOption.CREATED_DESC -> filteredEntries.sortedByDescending { it.createdAt }
            VaultSortOption.CREATED_ASC -> filteredEntries.sortedBy { it.createdAt }
        }

        // 3. 带实时 TOTP 剩余秒数与跨周期实时验证码的条目列表
        val liveCodes = batchSync.let { liveTotpCodesFlow.value }
        val entriesWithLiveTotp = sortedEntries.map { entry ->
            val liveCode = liveCodes[entry.id] ?: entry.totpCode
            if (liveCode != null) {
                entry.copy(totpCode = liveCode, totpRemainingSeconds = session.totpRemainingSeconds)
            } else {
                entry
            }
        }

        // 4. 文件夹
        val targetGroups = if (isSearching) {
            allGroups.filter { it.name.contains(session.filterParams.query, ignoreCase = true) }
        } else {
            allGroups.filter { it.parentId == effectiveGroupId }
        }

        VaultListUiState(
            searchQuery = session.filterParams.query,
            isSearchActive = session.filterParams.isSearchActive,
            sortOption = session.filterParams.sortOption,
            currentGroupId = session.currentGroupId,
            isInsideRecycleBin = isInsideRecycleBin,
            breadcrumbs = breadcrumbs,
            currentGroups = targetGroups,
            allGroups = allGroups,
            entries = entriesWithLiveTotp,
            totalEntriesCount = allEntries.size,
            isBatchMode = batchSync.isBatchMode,
            selectedEntryIds = batchSync.selectedEntryIds,
            syncStatus = batchSync.syncStatus,
            isSyncing = batchSync.isSyncing,
            lastSyncTimeText = batchSync.lastSyncTimeText,
            hasPendingConflict = batchSync.hasPendingConflict,
            isReadOnly = batchSync.isReadOnly,
            userMessage = session.userMessage,
            showUsernameInList = settings.showUsernameInList,
            showOtpInList = settings.showOtpInList,
            showPasskeyBadge = settings.showPasskeyBadge,
            showUrlInList = settings.showUrlInList,
            hideFabOnScroll = settings.hideFabOnScroll
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VaultListUiState()
    )

    fun enterGroup(groupId: String) {
        currentGroupIdFlow.value = groupId
    }

    fun navigateUp() {
        val curId = currentGroupIdFlow.value ?: return
        val currentGroup = uiState.value.breadcrumbs.lastOrNull { it.id == curId }
        currentGroupIdFlow.value = currentGroup?.parentId
    }

    fun navigateToBreadcrumb(groupId: String?) {
        currentGroupIdFlow.value = groupId
    }

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun setSearchActive(active: Boolean) {
        isSearchActiveFlow.value = active
        if (!active) {
            searchQueryFlow.value = ""
        }
    }

    fun setSortOption(sortOption: VaultSortOption) {
        sortOptionFlow.value = sortOption
    }

    fun copyPassword(entry: UiVaultEntry) {
        // M1 整改：列表投影不携带密码明文，复制时按需单条解密
        viewModelScope.launch {
            val password = vaultRepository.getEntryPassword(entry.id).orEmpty()
            clipboardSecurityManager?.copySensitiveText(entry.title, password)
            userMessageFlow.update { UiMessage(R.string.vault_copy_password_done, listOf(entry.title)) }
        }
    }

    fun copyUsername(entry: UiVaultEntry) {
        if (entry.username.isNotBlank()) {
            clipboardSecurityManager?.copyPlainText(entry.title, entry.username)
            userMessageFlow.update { UiMessage(R.string.vault_copy_username_done, listOf(entry.username)) }
        } else {
            userMessageFlow.update { UiMessage(R.string.vault_copy_username_missing) }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    // 批量管理操作
    fun startBatchMode(initialEntryId: String) {
        isBatchModeFlow.value = true
        selectedEntryIdsFlow.value = setOf(initialEntryId)
    }

    fun toggleEntrySelection(entryId: String) {
        val current = selectedEntryIdsFlow.value.toMutableSet()
        if (entryId in current) {
            current.remove(entryId)
            if (current.isEmpty()) {
                isBatchModeFlow.value = false
            }
        } else {
            current.add(entryId)
        }
        selectedEntryIdsFlow.value = current
    }

    fun selectAllEntries() {
        val allIds = uiState.value.entries.map { it.id }.toSet()
        selectedEntryIdsFlow.value = allIds
    }

    fun clearBatchSelection() {
        isBatchModeFlow.value = false
        selectedEntryIdsFlow.value = emptySet()
    }

    fun batchMoveSelected(targetGroupId: String?) {
        val selected = selectedEntryIdsFlow.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val result = vaultRepository.batchMoveEntries(selected, targetGroupId)
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_batch_moved, listOf(selected.size)) }
                clearBatchSelection()
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    fun batchDeleteSelected() {
        val selected = selectedEntryIdsFlow.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val result = vaultRepository.batchDeleteEntries(selected)
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_batch_deleted, listOf(selected.size)) }
                clearBatchSelection()
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    // 下拉手势同步触发：真实执行 SyncCoordinator 全量同步（不再使用演示性假桩）
    fun triggerPullRefresh() {
        if (isSyncingFlow.value) return
        viewModelScope.launch {
            isSyncingFlow.value = true
            syncStatusFlow.value = VaultSyncStatus.SYNCING
            val outcome = syncCoordinator.syncNow()
            applySyncOutcome(outcome)
            surfaceSyncCacheEvents()
            isSyncingFlow.value = false
        }
    }

    /**
     * ICacheSupervisor 六事件上浮：把引擎层缓存监督事件转化为可读的用户提示，
     * 覆盖「云端已更新刷新本地」「保存失败留本地」两类最需要用户知情的事件
     */
    private fun surfaceSyncCacheEvents() {
        val events = syncCoordinator.recentSyncEvents.value
        when {
            events.any { it is SyncCacheEvent.CouldntSaveToRemote } ->
                userMessageFlow.update { UiMessage(R.string.vault_sync_saved_locally) }
            events.any { it is SyncCacheEvent.UpdatedCachedFileOnLoad } ->
                userMessageFlow.update { UiMessage(R.string.vault_sync_remote_updated) }
            else -> Unit
        }
    }

    private fun applySyncOutcome(outcome: com.keepasskey.app.sync.SyncOutcome) {
        when (outcome) {
            is com.keepasskey.app.sync.SyncOutcome.UpToDate -> {
                syncStatusFlow.value = VaultSyncStatus.SYNCED
                lastSyncTimeMillisFlow.value = System.currentTimeMillis()
                userMessageFlow.update { UiMessage(R.string.vault_sync_completed) }
            }
            is com.keepasskey.app.sync.SyncOutcome.UploadedLocal,
            is com.keepasskey.app.sync.SyncOutcome.MergedAndUploaded -> {
                syncStatusFlow.value = VaultSyncStatus.SYNCED
                lastSyncTimeMillisFlow.value = System.currentTimeMillis()
                userMessageFlow.update { UiMessage(R.string.vault_sync_uploaded) }
            }
            is com.keepasskey.app.sync.SyncOutcome.ConflictNeedsUser -> {
                syncStatusFlow.value = VaultSyncStatus.CONFLICT
                userMessageFlow.update { UiMessage(R.string.sync_feedback_conflict) }
            }
            is com.keepasskey.app.sync.SyncOutcome.Offline -> {
                syncStatusFlow.value = VaultSyncStatus.OFFLINE
                userMessageFlow.update { UiMessage(R.string.sync_feedback_offline) }
            }
            is com.keepasskey.app.sync.SyncOutcome.Error -> {
                syncStatusFlow.value = VaultSyncStatus.OFFLINE
                userMessageFlow.update { UiMessage(R.string.sync_feedback_error, listOf(outcome.message)) }
            }
        }
    }

    fun createGroup(name: String, iconName: String = "folder") {
        if (name.isBlank() || isReadOnlyFlow.value) return
        viewModelScope.launch {
            val newGroup = VaultGroup(
                id = "group_${System.currentTimeMillis()}",
                name = name.trim(),
                parentId = currentGroupIdFlow.value,
                iconName = iconName,
                orderIndex = (uiState.value.currentGroups.maxOfOrNull { it.orderIndex } ?: 0) + 1,
                updatedAt = "刚刚",
                createdAt = "刚刚"
            )
            val result = vaultRepository.saveGroup(newGroup)
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_group_created, listOf(name.trim())) }
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    fun renameGroup(group: VaultGroup, newName: String) {
        if (newName.isBlank() || isReadOnlyFlow.value) return
        viewModelScope.launch {
            val result = vaultRepository.saveGroup(group.copy(name = newName.trim(), updatedAt = "刚刚"))
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_group_renamed, listOf(newName.trim())) }
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    fun changeGroupIcon(group: VaultGroup, newIcon: String) {
        if (isReadOnlyFlow.value) return
        viewModelScope.launch {
            vaultRepository.saveGroup(group.copy(iconName = newIcon, updatedAt = "刚刚"))
            userMessageFlow.update { UiMessage(R.string.vault_group_icon_updated) }
        }
    }

    fun deleteGroup(groupId: String) {
        if (isReadOnlyFlow.value) return
        viewModelScope.launch {
            val result = vaultRepository.deleteGroup(groupId)
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_group_deleted) }
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    fun restoreEntry(entryId: String) {
        if (isReadOnlyFlow.value) return
        viewModelScope.launch {
            val result = vaultRepository.restoreEntry(entryId)
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_entry_restored) }
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    fun purgeEntry(entryId: String) {
        if (isReadOnlyFlow.value) return
        viewModelScope.launch {
            val result = vaultRepository.deleteEntry(entryId)
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_entry_purged) }
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    fun emptyRecycleBin() {
        if (isReadOnlyFlow.value) return
        viewModelScope.launch {
            val result = vaultRepository.emptyRecycleBin()
            if (result is KdbxResult.Success) {
                userMessageFlow.update { UiMessage(R.string.vault_recycle_emptied) }
            } else {
                userMessageFlow.update { UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)) }
            }
        }
    }

    private fun calculateCurrentRemainingSeconds(): Int {
        val nowSec = (System.currentTimeMillis() / MILLIS_PER_SECOND).toInt()
        val remainder = nowSec % TOTP_PERIOD_SECONDS
        return TOTP_PERIOD_SECONDS - remainder
    }

    /**
     * 将时间戳格式化为相对日期文案（今天 / 昨天 / 具体日期）+ HH:mm；
     * 0 表示本会话尚未执行过同步
     */
    private fun formatLastSyncTime(millis: Long): String {
        if (millis <= 0L) return NEVER_SYNCED_TEXT
        val dateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val datePrefix = when (dateTime.toLocalDate()) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> dateTime.format(DateTimeFormatter.ofPattern("M月d日"))
        }
        return "$datePrefix ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    companion object {
        private const val TOTP_PERIOD_SECONDS = 30
        private const val MILLIS_PER_SECOND = 1000L
        private const val TOTP_TICK_INTERVAL_MS = 1000L
        private const val NEVER_SYNCED_TEXT = "尚未同步"
    }
}
