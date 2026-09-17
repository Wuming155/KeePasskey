package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.VaultGroup

/**
 * 分组完整路径解析（ISSUE-P3-17）。
 *
 * 库列表搜索结果行与详情页「所属分组」都要求「自根到该分组的完整路径」，
 * 两处共用本纯函数实现，避免路径拼接与环状防御出现两条各自演化的逻辑。
 * 纯函数、无 Android 依赖、无 IO，可 JVM 直测。
 */
object GroupPathPresenter {

    /** 层级分隔符（KeePass 生态统一以「/」表达分组层级，非可翻译文案） */
    const val SEPARATOR = " / "

    /**
     * 自根到 [groupId] 的完整路径（如「工作与生产力 / 研发与基础设施」）。
     *
     * @return 分组不存在或 [groupId] 为 null 时返回 null（调用方不展示该行）；
     * 父链出现环（异常库文件）时按已访问集合截断，绝不无限上溯。
     */
    fun fullPathOf(groups: List<VaultGroup>, groupId: String?): String? =
        pathOfIndexed(groups.associateBy { it.id }, groupId)

    /**
     * 批量解析：分组 id → 完整路径（搜索结果行按 `entry.groupId` 取用）。
     * 路径解析异常（父链断裂）时回退该分组自身名称，绝不返回空串掩盖归属。
     *
     * ISSUE-P3-162：索引只建一次并全程复用——原实现对每个分组各调一次 [fullPathOf]，
     * 而后者每次都要 `associateBy` 重建全表索引 ⇒ `O(G²)` 次建表 + G 个 Map 分配，
     * 且本函数在每次列表状态投影（含搜索态每轮输入）都被调用。
     */
    fun pathsOf(groups: List<VaultGroup>): Map<String, String> {
        val byId = groups.associateBy { it.id }
        return groups.associate { group ->
            group.id to (pathOfIndexed(byId, group.id) ?: group.name)
        }
    }

    private fun pathOfIndexed(byId: Map<String, VaultGroup>, groupId: String?): String? {
        if (groupId == null) return null
        val names = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        var current = byId[groupId]
        while (current != null && visited.add(current.id)) {
            names.addFirst(current.name)
            current = current.parentId?.let { byId[it] }
        }
        return if (names.isEmpty()) null else names.joinToString(SEPARATOR)
    }
}
