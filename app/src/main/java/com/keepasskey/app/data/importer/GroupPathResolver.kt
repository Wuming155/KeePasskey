package com.keepasskey.app.data.importer

import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult

/**
 * 分组路径解析结果。
 *
 * @param groupId 命中的叶子分组 id；空路径（根分组）时返回**根分组的真实 id**（ISSUE-P3-63：
 * 此前返回 null，与会话树「条目落树后携带真实父组 id」的规范化语义不一致，导致重复导入
 * 根级条目时去重键错配）；整链创建失败后的安全回退时亦尽可能给出最深已解析分组 id。
 * @param creationFailed 解析过程中至少有一次分组创建失败（调用方据此记警告，不静默降级）。
 */
internal data class GroupResolution(val groupId: String?, val creationFailed: Boolean)

/**
 * 分组路径「查找或创建」器（ISSUE-P3-19 交付物 1.1 的分组语义）。
 *
 * 语义：
 * - 自顶向下逐段匹配既有分组（**父分组 id 相同 + 名称完全相同**，区分大小写：避免把
 *   `Work` 与 `work` 意外合并）；未命中则新建并落库；
 * - **回收站分组永不参与匹配、也永不在其下创建子组**（对齐库内 `RecycleBinCoordinator`
 *   的回收站语义：外部导入不得把新条目塞进回收站子树）；
 * - 任一层创建失败即**保留已解析到的最深分组**并上报 `creationFailed`（条目落库到浅层分组，
 *   绝不丢数据）；
 * - 路径深度按 [ImportLimits.MAX_GROUP_PATH_DEPTH] 截断（防御性上限，超出部分丢弃）。
 *
 * 纯逻辑 + 注入式创建回调，故可在 JVM 单测中以假回调直接驱动。
 */
internal class GroupPathResolver(
    existingGroups: List<VaultGroup>,
    private val createGroup: suspend (VaultGroup) -> KdbxResult<Unit>
) {

    private val known = existingGroups.toMutableList()

    /**
     * 解析完整路径（ISSUE-P3-63 语义修正）。
     *
     * 自根分组起逐段匹配：根级路径段以**根分组真实 id** 为父锚点（分组投影中顶级分组的
     * `parentId` 即根组 id，此前以 null 为起点导致根级既有分组永远匹配不上、重复导入
     * 会建出同名重复分组）；空路径返回根分组的真实 id（未知根组时回退 null）。
     */
    suspend fun resolve(path: List<String>): GroupResolution {
        val rootId = known.firstOrNull { it.parentId == null }?.id
        var parentId: String? = rootId
        var failed = false
        for (segment in path.take(ImportLimits.MAX_GROUP_PATH_DEPTH)) {
            val name = segment.trim()
            if (name.isEmpty()) continue
            val match = findChild(parentId, name) ?: createChild(parentId, name)
            if (match == null) {
                failed = true
                break
            }
            parentId = match.id
        }
        return GroupResolution(groupId = parentId, creationFailed = failed)
    }

    private fun findChild(parentId: String?, name: String): VaultGroup? = known.firstOrNull {
        it.parentId == parentId && it.name == name && !it.isRecycleBin
    }

    private suspend fun createChild(parentId: String?, name: String): VaultGroup? {
        val group = VaultGroup(
            id = KdbxUuid.random().toHexString(),
            name = name,
            parentId = parentId,
            iconName = FOLDER_ICON_NAME
        )
        if (createGroup(group).isFailure) return null
        known += group
        return group
    }

    private companion object {
        /** 与仓库同义的文件夹图标名（`VaultGroup.iconName` 的既有取值）。 */
        const val FOLDER_ICON_NAME = "folder"
    }
}
