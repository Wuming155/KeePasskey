package com.keepasskey.app.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.ScanPayloadClassifier
import com.keepasskey.app.passkey.ScanPayloadKind
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.EntryDisplayDispatcher
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.TotpCountdownTracker
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
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
 * - TOTP 倒计时：[TotpCountdownTracker]
 * - 写操作与剪贴板：[VaultListActionController]
 * - 展示装饰：[VaultListDecorationsProvider]
 * 拆分为**纯结构性**：公开 API 与状态输出零变化。
 */
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val clipboardSecurityManager: ClipboardSecurityChannel? = null,
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
    private val childDatabaseSessionManager: ChildDatabaseSessionManager? = null,
    private val createEntryPrefill: com.keepasskey.app.ui.screens.edit.CreateEntryPrefillHost? = null,
    // ISSUE-P3-439：高级搜索选项的持久化写通道（与读通道 ExtendedSettingsSource 同一仓库）。
    // null 仅用于纯 JVM 单测；null 时选项仍会话内生效但不落盘（与偏好通道缺位语义一致）
    private val extendedSettingsStore: com.keepasskey.app.data.repository.ExtendedSettingsStore? = null,
    // ISSUE-P3-447 AC③：外部修改漂移的待决态（保存被中止时点亮列表页「未落库」提示）。
    // null 仅用于纯 JVM 单测；生产 DI 注入 @Singleton 真实现（常驻态恒为 false）
    private val vaultFileDriftCoordinator: com.keepasskey.app.security.VaultFileDriftCoordinator? = null
) : ViewModel() {

    // P3-23：null 时回退空串实现（生产 Hilt 恒注入 StringsProviderModule 真实现）
    private val strings: StringsProvider = stringsProvider ?: StringsProvider { _, _ -> "" }

    private val currentGroupIdFlow = MutableStateFlow<String?>(null)
    private val searchQueryFlow = MutableStateFlow("")
    private val isSearchActiveFlow = MutableStateFlow(false)
    private val sortOptionFlow = MutableStateFlow(VaultSortOption.DEFAULT)
    // ISSUE-P3-297 处置③：标签 / 收藏筛选档（会话态，随排序同口径不持久化）
    private val selectedTagFlow = MutableStateFlow<String?>(null)
    private val favoriteOnlyFlow = MutableStateFlow(false)
    internal val userMessageFlow = MutableStateFlow<UiMessage?>(null) // ISSUE-P3-359 AC④：放宽 internal 供同包 publishVaultMessage 双写

    // H2 整改：存在待解决的冲突会话时驱动「去解决冲突」入口
    private val hasPendingConflictFlow = MutableStateFlow(false)

    // H4-只读整改：会话只读标志（解锁时刻确定，只读时禁用新增/批量编辑入口）
    private val isReadOnlyFlow = MutableStateFlow(vaultRepository.isSessionReadOnly())

    // PD-47：顶栏扫码对话框 FLAG_SECURE 跟随设置页「禁止截屏与录屏」开关；
    // 无设置流（纯 JVM 单测注入假实现恒有流，此处初值 true = fail-closed，与编辑页同口径）
    private val _flagSecureEnabled = MutableStateFlow(true)
    val flagSecureEnabled: StateFlow<Boolean> = _flagSecureEnabled.asStateFlow()

    // ISSUE-P3-17：进阶显示偏好快照（构造期读取一次；页面每次进入组合时经 onScreenEntered 刷新）。
    // 无偏好通道（单测未注入）时回落到 ExtendedSettings 默认值，与生产未落库语义一致。
    private val initialExtendedSettings: ExtendedSettings =
        extendedSettingsSource?.load() ?: ExtendedSettings()
    private val extendedSettingsFlow = MutableStateFlow(initialExtendedSettings)

    // ISSUE-P3-439：高级搜索选项（构造期从偏好快照装载；用户改动即写回偏好通道）。
    // 状态与持久化编排在同包 [VaultSearchAdvancedStore]（§280 规模门禁同批拆出，语义零变化）
    internal val searchAdvancedStore =
        VaultSearchAdvancedStore(initialExtendedSettings.searchAdvanced, extendedSettingsStore)

    // ISSUE-P3-17：autoActivateSearchOnOpen 是「打开数据库后」的一次性意图——仅在 ViewModel
    // 构造（= 解锁后首次进入列表页）时装载，页面返回 / 重组不重复装载，避免反复抢焦点弹输入法
    private val autoActivateSearchFlow =
        MutableStateFlow(initialExtendedSettings.autoActivateSearchOnOpen)

    // ISSUE-P3-29：写操作 / 剪贴板编排（批量状态由该协作者持有，对外只读暴露）。
    // 对外门面（copyX / 批量 / 分组 / 回收站 / 通行密钥导入上行）在同包
    // VaultListViewModelActions.kt（§280 规模门禁同批逐字迁出，internal 供扩展访问）
    internal val actions = VaultListActionController(
        repository = vaultRepository,
        scope = viewModelScope,
        strings = strings,
        clipboardSecurityManager = clipboardSecurityManager,
        host = VaultListActionHost(
            isReadOnly = { isReadOnlyFlow.value },
            currentGroupId = { currentGroupIdFlow.value },
            currentGroups = { uiState.value.currentGroups },
            currentEntryIds = { uiState.value.entries.map { it.id } },
            onMessage = { publishVaultMessage(it) }
        )
    )

    // ISSUE-P3-29：同步指示与下拉刷新编排（门面在同包 VaultListViewModelActions.kt）
    internal val syncController = VaultListSyncController(
        syncCoordinator = syncCoordinator,
        scope = viewModelScope,
        strings = strings,
        onMessage = { publishVaultMessage(it) }
    )

    // ISSUE-P3-29：TOTP 实时倒计时（种子只在数据层解析）
    // ISSUE-P2-89：本协作者的两条输出均**不进整页状态**，经下方窄通道直接给列表行徽标。
    // 窄通道的对外暴露（totpNowSeconds / totpLiveCodes）在同包 VaultListTotpChannels.kt
    internal val totpTracker = TotpCountdownTracker(
        vaultRepository = vaultRepository,
        scope = viewModelScope,
        currentEntries = { uiState.value.entries },
        // 生产 Dispatchers.Default；单测注入测试调度器以确定性推进秒级节拍
        dispatcher = displayDispatcher
    )

    // ISSUE-P3-176：两条**整库投影流**共享给本 VM 的 `uiState` 与展示装饰装配两处消费者——
    // `RealVaultRepository` 的这两条是**冷流**，原实现各自订阅一次 ⇒ 一次数据变更做
    // 2 份逐字段解密 + 时间格式化。`replay = 1` 让 `combine` 立即拿到最近值；
    // `WhileSubscribed(5000)` 与 `uiState` 的启停口径一致（离屏 5 s 后停、重新订阅即恢复）。
    private val entriesFlow = vaultRepository.getEntries()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)
    private val groupsFlow = vaultRepository.getGroups()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    // ISSUE-P3-29：条目 / 分组展示装饰装配（共用同一图标投影缓存）
    // ISSUE-P3-176：改为消费上面**已共享**的两条投影流（原实现自行再订阅一次）
    private val decorations = VaultListDecorationsProvider(
        vaultRepository = vaultRepository,
        displayDispatcher = displayDispatcher,
        entries = entriesFlow,
        groups = groupsFlow
    )

    init {
        // P1 整改：秒级 tick 改由官方 tickerFlow 冷流驱动，单一 tick 源 + 虚拟时钟可推进。
        // ISSUE-P2-89：不再需要显式 start()——节拍随 [totpRemainingSeconds] 的订阅自动起停
        // （列表页不可见即停表），此前 init 期常驻启动会在离屏后继续每秒烧 CPU。
        // H2 整改：订阅冲突会话流，冲突待解决时点亮列表页冲突入口
        viewModelScope.launch {
            syncCoordinator.conflictFlow.collect { conflicts ->
                hasPendingConflictFlow.value = conflicts.isNotEmpty()
            }
        }
        // PD-47：随设置流刷新防截屏开关快照（顶栏扫码对话框在组合时消费当前值）
        viewModelScope.launch {
            settingsRepository.getSettings().collect { _flagSecureEnabled.value = it.flagSecureEnabled }
        }
        // 断点11 整改：解锁进入列表页即自动重同步一次（配置了云同步才触发），
        // 避免解锁后停留在缓存旧数据直到手动下拉刷新。
        // ISSUE-P3-272：「自动同步」开关真实接线于此——关闭后本触发点不再自动同步，
        // 仅保留手动下拉刷新 / 设置页手动同步（开关经 ExtendedSettingsStore 持久化）。
        // 显式取值而非内联：`?:` 的优先级高于 `&&`，内联写法虽等价但读起来像
        // 「关掉开关即恒不触发」被短路——本开关与「是否已配置云同步」是**两个独立合取项**。
        // 无偏好通道（纯 JVM 单测未注入）时按默认开启判定，与 ExtendedSettings 默认值一致。
        val autoSyncOnUnlock = extendedSettingsSource?.load()?.autoSyncEnabled ?: true
        if (autoSyncOnUnlock && syncController.isSyncConfigured()) {
            triggerPullRefresh()
        }
    }

    /**
     * P2 整改：搜索输入防抖（官方 `Flow.debounce`）——原实现每字符触发一次全量过滤重算（大库
     * 掉帧源），现停顿 SEARCH_DEBOUNCE_MS 才下发，首帧补发保初次渲染即时。ISSUE-P2-356：
     * 防抖**只**作用于过滤流——显示回显走 [searchQueryDisplay]，空串零超时旁路立即下发。
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearchQueryFlow: Flow<String> = flow {
        emit(searchQueryFlow.value)
        emitAll(searchQueryFlow.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS })
    }.distinctUntilChanged()

    /** ISSUE-P2-356：搜索框即时回显通道（未防抖，UI 只读）——防抖若波及显示值，快速连打即回吞；顶栏 / 返回键清空判定读本通道。 */
    val searchQueryDisplay: StateFlow<String> = searchQueryFlow.asStateFlow()

    private val filterParamsFlow = combine(
        debouncedSearchQueryFlow,
        isSearchActiveFlow,
        sortOptionFlow,
        selectedTagFlow,
        favoriteOnlyFlow
    ) { query, isSearchActive, sortOption, selectedTag, favoriteOnly ->
        VaultListFilterParams(query, isSearchActive, sortOption, selectedTag, favoriteOnly)
    }

    /**
     * ISSUE-P3-447 AC③：外部修改漂移的待决态（**须先于 [batchAndSyncFlow] 声明**，
     * 否则属性初始化顺序会让后者读到尚未初始化的 null）。
     *
     * 取 `VaultFileDriftCoordinator.pending`（保存中止时置位、用户三选处置后清空），
     * 未装配（纯 JVM 单测）时恒 false——不改变既有状态输出。
     */
    private val externalModificationFlow: Flow<Boolean> =
        vaultFileDriftCoordinator?.pending?.map { it != null } ?: flowOf(false)

    private val batchAndSyncFlow = combine(
        actions.isBatchMode,
        actions.selectedEntryIds,
        syncController.isSyncing,
        syncController.lastSyncTimeText
    ) { isBatch, selected, syncing, lastSyncText ->
        VaultListBatchAndSyncState(isBatch, selected, syncing, lastSyncText)
    }.combine(hasPendingConflictFlow) { state, hasConflict ->
        state.copy(hasPendingConflict = hasConflict)
    }.combine(isReadOnlyFlow) { state, readOnly ->
        state.copy(isReadOnly = readOnly)
    }.combine(externalModificationFlow) { state, blocked ->
        // ISSUE-P3-447 AC③：保存被外部修改中止且尚未处置 ⇒ 列表页提示「含未落库改动」
        state.copy(saveBlockedByExternalModification = blocked)
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

    /**
     * ISSUE-P2-89：TOTP 的秒级倒计时与跨周期验证码**不进整页状态**。
     *
     * 原实现把 `totpTracker.remainingSeconds`（每秒一个值）并入本 `combine`，于是每秒都会
     * 重跑一次 [buildVaultListUiState]——全库过滤 / 排序 / 回收站集合与模板扫描，
     * 且 `stateIn` 的收集上下文是 `viewModelScope`（Main），构成秒级主线程负载与掉帧源。
     * 现两条值只经 [totpRemainingSeconds] / [totpLiveCodes] 窄通道给列表行徽标按需读取。
     */
    private val sessionStateFlow = combine(
        currentGroupIdFlow,
        filterParamsFlow,
        userMessageFlow
    ) { groupId, params, message ->
        VaultListSessionState(groupId, params, message)
    }.combine(extendedSettingsFlow) { state, extended ->
        state.copy(extended = extended)
    }.combine(searchAdvancedStore.state) { state, searchAdvanced ->
        // ISSUE-P3-439：高级搜索选项以本流为准（用户改动即时生效，不等页面进入刷新）
        state.copy(extended = state.extended.copy(searchAdvanced = searchAdvanced))
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
        combine(vaultRepository.getDatabases(), groupsFlow) { dbs, groups -> Pair(dbs, groups) },
        entriesFlow,
        settingsRepository.getSettings(),
        sessionStateFlow,
        batchSyncDecorationsFlow
    ) { (databases, allGroups), allEntries, settings, session, batchSyncDecorations ->
        buildVaultListUiState(
            library = VaultListLibraryState(
                databases = databases,
                allGroups = allGroups,
                allEntries = allEntries
            ),
            settings = settings,
            session = session,
            batchSyncDecorations = batchSyncDecorations
        )
    }
        // ISSUE-P3-174：整库投影（全库过滤 / 排序 / 面包屑上溯 / 回收站后代递归 / 分组路径装配）
        // 必须**离开收集上下文**——`stateIn` 的收集上下文是 `viewModelScope`（即 Main），
        // 故与数据层两条投影流（§117 / ISSUE-P3-154）同口径补 `flowOn`，复用本 VM 已有的
        // 展示调度器（自动填充页 / 验证器页同一写法）。
        .flowOn(displayDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VaultListUiState()
        )

    // ISSUE-P2-89 / ISSUE-P3-158：TOTP 窄通道（totpNowSeconds / totpLiveCodes）随拆分
    // 移至同包 VaultListTotpChannels.kt（KDoc 与判据逐字随迁；§280 规模门禁同批结构性拆分）

    /**
     * ISSUE-P3-17：页面每次进入组合时刷新进阶显示偏好快照。
     *
     * 偏好通道只有同步 `load()`（无 Flow），故由页面在进入时主动拉取一次：
     * 用户在设置页改动后返回列表页即生效，且不引入轮询。
     */
    fun onScreenEntered() {
        val source = extendedSettingsSource ?: return
        val loaded = source.load()
        extendedSettingsFlow.value = loaded
        // ISSUE-P3-439：偏好快照刷新时同步高级搜索选项（设置页与列表页同源）
        searchAdvancedStore.reload(loaded.searchAdvanced)
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

    /** ISSUE-P3-352 AC①：发布当前搜索词为下次新建的一次性预填（空白不发布；宿主缺席为空操作） */
    fun beginCreateEntryFromSearch() {
        createEntryPrefill?.publish(searchQueryFlow.value)
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

    /** ISSUE-P3-297 处置③：切换标签筛选档（再点同一标签 = 取消） */
    fun onTagFilterChange(tag: String?) {
        selectedTagFlow.value = tag
    }

    /** ISSUE-P3-297 处置③：切换「只看收藏」筛选档 */
    fun onFavoriteFilterChange(favoriteOnly: Boolean) {
        favoriteOnlyFlow.value = favoriteOnly
    }

    // 复制 / 批量 / 分组 / 回收站 / 通行密钥导入等对外门面随 §280 规模门禁拆分
    // 逐字迁至同包 VaultListViewModelActions.kt（扩展函数形态，调用点语法不变）

    /**
     * 离开密码库页（ViewModel 销毁）时，尚未确认 / 尚未落库的导入草案**必须**擦除：
     * 它是私钥明文的唯一持有者，只靠 GC 不算清零（§3 敏感数据铁律）。
     * 正常路径（确认落库 / 点取消）各自已擦，这里只兜「直接退页」这条。
     */
    override fun onCleared() {
        actions.wipePendingPasskeyImport()
        super.onCleared()
    }

    private companion object {
        /** 搜索输入停顿多久后才触发列表重算（毫秒） */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
