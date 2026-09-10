package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.childdb.ChildDatabaseEntryProjection
import com.keepasskey.app.ui.model.ChildVaultEntryGroup
import com.keepasskey.app.ui.model.ChildVaultEntryRow

/**
 * 子库条目投影 → 库列表只读分区的装配（ISSUE-P3-30）。
 *
 * 纯函数、无 Android 依赖、无 IO，可 JVM 直测。
 *
 * ## 只做「展示装配」，不做「数据合并」
 *
 * 本对象把核心层的扁平投影 `List<ChildDatabaseEntryProjection>` 归拢为
 * 「按挂载分组」的展示结构，**不触碰**根库对象树、不产生任何可写模型：
 * 归拢结果只经 [com.keepasskey.app.ui.screens.vault.VaultListUiState.childEntryGroups] 下发，
 * 与 `VaultListUiState.entries`（根库条目）并列而不混合。
 *
 * ## 分组路径口径
 *
 * 核心层 `ChildDatabaseEntryProjection.groupPath` 以 `/` 连接子库内的分组层级，
 * 根级条目为空串；本对象把层级统一渲染为 [GroupPathPresenter.SEPARATOR]（` / `，
 * 与根库搜索结果行的路径分隔符同源，避免两处各写一套），并在根级条目上**回退为挂载别名**，
 * 使用户始终能看出条目归属哪个子库（不会出现无来源的裸条目）。
 *
 * 已知口径限界：[ChildDatabaseEntryProjection.groupPath] 内的子库分组名若**自身含 `/`**，
 * 展示路径的层级会与之混淆（核心层以 `/` 为分隔符的既有约定所致）；首版如实接受，
 * 不做转义，以免与核心层的路径口径漂移。
 */
object ChildVaultEntryPresenter {

    /** 核心层分组路径的分隔符（`ChildDatabaseEntryProjection.groupPath` 的既定约定） */
    private const val CORE_GROUP_PATH_SEPARATOR = "/"

    /**
     * 按挂载归拢为展示分区（**保持投影给出的顺序**：注册表登记顺序 → 各挂载内部条目遍历顺序）。
     *
     * @return 无已打开子库时返回空表（UI 据此不渲染任何子库分区）
     */
    fun groupsOf(
        projections: List<ChildDatabaseEntryProjection>
    ): List<ChildVaultEntryGroup> = projections
        .groupBy { it.mountId }
        .map { (mountId, entries) ->
            ChildVaultEntryGroup(
                mountId = mountId,
                mountAlias = entries.first().mountAlias,
                entries = entries.map { it.toRow() }
            )
        }

    /** 单条目投影 → 展示行（只取渲染所需字段，见 [ChildVaultEntryRow] KDoc） */
    private fun ChildDatabaseEntryProjection.toRow(): ChildVaultEntryRow = ChildVaultEntryRow(
        mountId = mountId,
        mountAlias = mountAlias,
        entryUuid = entryUuid,
        title = title,
        username = username,
        url = url,
        displayPath = displayPathOf(mountAlias, groupPath)
    )

    /** 展示路径：根级条目回退为挂载别名，子分组路径以统一分隔符展开 */
    private fun displayPathOf(mountAlias: String, groupPath: String): String =
        if (groupPath.isEmpty()) {
            mountAlias
        } else {
            mountAlias + GroupPathPresenter.SEPARATOR +
                groupPath.replace(CORE_GROUP_PATH_SEPARATOR, GroupPathPresenter.SEPARATOR)
        }
}
