package com.keepasskey.app.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val userMessageFlow = MutableStateFlow<String?>(null)

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

    val uiState: StateFlow<VaultListUiState> = combine(
        vaultRepository.getGroups(),
        vaultRepository.getEntries(),
        settingsRepository.getSettings(),
        combine(currentGroupIdFlow, filterParamsFlow, userMessageFlow) { groupId, params, message ->
            Triple(groupId, params, message)
        }
    ) { allGroups, allEntries, settings, (currentGroupId, params, message) ->
        val isSearching = params.query.isNotBlank()

        // 计算当前面包屑路径
        val breadcrumbs = mutableListOf<VaultGroup>()
        var curId = currentGroupId
        while (curId != null) {
            val grp = allGroups.find { it.id == curId } ?: break
            breadcrumbs.add(0, grp)
            curId = grp.parentId
        }

        val isInsideRecycleBin = currentGroupId == "group_recycle_bin" || breadcrumbs.any { it.isRecycleBin }

        // 1. 过滤条目：搜索时全局匹配，正常时只展示当前文件夹下的条目（回收站除外）
        val targetEntries = if (isSearching) {
            allEntries.filter { if (!isInsideRecycleBin) it.groupId != "group_recycle_bin" else true }
        } else {
            allEntries.filter { it.groupId == currentGroupId }
        }

        val filteredEntries = targetEntries.filter { entry ->
            params.query.isBlank() ||
                    entry.title.contains(params.query, ignoreCase = true) ||
                    entry.username.contains(params.query, ignoreCase = true) ||
                    entry.url.contains(params.query, ignoreCase = true)
        }

        // 2. 排序条目（默认顺序、按名称、按日期、按修改日期、按创建日期）
        val sortedEntries = when (params.sortOption) {
            VaultSortOption.DEFAULT -> filteredEntries.sortedBy { it.orderIndex }
            VaultSortOption.NAME -> filteredEntries.sortedBy { it.title.lowercase() }
            VaultSortOption.DATE -> filteredEntries.sortedByDescending { it.updatedAt }
            VaultSortOption.MODIFIED_DATE -> filteredEntries.sortedByDescending { it.updatedAt }
            VaultSortOption.CREATED_DATE -> filteredEntries.sortedByDescending { it.createdAt }
        }

        // 3. 文件夹：搜索时匹配文件夹名称，非搜索时显示当前层级的子文件夹；文件夹始终排在条目之前
        val targetGroups = if (isSearching) {
            allGroups.filter { it.name.contains(params.query, ignoreCase = true) }
        } else {
            allGroups.filter { it.parentId == currentGroupId }
        }

        val sortedGroups = when (params.sortOption) {
            VaultSortOption.DEFAULT -> targetGroups.sortedBy { it.orderIndex }
            VaultSortOption.NAME -> targetGroups.sortedBy { it.name.lowercase() }
            VaultSortOption.DATE -> targetGroups.sortedByDescending { it.updatedAt }
            VaultSortOption.MODIFIED_DATE -> targetGroups.sortedByDescending { it.updatedAt }
            VaultSortOption.CREATED_DATE -> targetGroups.sortedByDescending { it.createdAt }
        }

        VaultListUiState(
            searchQuery = params.query,
            isSearchActive = params.isSearchActive,
            sortOption = params.sortOption,
            currentGroupId = currentGroupId,
            isInsideRecycleBin = isInsideRecycleBin,
            breadcrumbs = breadcrumbs,
            currentGroups = sortedGroups,
            entries = sortedEntries,
            totalEntriesCount = allEntries.size,
            databaseName = "keepasskey.kdbx",
            userMessage = message,
            showUsernameInList = settings.showUsernameInList,
            showOtpInList = settings.showOtpInList,
            showPasskeyBadge = settings.showPasskeyBadge
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
        if (currentGroupIdFlow.value == null) return
        val currentBreadcrumbs = uiState.value.breadcrumbs
        if (currentBreadcrumbs.size <= 1) {
            currentGroupIdFlow.value = null
        } else {
            currentGroupIdFlow.value = currentBreadcrumbs[currentBreadcrumbs.size - 2].id
        }
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
        userMessageFlow.update { "已安全复制 ${entry.title} 的密码到剪贴板" }
    }

    fun copyUsername(entry: UiVaultEntry) {
        if (entry.username.isNotBlank()) {
            userMessageFlow.update { "已安全复制用户名: ${entry.username}" }
        } else {
            userMessageFlow.update { "该凭据未配置用户名" }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
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
            userMessageFlow.update { "已创建文件夹「$name」" }
        }
    }

    fun renameGroup(group: VaultGroup, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            vaultRepository.saveGroup(group.copy(name = newName.trim(), updatedAt = "刚刚"))
            userMessageFlow.update { "已重命名为「$newName」" }
        }
    }

    fun changeGroupIcon(group: VaultGroup, newIcon: String) {
        viewModelScope.launch {
            vaultRepository.saveGroup(group.copy(iconName = newIcon, updatedAt = "刚刚"))
            userMessageFlow.update { "已更新文件夹图标" }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            vaultRepository.deleteGroup(groupId)
            userMessageFlow.update { "已删除文件夹" }
        }
    }

    fun restoreEntry(entryId: String) {
        viewModelScope.launch {
            vaultRepository.restoreEntry(entryId)
            userMessageFlow.update { "凭据条目已成功还原至根目录" }
        }
    }

    fun purgeEntry(entryId: String) {
        viewModelScope.launch {
            vaultRepository.deleteEntry(entryId)
            userMessageFlow.update { "凭据已从回收站彻底删除" }
        }
    }

    fun emptyRecycleBin() {
        viewModelScope.launch {
            vaultRepository.emptyRecycleBin()
            userMessageFlow.update { "回收站已清空" }
        }
    }
}
