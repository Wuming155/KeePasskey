package com.keepasskey.app.ui.model

/**
 * 子库条目的**只读展示行**（ISSUE-P3-30）。
 *
 * 与 [UiVaultEntry] 刻意**分属两个类型**，是本次接线的结构性只读保证：
 * 根库条目的编辑/删除/批量/复制入口全部以 [UiVaultEntry] 为形参或以其 `id` 为入参，
 * 而子库行**不是** [UiVaultEntry]、也永远不进 `VaultListUiState.entries`，
 * 因此不存在「子库条目被误路由进写路径」的代码路径——这比「运行时再拒绝」更强：
 * 没有入口，就没有可被绕过的判定。
 *
 * 为什么不以 UUID 集合做运行时拒绝：子库完全可能是根库的副本（KDBX 复制即 UUID 全同），
 * 届时「子库条目 UUID」与「根库条目 UUID」是同一批字符串，按 UUID 拒绝会**误伤根库自身的
 * 合法编辑**。故只读边界只能靠数据流与组件层切割，不能靠 ID 匹配。
 *
 * 字段只保留本行实际渲染所需的最小集：核心层投影
 * `ChildDatabaseEntryProjection` 里的 notes / tags / iconId / hasPassword 暂无消费方，
 * 不下发到 UI 状态层（少一份驻留即少一份暴露面）。
 *
 * @param entryUuid 子库内条目 UUID（hex）。**不保证**在根库内唯一（见上文副本场景），
 *   故列表稳定 key 必须用 [rowKey]（挂载 ID + 条目 UUID）而非裸 UUID。
 * @param displayPath 「挂载别名 + 子库内分组路径」的展示文案，由
 *   `ChildVaultEntryPresenter` 统一装配（UI 不做路径拼接）。
 */
data class ChildVaultEntryRow(
    val mountId: String,
    val mountAlias: String,
    val entryUuid: String,
    val title: String,
    val username: String,
    val url: String,
    val displayPath: String
) {

    /**
     * 列表项稳定标识：挂载 ID 与条目 UUID 的组合。
     *
     * 单用 [entryUuid] 会在「同一 UUID 同时存在于两个子库」时产生重复 key（Compose 抛异常），
     * 单用 [title] 则会在重名条目上错位复用；故以挂载身份限定命名空间。
     */
    val rowKey: String
        get() = "$mountId:$entryUuid"
}

/**
 * 单个已打开子库的只读条目分组（列表分区渲染单位）。
 *
 * @param entries 与 [mountAlias] 同源，保证分区标题与行内容取自同一次投影快照
 */
data class ChildVaultEntryGroup(
    val mountId: String,
    val mountAlias: String,
    val entries: List<ChildVaultEntryRow>
)
