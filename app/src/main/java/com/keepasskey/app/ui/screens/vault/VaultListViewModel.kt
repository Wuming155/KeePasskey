package com.keepasskey.app.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.EntryDisplayDispatcher
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 密码库列表状态容器 ViewModel，基于 Flow 响应式驱动 UI 状态组合。
 *
 * ## 子库（ISSUE-P3-30）展示边界裁决
 *
 * 已解锁子库的条目以**只读分区**并列展示（`ChildVaultEntryRow`），与根库条目
 * （`UiVaultEntry`）分属两个类型与两个状态字段。本类据此明确三项边界：
 *
 * 1. **只读**：子库行不提供任何点击 / 长按 / 复制 / 编辑 / 删除入口——本类所有写方法
 *    （`batchMoveSelected` / `batchDeleteSelected` / `purgeEntry` / `restoreEntry` …）
 *    的入参都来自根库条目流，子库行在类型层面进不来。**刻意不做**按 UUID 的运行时拒绝：
 *    子库可能是根库副本（UUID 全同），按 ID 拒绝会误伤根库自身的合法编辑。
 * 2. **不参与搜索**：搜索只过滤 `vaultRepository` 的根库条目；子库行不经关键词过滤，
 *    搜索态下整块分区隐藏（不做「搜不到但仍显示」的半吊子行为），并由 UI 如实提示原因。
 *    首版裁决理由：子库投影是**已解密条目的展示快照**，未解锁时无任何可检索内容，
 *    若纳入搜索会出现「同一子库时而搜得到、时而不见」的降级歧义；先不纳入更诚实。
 * 3. **不参与自动填充**：`KeePasskeyAutofillService` 走 `VaultRepository`，而子库投影
 *    从不进入该仓库（核心层 `ChildDatabaseSessionManager` KDoc 的同步交互不变量），
 *    故自动填充零改动、零新增暴露面。
 *
 * 同步 / 合并 / 历史 / 回收站路径同样零子库条目——本类不改动、不触碰这些路径，
 * 结构断言见 `ChildDatabaseSyncIsolationTest` 与本批 `VaultListChildDatabaseTest`。
 *
 * ## ISSUE-P3-29 拆分说明
 *
 * 原 803 行巨型类已按职责拆为四个协作者，本类只保留「输入流编排 + 状态投影 + 对外 API 门面」：
 * - 纯投影：[buildVaultListUiState]（`VaultListProjection.kt`）
 * - 同步指示：[VaultListSyncController]
 * - TOTP 倒计时：[VaultListTotpTracker]
 * - 写操作与剪贴板：[VaultListActionController]
 * - 展示装饰：[VaultListDecorationsProvider]
 * 拆分为**纯结构性**：公开 API 与状态输出零变化。
 */
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val clipboardSecurityManager: ClipboardSecurityManager? = null,
    private val syncCoordinator: com.keepasskey.app.sync.SyncCoordinator,
    // TASK-21：非 Compose 层文案资源解析通道（生产 DI 注入真实现；单测注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // ISSUE-P3-02：展示装配调度器（生产 Dispatchers.Default；单测注入测试调度器保证断言确定性）
    @EntryDisplayDispatcher private val displayDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // ISSUE-P3-17：进阶显示偏好通道（列表密度 / 分组路径 / 自动聚焦搜索）。
    // 该通道只有同步快照读取（无 Flow），故在此以 StateFlow 承载快照，页面进入时刷新；
    // null 仅用于纯 JVM 单测（生产 DI 经 ExtendedSettingsSourceModule 恒注入）
    private val extendedSettingsSource: ExtendedSettingsSource? = null,
    // ISSUE-P3-30：子库只读投影通道（生产 DI 注入 @Singleton 单例）。
    // null 仅用于不涉子库的纯 JVM 单测；无该通道时子库分区恒为空表，不影响根库列表任何行为。
    private val childDatabaseSessionManager: ChildDatabaseSessionManager? = null
) : ViewModel() {

    // P3-23：null 时回退空串实现（生产 Hilt 恒注入 StringsProviderModule 真实现）
    private val strings: StringsProvider = stringsProvider ?: StringsProvider { _, _ -> "" }

    private val currentGroupIdFlow = MutableStateFlow<String?>(null)
    private val searchQueryFlow = MutableStateFlow("")
    private val isSearchActiveFlow = MutableStateFlow(false)
    private val sortOptionFlow = MutableStateFlow(VaultSortOption.DEFAULT)
    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)

    // H2 整改：存在待解决的冲突会话时驱动「去解决冲突」入口
    private val hasPendingConflictFlow = MutableStateFlow(false)

    // H4-只读整改：会话只读标志（解锁时刻确定，只读时禁用新增/批量编辑入口）
    private val isReadOnlyFlow = MutableStateFlow(vaultRepository.isSessionReadOnly())

    // ISSUE-P3-17：进阶显示偏好快照（构造期读取一次；页面每次进入组合时经 onScreenEntered 刷新）。
    // 无偏好通道（单测未注入）时回落到 ExtendedSettings 默认值，与生产未落库语义一致。
    private val initialExtendedSettings: ExtendedSettings =
        extendedSettingsSource?.load() ?: ExtendedSettings()
    private val extendedSettingsFlow = MutableStateFlow(initialExtendedSettings)

    // ISSUE-P3-17：autoActivateSearchOnOpen 是「打开数据库后」的一次性意图——仅在 ViewModel
    // 构造（= 解锁后首次进入列表页）时装载，页面返回 / 重组不重复装载，避免反复抢焦点弹输入法
    private val autoActivateSearchFlow =
        MutableStateFlow(initialExtendedSettings.autoActivateSearchOnOpen)

    // ISSUE-P3-29：写操作 / 剪贴板编排（批量状态由该协作者持有，对外只读暴露）
    private val actions = VaultListActionController(
        repository = vaultRepository,
        scope = viewModelScope,
        strings = strings,
        clipboardSecurityManager = clipboardSecurityManager,
        isReadOnly = { isReadOnlyFlow.value },
        currentGroupId = { currentGroupIdFlow.value },
        currentGroups = { uiState.value.currentGroups },
        currentEntryIds = { uiState.value.entries.map { it.id } },
        onMessage = { userMessageFlow.value = it }
    )

    // ISSUE-P3-29：同步指示与下拉刷新编排
    private val syncController = VaultListSyncController(
        syncCoordinator = syncCoordinator,
        scope = viewModelScope,
        strings = strings,
        onMessage = { userMessageFlow.value = it }
    )

    // ISSUE-P3-29：TOTP 实时倒计时（种子只在数据层解析）
    private val totpTracker = VaultListTotpTracker(
        vaultRepository = vaultRepository,
        scope = viewModelScope,
        currentEntries = { uiState.value.entries }
    )

    // ISSUE-P3-29：条目 / 分组展示装饰装配（共用同一图标投影缓存）
    private val decorations = VaultListDecorationsProvider(vaultRepository, displayDispatcher)

    init {
        // P1 整改：秒级 tick 改由官方 tickerFlow 冷流驱动，单一 tick 源 + 虚拟时钟可推进。
        totpTracker.start()
        // H2 整改：订阅冲突会话流，冲突待解决时点亮列表页冲突入口
        viewModelScope.launch {
            syncCoordinator.conflictFlow.collect { conflicts ->
                hasPendingConflictFlow.value = conflicts.isNotEmpty()
            }
        }
        // 断点11 整改：解锁进入列表页即自动重同步一次（配置了云同步才触发），
        // 避免解锁后停留在缓存旧数据直到手动下拉刷新
        if (syncController.isSyncConfigured()) {
            triggerPullRefresh()
        }
    }

    /**
     * P2 整改：搜索输入防抖 —— 官方 kotlinx.coroutines `Flow.debounce` 标准算子。
     *
     * 原实现 searchQueryFlow 直接进 combine，每敲一个字符都触发一次
     * 「全条目过滤 + 排序 + 分组树遍历 + 回收站集合构建」的完整重算，
     * 大库场景下构成明显掉帧源；现在输入停顿 SEARCH_DEBOUNCE_MS 后才下发新关键词。
     *
     * 首帧补发（flow{emit(current); emitAll(...)}）：保证初次渲染与
     * 「关闭搜索时立即清空关键词」不被防抖窗口延迟，distinctUntilChanged 负责吸收重复值。
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearchQueryFlow: Flow<String> = flow {
        emit(searchQueryFlow.value)
        emitAll(searchQueryFlow.debounce(SEARCH_DEBOUNCE_MS))
    }.distinctUntilChanged()

    private val filterParamsFlow = combine(
        debouncedSearchQueryFlow,
        isSearchActiveFlow,
        sortOptionFlow
    ) { query, isSearchActive, sortOption ->
        VaultListFilterParams(query, isSearchActive, sortOption)
    }

    private val batchAndSyncFlow = combine(
        actions.isBatchMode,
        actions.selectedEntryIds,
        syncController.syncStatus,
        syncController.isSyncing,
        syncController.lastSyncTimeText
    ) { isBatch, selected, status, syncing, lastSyncText ->
        VaultListBatchAndSyncState(isBatch, selected, status, syncing, lastSyncText)
    }.combine(hasPendingConflictFlow) { state, hasConflict ->
        state.copy(hasPendingConflict = hasConflict)
    }.combine(isReadOnlyFlow) { state, readOnly ->
        state.copy(isReadOnly = readOnly)
    }

    /**
     * ISSUE-P3-30：子库只读投影流。
     *
     * 只订阅核心层既有 `StateFlow`（`projectedEntries` / `mountedCount`），**不新增任何写通道**：
     * 本 ViewModel 不持有子库凭据，也不提供任何子库写方法。锁定根库时核心层
     * `ChildDatabaseSessionManager.onSessionLocked` 会终止会话并令 `projectedEntries` 归空，
     * 子库分区因此**自动消失**，无需 UI 侧额外清理。
     *
     * 未注入通道（单测）时恒为空表快照，根库列表行为零变化。
     */
    private val childDatabaseStateFlow: Flow<VaultListChildDatabaseSnapshotState> =
        childDatabaseSessionManager?.let { manager ->
            combine(manager.projectedEntries, manager.mountedCount) { projections, count ->
                VaultListChildDatabaseSnapshotState(projections = projections, mountedCount = count)
            }
        } ?: flowOf(VaultListChildDatabaseSnapshotState())

    private val sessionStateFlow = combine(
        currentGroupIdFlow,
        filterParamsFlow,
        userMessageFlow,
        totpTracker.remainingSeconds
    ) { groupId, params, message, totpSeconds ->
        VaultListSessionState(groupId, params, message, totpSeconds)
    }.combine(extendedSettingsFlow) { state, extended ->
        state.copy(extended = extended)
    }.combine(autoActivateSearchFlow) { state, autoActivate ->
        state.copy(autoActivateSearch = autoActivate)
    }.combine(childDatabaseStateFlow) { state, childDatabase ->
        state.copy(childDatabase = childDatabase)
    }

    /** 批量/同步状态与展示装饰的聚合体（避开 combine 五流上限的元组嵌套） */
    private val batchSyncDecorationsFlow = combine(
        batchAndSyncFlow,
        decorations.entryDecorations,
        decorations.groupIcons
    ) { batchAndSync, entryDecorations, groupIcons ->
        VaultListBatchSyncDecorations(batchAndSync, entryDecorations, groupIcons)
    }

    val uiState: StateFlow<VaultListUiState> = combine(
        combine(vaultRepository.getDatabases(), vaultRepository.getGroups()) { dbs, groups -> Pair(dbs, groups) },
        vaultRepository.getEntries(),
        settingsRepository.getSettings(),
        sessionStateFlow,
        batchSyncDecorationsFlow
    ) { (databases, allGroups), allEntries, settings, session, batchSyncDecorations ->
        buildVaultListUiState(
            databases = databases,
            allGroups = allGroups,
            allEntries = allEntries,
            settings = settings,
            session = session,
            batchSyncDecorations = batchSyncDecorations,
            liveTotpCodes = totpTracker.liveCodes.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VaultListUiState()
    )

    /**
     * ISSUE-P3-17：页面每次进入组合时刷新进阶显示偏好快照。
     *
     * 偏好通道只有同步 `load()`（无 Flow），故由页面在进入时主动拉取一次：
     * 用户在设置页改动后返回列表页即生效，且不引入轮询。
     */
    fun onScreenEntered() {
        val source = extendedSettingsSource ?: return
        extendedSettingsFlow.value = source.load()
    }

    /**
     * ISSUE-P3-17：自动聚焦搜索栏的一次性意图已被 Screen 消费，立即置回，
     * 避免列表重组或从详情页返回时反复抢焦点并弹出输入法。
     */
    fun consumeAutoActivateSearch() {
        autoActivateSearchFlow.value = false
    }

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

    fun copyPassword(entry: UiVaultEntry) = actions.copyPassword(entry)

    fun copyUsername(entry: UiVaultEntry) = actions.copyUsername(entry)

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    // 批量管理操作
    fun startBatchMode(initialEntryId: String) = actions.startBatchMode(initialEntryId)

    fun toggleEntrySelection(entryId: String) = actions.toggleEntrySelection(entryId)

    fun selectAllEntries() = actions.selectAllEntries()

    fun clearBatchSelection() = actions.clearBatchSelection()

    fun batchMoveSelected(targetGroupId: String?) = actions.batchMoveSelected(targetGroupId)

    fun batchDeleteSelected() = actions.batchDeleteSelected()

    /** 下拉手势同步触发：真实执行 SyncCoordinator 全量同步（不再使用演示性假桩） */
    fun triggerPullRefresh() = syncController.triggerPullRefresh()

    fun createGroup(name: String, iconName: String = "folder") = actions.createGroup(name, iconName)

    fun renameGroup(group: VaultGroup, newName: String) = actions.renameGroup(group, newName)

    fun changeGroupIcon(group: VaultGroup, newIcon: String) = actions.changeGroupIcon(group, newIcon)

    fun deleteGroup(groupId: String) = actions.deleteGroup(groupId)

    fun restoreEntry(entryId: String) = actions.restoreEntry(entryId)

    fun purgeEntry(entryId: String) = actions.purgeEntry(entryId)

    fun emptyRecycleBin() = actions.emptyRecycleBin()

    private companion object {
        /** 搜索输入停顿多久后才触发列表重算（毫秒） */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
