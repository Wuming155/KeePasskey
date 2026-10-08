package com.keepasskey.app.ui.screens.vault

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.keepasskey.app.ui.components.vaultLoadingSkeletonItems
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.theme.LocalReduceAnimations

/**
 * 密码库列表页的**内容装配层**（§280 规模门禁同批自 `VaultListScreen.kt` 逐字迁出，
 * 结构性拆分：`VaultListScreen` 只保留页面壳（状态订阅 / 返回键 / 对话框宿主接线），
 * 本文件承载 [VaultListContent] 的 Scaffold 与 LazyColumn 分区装配，渲染语义零变化）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListContent(
    uiState: VaultListUiState,
    // ISSUE-P2-356：搜索框即时回显（未防抖）；缺省取过滤快照供预览 / 截图测试
    searchQuery: String = uiState.searchQuery,
    onSearchQueryChange: (String) -> Unit,
    onSortOptionSelect: (VaultSortOption) -> Unit,
    // ISSUE-P3-297 处置③：标签 / 收藏筛选档上行（预览与既有调用方可走缺省）
    onFavoriteFilterChange: (Boolean) -> Unit = {},
    onTagFilterChange: (String?) -> Unit = {},
    // ISSUE-P3-439 / ISSUE-P2-544：高级搜索选项（由溢出菜单项打开对话框；上行走 ViewModel 持久化通道）
    onSearchFieldToggle: (com.keepasskey.app.ui.screens.settings.SearchField) -> Unit = {},
    onSearchExcludeExpiredChange: (Boolean) -> Unit = {},
    onSearchCaseSensitiveChange: (Boolean) -> Unit = {},
    onGroupClick: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToBreadcrumb: (String?) -> Unit,
    onEntryClick: (String) -> Unit,
    onEntryLongClick: (String) -> Unit,
    onCopyPassword: (UiVaultEntry) -> Unit,
    onCopyUsername: (UiVaultEntry) -> Unit,
    /** ISSUE-P3-184：行内验证码徽标点击复制（HOTP 条目不渲染该入口） */
    onCopyTotp: (UiVaultEntry) -> Unit = {},
    onAddEntryClick: () -> Unit,
    // ISSUE-P3-51：从模板新建（入参为选中模板 id）
    onCreateFromTemplate: (String) -> Unit = {},
    /** ISSUE-P3-352 AC①：搜索空态双出口（新建导航前发布搜索词预填；null = 只读/回收站不呈现新建） */
    onCreateEntryFromSearch: (() -> Unit)? = null,
    onClearSearch: () -> Unit = {},
    onCreateGroup: (name: String, icon: String) -> Unit,
    onRenameGroup: (VaultGroup, String) -> Unit,
    onChangeGroupIcon: (VaultGroup, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onRestoreEntry: (String) -> Unit,
    onPurgeEntry: (String) -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onTriggerSync: () -> Unit,
    onNavigateToConflictResolver: () -> Unit = {},
    onLockClick: () -> Unit = {},
    onSelectAllBatch: () -> Unit,
    onClearBatch: () -> Unit,
    onBatchDelete: () -> Unit,
    onBatchMove: (String?) -> Unit,
    onSelectEntriesBatch: () -> Unit = {}, // ISSUE-P3-360 AC④a：溢出菜单「选择」项（进入批量模式，不预选任何条目）
    // ISSUE-P3-17：非空才呈现「彻底退出应用」入口（偏好开启且宿主可终止）
    onKillApp: (() -> Unit)? = null,
    /** 非空才在溢出菜单呈现「扫码」入口（otpauth → 创建验证码条目）；只读会话传 null 隐藏 */
    onScanClick: (() -> Unit)? = null,
    // ISSUE-P3-17：自动聚焦搜索栏意图已被消费的回执
    onAutoActivateSearchConsumed: () -> Unit = {},
    /**
     * ISSUE-P2-89 / ISSUE-P3-158：TOTP 秒级刻度（窄状态）。
     *
     * 本页只**持有**该状态对象、不读其值——读取动作下沉到列表行内的徽标，
     * 故秒级 tick 不会令本页（含整张列表）失效。缺省值供预览 / 截图测试使用。
     */
    totpNowSeconds: State<Long> = remember { mutableLongStateOf(TOTP_PREVIEW_NOW_SECONDS) },
    /** ISSUE-P2-89：TOTP 本周期实时验证码（窄状态，`entryId → 验证码`） */
    totpLiveCodes: State<Map<String, String>> = remember { mutableStateOf(emptyMap()) },
    modifier: Modifier = Modifier
) {
    // ISSUE-P3-29：10 个对话框的可见性 / 目标对象由独立状态持有者承接（见 VaultListDialogHost.kt）
    val dialogs = rememberVaultListDialogController()
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    // ISSUE-P3-17：列表密度规格（偏好 → 行高 / 内边距 / 字号的唯一映射点）
    val densitySpec = remember(uiState.listDensity) {
        ListDensityPresenter.specOf(uiState.listDensity)
    }

    // ISSUE-P3-30：搜索态标记（仅用于「子库条目不参与搜索」提示行；子库分区可见性判定在状态层）
    val isSearching = searchQuery.isNotBlank()

    // ISSUE-P3-261 AC⑧：列表内容层动效与主题 MotionScheme 同族——条目增删 / 重排不再瞬移。
    // ISSUE-P3-323 降档：fade fast→default（3800→1600，增删不再近瞬消）、placement
    // fast→default（800/0.6→380/0.8，重排不再欠阻尼过冲）；仍在**本层**取值后传入 `animateItem`
    // （`items` 的 content lambda 是 `@Composable`，但把 `MaterialTheme` 读取下沉到每个条目
    // 会让 N 个 item 各注册一次组合局部读取）。
    // ISSUE-P3-444 AC②：动效降级态传 null spec —— `animateItem` 的 spec 参数可空，传 null
    // 即不播放增删 / 重排动画（调用形态保持不变，仍由源码守卫钉住「列表项必须启用 animateItem」）。
    val reduceAnimations = LocalReduceAnimations.current
    val itemFadeSpec: FiniteAnimationSpec<Float>? =
        if (reduceAnimations) null else MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val itemPlacementSpec: FiniteAnimationSpec<IntOffset>? =
        if (reduceAnimations) null else MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (uiState.isBatchMode) {
                VaultListBatchModeTopBar(
                    selectedCount = uiState.selectedEntryIds.size,
                    onClearBatch = onClearBatch,
                    onSelectAllBatch = onSelectAllBatch,
                    onBatchMoveClick = { dialogs.showBatchMoveDialog = true },
                    onBatchDelete = { dialogs.showBatchDeleteConfirm = true } // ISSUE-P2-357 AC①：先确认再删
                )
            } else {
                VaultListSearchTopBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    isInsideRecycleBin = uiState.isInsideRecycleBin,
                    sortOption = uiState.sortOption,
                    onSortClick = { dialogs.showSortDialog = true },
                    onLockClick = onLockClick,
                    onEmptyRecycleBinClick = { dialogs.showEmptyRecycleBinDialog = true },
                    // ISSUE-P3-17：进入列表页自动聚焦搜索栏（一次性意图，消费后回执清除）
                    autoActivateSearch = uiState.autoActivateSearch,
                    onAutoActivateSearchConsumed = onAutoActivateSearchConsumed,
                    onKillApp = onKillApp,
                    onScanClick = onScanClick,
                    onSelectEntriesClick = onSelectEntriesBatch,
                    // ISSUE-P2-544：高级搜索入口（溢出菜单项 → 选项对话框）
                    onAdvancedSearchClick = { dialogs.showSearchAdvancedDialog = true }
                )
            }
        },
        floatingActionButton = {
            // M3 Auto-collapsing FAB：滚动中缩回图标态；hideFabOnScroll 开启时整只隐藏
            val fabExpanded by remember {
                derivedStateOf { !listState.isScrollInProgress }
            }
            VaultListFab(
                visible = !uiState.isBatchMode && !uiState.isInsideRecycleBin &&
                    (!uiState.hideFabOnScroll || !listState.isScrollInProgress),
                isReadOnly = uiState.isReadOnly,
                expanded = fabExpanded,
                onClick = { dialogs.showCreateTypeDialog = true }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = onTriggerSync,
            state = pullRefreshState,
            indicator = {
                LastSyncPullIndicator(
                    distanceFraction = pullRefreshState.distanceFraction,
                    isSyncing = uiState.isSyncing,
                    lastSyncTimeText = uiState.lastSyncTimeText
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 底部预留 FAB + 系统导航栏高度，避免最后一条与悬浮按钮/手势条重叠
                // 水平 16dp：与 FAB（Scaffold 默认 16dp）、顶栏标题内缩（TopAppBar 16dp）对齐同一网格
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.isLoading) vaultLoadingSkeletonItems() // ISSUE-P3-360 AC⑤：库列表首载骨架（真实投影落地前；空态判定已排除加载窗）

                if (uiState.hasPendingConflict) {
                    item { PendingConflictBanner(onClick = onNavigateToConflictResolver) }
                }

                // ISSUE-P3-447 AC③：保存被外部修改中止 ⇒ 明确提示列表可能含未落库改动
                if (uiState.saveBlockedByExternalModification) {
                    item { ExternalModificationPendingBanner() }
                }

                // 1. 面包屑路径导航
                if (uiState.breadcrumbs.isNotEmpty() && searchQuery.isBlank()) {
                    item {
                        VaultBreadcrumbBar(
                            breadcrumbs = uiState.breadcrumbs,
                            currentGroupId = uiState.currentGroupId,
                            onNavigateUp = onNavigateUp,
                            onNavigateToBreadcrumb = onNavigateToBreadcrumb
                        )
                    }
                }

                // 回收站模式警示（含已删条目数）
                if (uiState.isInsideRecycleBin) {
                    item { RecycleBinBanner(deletedCount = uiState.entries.size) }
                }

                // 3. 当前非默认排序状态轻量提示
                if (uiState.sortOption != VaultSortOption.DEFAULT && !uiState.isInsideRecycleBin) {
                    item {
                        SortStatusHint(
                            sortOption = uiState.sortOption,
                            onResetSort = { onSortOptionSelect(VaultSortOption.DEFAULT) }
                        )
                    }
                }

                // 3.0 ISSUE-P3-297 处置③：标签 / 收藏筛选芯片行（段落组件见 VaultListScreenSections.kt）
                vaultFilterChipRowSection(
                    uiState = uiState,
                    onFavoriteFilterChange = onFavoriteFilterChange,
                    onTagFilterChange = onTagFilterChange
                )

                // 3.1 ISSUE-P3-30：搜索态下如实说明子库条目不参与搜索（有已挂载子库时才提示）
                if (isSearching && uiState.mountedChildDatabaseCount > 0) {
                    item(key = CHILD_DB_SEARCH_HINT_KEY) { ChildDatabaseSearchExclusionHint() }
                }

                // 4. 文件夹列表（ISSUE-P3-22：分组图标与条目侧共用同一投影缓存）
                items(uiState.currentGroups, key = { "group_${it.id}" }) { group ->
                    KeePassGroupRow(
                        group = group,
                        highlightQuery = uiState.searchQuery, // ISSUE-P3-360 AC④c：命中高亮取**已生效**的过滤词（防抖后），非输入回显
                        icon = uiState.groupIcons[group.id],
                        densitySpec = densitySpec,
                        onClick = { onGroupClick(group.id) },
                        onRename = { dialogs.groupToRename = group },
                        onChangeIcon = { dialogs.groupToChangeIcon = group },
                        onDelete = { dialogs.groupToDelete = group },
                        // ISSUE-P3-261 AC⑧：增删 / 重排不再瞬移
                        modifier = Modifier.animateItem(
                            fadeInSpec = itemFadeSpec,
                            placementSpec = itemPlacementSpec,
                            fadeOutSpec = itemFadeSpec
                        )
                    )
                }

                // 5. 现代化多形态条目列表 (支持长按批量选择、信用卡拟真、安全便签)
                items(uiState.entries, key = { it.id }) { entry ->
                    val isSelected = entry.id in uiState.selectedEntryIds
                    UnifiedVaultEntryRow(
                        entry = entry,
                        highlightQuery = uiState.searchQuery, // ISSUE-P3-360 AC④c：命中高亮取**已生效**的过滤词（防抖后），非输入回显
                        isRecycled = uiState.isInsideRecycleBin,
                        isBatchMode = uiState.isBatchMode,
                        isSelected = isSelected,
                        showUsername = uiState.showUsernameInList,
                        showOtp = uiState.showOtpInList,
                        showPasskeyBadge = uiState.showPasskeyBadge,
                        showUrl = uiState.showUrlInList,
                        densitySpec = densitySpec,
                        // ISSUE-P3-17：搜索结果行的完整分组路径（非搜索态 / 开关关闭时为空表）
                        groupPath = uiState.entryGroupPaths[entry.id],
                        onClick = { onEntryClick(entry.id) },
                        onLongClick = { onEntryLongClick(entry.id) },
                        onCopyPassword = { onCopyPassword(entry) },
                        onCopyUsername = { onCopyUsername(entry) },
                        onCopyTotpCode = { onCopyTotp(entry) },
                        onRestore = { onRestoreEntry(entry.id) },
                        onPurge = { dialogs.purgeEntryToDelete = entry }, // ISSUE-P2-357 AC①：先确认再永久删除
                        // ISSUE-P3-02：状态层装配的图标投影与引用展开文案（UI 只做纯绘制）
                        decorations = uiState.decorations,
                        // ISSUE-P2-89：TOTP 实时值经窄状态下发，仅由徽标读取（本页不读其值）
                        totpNowSeconds = totpNowSeconds,
                        totpLiveCodes = totpLiveCodes,
                        // ISSUE-P3-261 AC⑧：搜索 / 过滤 / 删除后的条目增删与重排走同族动效
                        modifier = Modifier.animateItem(
                            fadeInSpec = itemFadeSpec,
                            placementSpec = itemPlacementSpec,
                            fadeOutSpec = itemFadeSpec
                        )
                    )
                }

                // 6. ISSUE-P3-30：已解锁子库的只读分区（段落组件见 VaultListScreenSections.kt）
                childDatabaseSection(uiState, densitySpec, itemFadeSpec, itemPlacementSpec)

                // 7. 空状态（子库分区有内容时不算空；首载骨架窗不呈现空态闪现）
                if (!uiState.isLoading && uiState.currentGroups.isEmpty() && uiState.entries.isEmpty() && !uiState.childEntrySectionVisible) {
                    item { VaultEmptyState(searchQuery.isNotBlank(), onCreateEntryFromSearch, onClearSearch) }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    // ISSUE-P3-29：10 个对话框统一由 VaultListDialogHost 渲染（编排见该文件）
    VaultListDialogHost(
        controller = dialogs,
        uiState = uiState,
        onSortOptionSelect = onSortOptionSelect,
        onAddEntryClick = onAddEntryClick,
        onCreateFromTemplate = onCreateFromTemplate,
        onCreateGroup = onCreateGroup,
        onRenameGroup = onRenameGroup,
        onChangeGroupIcon = onChangeGroupIcon,
        onDeleteGroup = onDeleteGroup,
        onEmptyRecycleBin = onEmptyRecycleBin,
        onBatchMove = onBatchMove, onBatchDelete = onBatchDelete, onPurgeEntry = onPurgeEntry,
        // ISSUE-P2-544：高级搜索选项对话框的三条上行（改动即时生效，无草稿态）
        onSearchFieldToggle = onSearchFieldToggle,
        onSearchExcludeExpiredChange = onSearchExcludeExpiredChange,
        onSearchCaseSensitiveChange = onSearchCaseSensitiveChange
    )
}

/** ISSUE-P3-30：搜索态子库排除提示行的稳定 key（不同排序 / 筛选下不重建） */
private const val CHILD_DB_SEARCH_HINT_KEY = "child_db_search_exclusion"
