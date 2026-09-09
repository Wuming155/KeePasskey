package com.keepasskey.core.model

/**
 * 分组（文件夹）领域模型，树状结构。
 */
data class KdbxGroup(
    val id: KdbxUuid = KdbxUuid.random(),
    val parentGroupId: KdbxUuid? = null,
    val name: String,
    val notes: String = "",
    val iconId: Int = 48,
    val customIconId: KdbxUuid? = null,
    val times: KdbxTimes = KdbxTimes(),
    val isExpanded: Boolean = true,
    val defaultAutoTypeSequence: String = "",
    val enableAutoType: Boolean? = null,
    val enableSearching: Boolean? = null,
    val lastTopVisibleEntry: KdbxUuid? = null,
    val previousParentGroup: KdbxUuid? = null,
    /** 分组标签（官方 KeePass 2.51+ 支持 Group 级 Tags，XML 中以分号分隔存储） */
    val tags: List<String> = emptyList(),
    /** 分组自定义数据（官方 Group 级 <CustomData>） */
    val customData: Map<String, String> = emptyMap(),
    val entries: List<KdbxEntry> = emptyList(),
    val subgroups: List<KdbxGroup> = emptyList()
) {
    /**
     * 扁平化获取本组及所有子组下的所有条目
     */
    fun allEntries(): List<KdbxEntry> {
        val result = mutableListOf<KdbxEntry>()
        result.addAll(entries)
        for (sub in subgroups) {
            result.addAll(sub.allEntries())
        }
        return result
    }

    /**
     * 扁平化获取本组及所有递归子组
     */
    fun allGroups(): List<KdbxGroup> {
        val result = mutableListOf<KdbxGroup>()
        result.add(this)
        for (sub in subgroups) {
            result.addAll(sub.allGroups())
        }
        return result
    }

    fun findEntry(entryId: KdbxUuid): KdbxEntry? {
        val found = entries.find { it.id == entryId }
        if (found != null) return found
        for (sub in subgroups) {
            val childFound = sub.findEntry(entryId)
            if (childFound != null) return childFound
        }
        return null
    }

    fun findGroup(groupId: KdbxUuid): KdbxGroup? {
        if (id == groupId) return this
        for (sub in subgroups) {
            val childFound = sub.findGroup(groupId)
            if (childFound != null) return childFound
        }
        return null
    }

    /**
     * 本子树（含自身）是否包含指定 UUID 的分组。
     *
     * 对齐官方 KeePass `PwGroup.IsContainedIn` 的祖先/后代判定语义：
     * 回收站分流据此判断「某组是否位于回收站之内」或「某组是否包含回收站」，
     * 从而在移动/删除时避免把回收站移入自身造成的自嵌套与数据丢失。
     */
    fun subtreeContainsGroup(groupId: KdbxUuid): Boolean {
        if (id == groupId) return true
        return subgroups.any { it.subtreeContainsGroup(groupId) }
    }

    fun clearSensitiveData() {
        entries.forEach { it.clearSensitiveData() }
        subgroups.forEach { it.clearSensitiveData() }
    }
}
