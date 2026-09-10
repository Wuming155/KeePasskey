package com.keepasskey.app.ui.screens.vault

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.ChildVaultEntryGroup
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ListDensity

/**
 * 排序方向：升序 / 降序
 */
enum class SortOrder(@StringRes val labelRes: Int) {
    ASCENDING(R.string.sort_order_ascending),
    DESCENDING(R.string.sort_order_descending)
}

/**
 * 密码库排序字段选项
 */
enum class VaultSortOption(@StringRes val labelRes: Int) {
    DEFAULT(R.string.sort_option_default),
    NAME_ASC(R.string.sort_option_name_asc),
    NAME_DESC(R.string.sort_option_name_desc),
    MODIFIED_DESC(R.string.sort_option_modified_desc),
    MODIFIED_ASC(R.string.sort_option_modified_asc),
    CREATED_DESC(R.string.sort_option_created_desc),
    CREATED_ASC(R.string.sort_option_created_asc)
}

/**
 * 云端同步状态指示
 */
enum class VaultSyncStatus(@StringRes val labelRes: Int) {
    SYNCED(R.string.sync_status_synced),
    SYNCING(R.string.sync_status_syncing),
    OFFLINE(R.string.sync_status_offline),
    CONFLICT(R.string.sync_status_conflict)
}

/**
 * 主密码库列表页面的不可变 UI 状态
 */
data class VaultListUiState(
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val sortOption: VaultSortOption = VaultSortOption.DEFAULT,
    val currentGroupId: String? = null,
    val isInsideRecycleBin: Boolean = false,
    val breadcrumbs: List<VaultGroup> = emptyList(),
    val currentGroups: List<VaultGroup> = emptyList(),
    val allGroups: List<VaultGroup> = emptyList(),
    val entries: List<UiVaultEntry> = emptyList(),
    val totalEntriesCount: Int = 0,
    val databaseName: String = "",
    val syncStatus: VaultSyncStatus = VaultSyncStatus.SYNCED,
    val isSyncing: Boolean = false,
    // 上次同步完成时间的展示文案（如下拉指示区显示「今天 10:25」）；空串表示本会话尚未同步
    val lastSyncTimeText: String = "",
    val isLocked: Boolean = false,
    val isBatchMode: Boolean = false,
    val selectedEntryIds: Set<String> = emptySet(),
    val userMessage: UiMessage? = null,
    // H2 整改：存在待解决的同步冲突会话时为 true，驱动「去解决冲突」入口
    val hasPendingConflict: Boolean = false,
    // H4-只读整改：当前会话以只读模式打开时为 true，禁用新增/编辑/删除入口
    val isReadOnly: Boolean = false,
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true,
    val showUrlInList: Boolean = true,
    val hideFabOnScroll: Boolean = false,
    // ISSUE-P3-17：列表行密度（驱动行高 / 内边距 / 字号，映射见 ListDensityPresenter）
    val listDensity: ListDensity = ListDensity.NORMAL,
    // ISSUE-P3-17：搜索结果行是否展示完整分组路径（对应设置项 showGroupInSearchResult）
    val showGroupInSearchResult: Boolean = true,
    /**
     * ISSUE-P3-17：条目 id → 所属分组完整路径。
     * 仅「搜索中且 showGroupInSearchResult 开启」时装配，其余情况为空表（行组件不做路径计算）。
     */
    val entryGroupPaths: Map<String, String> = emptyMap(),
    /**
     * ISSUE-P3-17：进入库列表后自动聚焦搜索栏并弹出输入法的**一次性意图**；
     * Screen 消费后立即经 consumeAutoActivateSearch 置回 false，避免重组重复弹输入法。
     */
    val autoActivateSearch: Boolean = false,
    /**
     * ISSUE-P3-22：分组 id → 分组图标投影（自定义位图 / 缺图占位 / 标准图标）。
     * 与条目图标共用同一 [com.keepasskey.app.ui.model.EntryIconPresenter]（同一解码缓存）。
     */
    val groupIcons: Map<String, BitmapEntryIcon> = emptyMap(),
    /**
     * ISSUE-P3-30：已解锁子库的只读条目分区（按挂载分组）。
     *
     * **与 [entries] 严格并列，绝不混入**：[entries] 恒为根库条目（`UiVaultEntry`），
     * 本字段恒为子库只读行（`ChildVaultEntryRow`）——两者类型不同，故
     * 「子库条目被根库写路径（编辑 / 删除 / 批量 / 回收站）消费」在类型层面即不可能发生。
     * 锁定根库时核心层会终止全部子库会话，本字段随之回到空表（UI 无需额外清理逻辑）。
     *
     * 首版**仅在浏览库顶层（`currentGroupId == null`）且未搜索时**渲染；搜索与自动填充
     * 均不参与（理由与裁决见 `VaultListViewModel` 的字段 KDoc 与 [mountedChildDatabaseCount]）。
     */
    val childEntryGroups: List<ChildVaultEntryGroup> = emptyList(),
    /**
     * ISSUE-P3-30：已挂载子库数量（含未解锁）。
     *
     * 与 [childEntryGroups] 语义不同：后者只含「已解锁并已投影」的子库。
     * 本字段用于在搜索态下如实提示「子库条目不参与搜索与自动填充」——
     * 用户挂载了子库却搜不到其条目时，界面须说明原因而不是静默。
     */
    val mountedChildDatabaseCount: Int = 0,
    /**
     * ISSUE-P3-30：子库只读分区是否可见。
     *
     * 判定在**状态层**完成（与 ISSUE-P3-17 / P3-22 的「状态层装配、UI 只绘制」同一约定）：
     * 仅在「浏览库顶层（[currentGroupId] == null）且未搜索且确有已解锁子库」时为 true。
     * 搜索态与子分组态一律 false —— 子库条目不参与搜索（裁决见对应 ViewModel KDoc），
     * 而站在根库某个子分组里展示无关子库的条目只会造成归属歧义。
     */
    val childEntrySectionVisible: Boolean = false,
    // ISSUE-P3-02：条目展示装饰（自定义图标投影 + Notes/URL 字段引用展开文案）。
    // 图标解码与引用解析均在状态层完成，Composable 只做纯绘制（禁止在 UI 内做 IO/解码）。
    val decorations: EntryDecorations = EntryDecorations.EMPTY
)
