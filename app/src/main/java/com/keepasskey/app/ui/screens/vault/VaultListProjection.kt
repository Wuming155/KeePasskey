package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.childdb.ChildDatabaseEntryProjection
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.data.repository.VaultTemplateFactory
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.ChildVaultEntryGroup
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ExtendedSettings

/**
 * 密码库列表的**纯投影层**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 本文件只做「多路输入流 → [VaultListUiState]」的纯函数装配：过滤 / 排序 / 面包屑 /
 * 回收站集合 / 分组路径 / 子库分区可见性。**不持有任何可变状态、不触发任何 IO**，
 * 故可脱离 ViewModel 独立推理与测试。原 `VaultListViewModel` 内联的 combine 变换体
 * 逐字迁移至此，行为零变更。
 */

/** 搜索防抖后的过滤参数快照 */
internal data class VaultListFilterParams(
    val query: String,
    val isSearchActive: Boolean,
    val sortOption: VaultSortOption
)

/** 批量选择与同步指示的聚合快照 */
internal data class VaultListBatchAndSyncState(
    val isBatchMode: Boolean,
    val selectedEntryIds: Set<String>,
    val syncStatus: VaultSyncStatus,
    val isSyncing: Boolean,
    val lastSyncTimeText: String,
    val hasPendingConflict: Boolean = false,
    val isReadOnly: Boolean = false
)

/**
 * 子库投影通道的快照（条目投影 + 已挂载计数）。
 *
 * 两者语义不同且**必须同时下发**：投影只含「已解锁」子库（用于渲染分区），
 * 计数含「已挂载」子库（用于搜索态下如实提示「子库条目不参与搜索」）。
 */
internal data class VaultListChildDatabaseSnapshotState(
    val projections: List<ChildDatabaseEntryProjection> = emptyList(),
    val mountedCount: Int = 0
)

/** 会话态输入快照（导航 / 过滤 / 消息 / TOTP / 进阶偏好 / 子库） */
internal data class VaultListSessionState(
    val currentGroupId: String?,
    val filterParams: VaultListFilterParams,
    val userMessage: UiMessage?,
    val totpRemainingSeconds: Int,
    // ISSUE-P3-17：进阶显示偏好快照（列表密度 / 搜索结果分组路径）
    val extended: ExtendedSettings = ExtendedSettings(),
    // ISSUE-P3-17：自动聚焦搜索栏的一次性意图（消费后置 false）
    val autoActivateSearch: Boolean = false,
    // ISSUE-P3-30：子库只读投影快照（已解锁条目 + 已挂载计数）
    val childDatabase: VaultListChildDatabaseSnapshotState = VaultListChildDatabaseSnapshotState()
)

/** 批量/同步状态与展示装饰的聚合体（避开 combine 五流上限的元组嵌套） */
internal data class VaultListBatchSyncDecorations(
    val batchAndSync: VaultListBatchAndSyncState,
    val decorations: EntryDecorations,
    val groupIcons: Map<String, BitmapEntryIcon>
)

/**
 * 把全部输入流快照投影为最终 UI 状态（原 `VaultListViewModel` combine 变换体逐字迁移）。
 */
@Suppress("LongParameterList")
internal fun buildVaultListUiState(
    databases: List<VaultDatabaseInfo>,
    allGroups: List<VaultGroup>,
    allEntries: List<UiVaultEntry>,
    settings: UserSettings,
    session: VaultListSessionState,
    batchSyncDecorations: VaultListBatchSyncDecorations,
    liveTotpCodes: Map<String, String>
): VaultListUiState {
    val batchSync = batchSyncDecorations.batchAndSync
    val activeDb = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
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
        matchesSearchQuery(entry, session.filterParams.query)
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
    val entriesWithLiveTotp = sortedEntries.map { entry ->
        val liveCode = liveTotpCodes[entry.id] ?: entry.totpCode
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

    // 5. ISSUE-P3-17：搜索结果行的分组路径（仅在「搜索中 + 开关开启」时装配）
    val showGroupInSearchResult = session.extended.showGroupInSearchResult
    val entryGroupPaths = if (isSearching && showGroupInSearchResult) {
        buildEntryGroupPaths(allGroups, entriesWithLiveTotp)
    } else {
        emptyMap()
    }

    // 6. ISSUE-P3-30：已解锁子库的只读分区装配。
    // 只做「投影 → 展示结构」的归拢，不参与上面的过滤 / 排序 / 分组树遍历：
    // 子库条目既不进 entries（根库条目流），也不参与搜索与自动填充。
    val childEntryGroups: List<ChildVaultEntryGroup> =
        ChildVaultEntryPresenter.groupsOf(session.childDatabase.projections)
    // 可见性判定与列表数据同处状态层：搜索态 / 根库子分组态一律不展示（理由见 UiState KDoc）
    val childEntrySectionVisible = !isSearching &&
        session.currentGroupId == null &&
        childEntryGroups.isNotEmpty()

    // 7. ISSUE-P3-51：库内「模板」分组内的条目（供「从模板新建」选择器）。
    // 未安装模板库（无同名分组）时为空表，创建对话框据此隐藏该入口。
    val templateGroupIds = allGroups
        .filter { it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME }
        .mapTo(mutableSetOf()) { it.id }
    val templateEntries = if (templateGroupIds.isEmpty()) {
        emptyList()
    } else {
        allEntries.filter { it.groupId in templateGroupIds }.sortedBy { it.orderIndex }
    }

    return VaultListUiState(
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
        databaseName = activeDb?.name.orEmpty(),
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
        hideFabOnScroll = settings.hideFabOnScroll,
        listDensity = session.extended.listDensity,
        showGroupInSearchResult = showGroupInSearchResult,
        entryGroupPaths = entryGroupPaths,
        autoActivateSearch = session.autoActivateSearch,
        groupIcons = batchSyncDecorations.groupIcons,
        decorations = batchSyncDecorations.decorations,
        childEntryGroups = childEntryGroups,
        mountedChildDatabaseCount = session.childDatabase.mountedCount,
        childEntrySectionVisible = childEntrySectionVisible,
        templateEntries = templateEntries
    )
}

/**
 * 全文搜索匹配：命中条目任一处非受保护文本即算匹配。
 *
 * 覆盖范围（对齐 README「全文搜索」契约）：标题 / 用户名 / URL / 备注 / 标签 /
 * 自定义字段的键与**非受保护**值。受保护字段（`isProtected=true`）的明文不进投影
 * （见 [com.keepasskey.app.ui.model.UiCustomField]），故天然不参与命中，
 * 避免搜索侧信道泄露机密；其字段**键**属元数据（如 `TOTP Seed`）仍可命中。
 *
 * 空查询恒为真（未搜索时不过滤）。
 */
internal fun matchesSearchQuery(entry: UiVaultEntry, query: String): Boolean {
    if (query.isBlank()) return true
    if (entry.title.contains(query, ignoreCase = true)) return true
    if (entry.username.contains(query, ignoreCase = true)) return true
    if (entry.url.contains(query, ignoreCase = true)) return true
    if (entry.notes.contains(query, ignoreCase = true)) return true
    if (entry.tags.any { it.contains(query, ignoreCase = true) }) return true
    return entry.customFields.any { field ->
        field.key.contains(query, ignoreCase = true) ||
            (!field.isProtected && field.value.contains(query, ignoreCase = true))
    }
}

/**
 * ISSUE-P3-17：条目 id → 所属分组完整路径（搜索结果行）。
 * 归属分组缺失（条目在根级或分组投影暂缺）时不产出条目，由行组件如实不展示路径。
 */
private fun buildEntryGroupPaths(
    allGroups: List<VaultGroup>,
    entries: List<UiVaultEntry>
): Map<String, String> {
    val paths = GroupPathPresenter.pathsOf(allGroups)
    return entries.mapNotNull { entry ->
        val groupId = entry.groupId ?: return@mapNotNull null
        paths[groupId]?.let { path -> entry.id to path }
    }.toMap()
}
