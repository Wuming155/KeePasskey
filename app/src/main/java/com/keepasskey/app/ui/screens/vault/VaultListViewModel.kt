package com.keepasskey.app.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val settingsRepository: SettingsRepository
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
    // 上次同步完成时间戳，驱动下拉指示区的「上次同步」文案
    private val lastSyncTimeMillisFlow = MutableStateFlow(defaultLastSyncMillis())

    // TOTP 剩余秒数倒计时，与验证器页共用 30 秒周期窗口
    private val totpRemainingSecondsFlow = MutableStateFlow(calculateCurrentRemainingSeconds())

    init {
        // 每秒刷新 TOTP 剩余秒数，驱动列表内验证码环形倒计时实时跳动
        viewModelScope.launch {
            while (isActive) {
                delay(TOTP_TICK_INTERVAL_MS)
                totpRemainingSecondsFlow.value = calculateCurrentRemainingSeconds()
            }
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
        val lastSyncTimeText: String
    )

    private val batchAndSyncFlow = combine(
        isBatchModeFlow,
        selectedEntryIdsFlow,
        syncStatusFlow,
        isSyncingFlow,
        lastSyncTimeMillisFlow
    ) { isBatch, selected, status, syncing, lastSyncMillis ->
        BatchAndSyncState(isBatch, selected, status, syncing, formatLastSyncTime(lastSyncMillis))
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

        val isInsideRecycleBin = session.currentGroupId == "group_recycle_bin" || breadcrumbs.any { it.isRecycleBin }

        // 1. 过滤条目：搜索时全局匹配，正常时只展示当前文件夹下的条目（回收站除外）
        val targetEntries = if (isSearching) {
            allEntries.filter { if (!isInsideRecycleBin) it.groupId != "group_recycle_bin" else true }
        } else {
            allEntries.filter { it.groupId == session.currentGroupId }
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

        // 3. 带实时 TOTP 剩余秒数的条目列表
        val entriesWithLiveTotp = sortedEntries.map { entry ->
            if (entry.totpCode != null) {
                entry.copy(totpRemainingSeconds = session.totpRemainingSeconds)
            } else {
                entry
            }
        }

        // 4. 文件夹
        val targetGroups = if (isSearching) {
            allGroups.filter { it.name.contains(session.filterParams.query, ignoreCase = true) }
        } else {
            allGroups.filter { it.parentId == session.currentGroupId }
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
        userMessageFlow.update { UiMessage(R.string.vault_copy_password_done, listOf(entry.title)) }
    }

    fun copyUsername(entry: UiVaultEntry) {
        if (entry.username.isNotBlank()) {
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
            vaultRepository.batchMoveEntries(selected, targetGroupId)
            userMessageFlow.update { UiMessage(R.string.vault_batch_moved, listOf(selected.size)) }
            clearBatchSelection()
        }
    }

    fun batchDeleteSelected() {
        val selected = selectedEntryIdsFlow.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            vaultRepository.batchDeleteEntries(selected)
            userMessageFlow.update { UiMessage(R.string.vault_batch_deleted, listOf(selected.size)) }
            clearBatchSelection()
        }
    }

    // 下拉手势同步触发
    fun triggerPullRefresh() {
        viewModelScope.launch {
            isSyncingFlow.value = true
            syncStatusFlow.value = VaultSyncStatus.SYNCING
            delay(SYNC_VERIFICATION_DELAY_MS)
            isSyncingFlow.value = false
            syncStatusFlow.value = VaultSyncStatus.SYNCED
            lastSyncTimeMillisFlow.value = System.currentTimeMillis()
            userMessageFlow.update { UiMessage(R.string.vault_sync_completed) }
        }
    }

    fun createGroup(name: String, iconName: String = "folder") {
        if (name.isBlank()) return
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
            vaultRepository.saveGroup(newGroup)
            userMessageFlow.update { UiMessage(R.string.vault_group_created, listOf(name.trim())) }
        }
    }

    fun renameGroup(group: VaultGroup, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            vaultRepository.saveGroup(group.copy(name = newName.trim(), updatedAt = "刚刚"))
            userMessageFlow.update { UiMessage(R.string.vault_group_renamed, listOf(newName.trim())) }
        }
    }

    fun changeGroupIcon(group: VaultGroup, newIcon: String) {
        viewModelScope.launch {
            vaultRepository.saveGroup(group.copy(iconName = newIcon, updatedAt = "刚刚"))
            userMessageFlow.update { UiMessage(R.string.vault_group_icon_updated) }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            vaultRepository.deleteGroup(groupId)
            userMessageFlow.update { UiMessage(R.string.vault_group_deleted) }
        }
    }

    fun restoreEntry(entryId: String) {
        viewModelScope.launch {
            vaultRepository.restoreEntry(entryId)
            userMessageFlow.update { UiMessage(R.string.vault_entry_restored) }
        }
    }

    fun purgeEntry(entryId: String) {
        viewModelScope.launch {
            vaultRepository.deleteEntry(entryId)
            userMessageFlow.update { UiMessage(R.string.vault_entry_purged) }
        }
    }

    fun emptyRecycleBin() {
        viewModelScope.launch {
            vaultRepository.emptyRecycleBin()
            userMessageFlow.update { UiMessage(R.string.vault_recycle_emptied) }
        }
    }

    private fun calculateCurrentRemainingSeconds(): Int {
        val nowSec = (System.currentTimeMillis() / MILLIS_PER_SECOND).toInt()
        val remainder = nowSec % TOTP_PERIOD_SECONDS
        return TOTP_PERIOD_SECONDS - remainder
    }

    /**
     * 将时间戳格式化为相对日期文案（今天 / 昨天 / 具体日期）+ HH:mm
     */
    private fun formatLastSyncTime(millis: Long): String {
        val dateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val datePrefix = when (dateTime.toLocalDate()) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> dateTime.format(DateTimeFormatter.ofPattern("M月d日"))
        }
        return "$datePrefix ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    private fun defaultLastSyncMillis(): Long =
        LocalDate.now()
            .atTime(DEFAULT_SYNC_HOUR, DEFAULT_SYNC_MINUTE)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    companion object {
        private const val TOTP_PERIOD_SECONDS = 30
        private const val MILLIS_PER_SECOND = 1000L
        private const val TOTP_TICK_INTERVAL_MS = 1000L
        private const val SYNC_VERIFICATION_DELAY_MS = 1000L

        // 演示用默认「上次同步」时刻（当天 10:25）
        private const val DEFAULT_SYNC_HOUR = 10
        private const val DEFAULT_SYNC_MINUTE = 25
    }
}
