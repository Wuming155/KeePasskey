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

    /**
     * ISSUE-P2-06：copy-on-write 版本切换前的定点擦除。
     *
     * 以 [surviving] 为存活树，收集其中（含条目 history）全部受保护实例的对象身份，
     * 再遍历本树，**仅擦除未被存活树以同一对象引用（引用相等）的实例**。
     * 这样既能清掉真正下线的旧节点密文，又不会误伤新树仍共享引用的
     * [ProtectedString] / [KdbxAttachment]（例如 [KdbxEntry.withField] 只替换目标字段、
     * KdbxEntry.copy(parentGroupId = ...) 的移动条目会共享全部字段实例）。
     *
     * **严禁**改用 [clearSensitiveData] 递归擦除：新树会共享未修改子树的引用，
     * 递归擦除会造成存活数据丢失。
     *
     * 调用约定：在把旧树替换为 [surviving] 之前调用 oldRoot.clearSupersededSensitiveData(newRoot)。
     * 新旧根为同一实例（无替换）时直接返回。
     */
    fun clearSupersededSensitiveData(surviving: KdbxGroup) {
        if (this === surviving) return
        // IdentityHashMap 支撑的集合：contains 走引用相等而非 equals/hashCode
        val live = newSensitiveIdentitySet()
        surviving.collectSensitiveIdentities(live)
        clearSensitiveIdentitiesNotIn(live)
    }

    /**
     * ISSUE-P3-156 定点擦除（增量）：**本实例为替换后的新树根**，擦除 [replaced]
     * （本次被替换下线的旧节点）中在本树不可达的敏感实例；[replacement] 为同位置上线的
     * 新节点（新增 / 无替换场景为 null）。
     *
     * 与 [clearSupersededSensitiveData] 的等价性依据是**路径复制契约**（树变换只重建从根到目标的
     * 分组链）：新树除 [replaced] 所在位置外与旧树按同一对象引用共享全部子树，故「旧树中下线、
     * 新树中存活」的敏感实例**只可能来自 [replaced]**。候选集合规模 = [replaced] 规模，
     * **不随全库规模增长**；[clearSupersededSensitiveData] 需为整棵新树建身份集合（O(全库)），
     * 只保留给无法定位替换位置的调用方（任意整库变换 / 历史修剪）。
     *
     * 判定逐级收窄，任一阶段候选清空即返回：
     * 1. [replacement] 与 [replaced] 共享全部承载容器（`copy` 仅改元数据）⇒ 旧实例必然存活，零成本返回；
     * 2. [replaced] 可达实例中被 [replacement] 覆盖者仍在树上 ⇒ 移出候选；
     * 3. 余下候选在本树任意位置可达者仍在树上 ⇒ 移出候选（**只做引用比较、不插入集合**）；
     * 4. 仍留存的候选即真正下线的实例，逐个清零。
     */
    fun eraseSupersededSensitiveData(replaced: KdbxEntry, replacement: KdbxEntry?) = eraseSuperseded(
        collect = replaced::collectSensitiveIdentities,
        coveredByReplacement = replacement != null && replaced.sharesSensitiveContainersWith(replacement),
        dropFromReplacement = { replacement?.dropSensitiveIdentitiesFrom(it) },
        clear = replaced::clearSensitiveIdentitiesIn
    )

    /** 分组版：语义与条目版逐条一致，见 [eraseSupersededSensitiveData] 的条目重载。 */
    fun eraseSupersededSensitiveData(replaced: KdbxGroup, replacement: KdbxGroup?) = eraseSuperseded(
        collect = replaced::collectSensitiveIdentities,
        coveredByReplacement = replacement != null && replaced.sharesSensitiveContainersWith(replacement),
        dropFromReplacement = { replacement?.dropSensitiveIdentitiesFrom(it) },
        clear = replaced::clearSensitiveIdentitiesIn
    )

    /**
     * 增量擦除的唯一实现（条目 / 分组两个重载共用，避免安全逻辑出现两份漂移实现）。
     * 候选集合只承载被替换节点可达的实例；存活判定对**本树**（存活树）做引用扫描。
     */
    private fun eraseSuperseded(
        collect: (MutableSet<Any>) -> Unit,
        coveredByReplacement: Boolean,
        dropFromReplacement: (MutableSet<Any>) -> Unit,
        clear: (Set<Any>) -> Unit
    ) {
        if (coveredByReplacement) return
        val pending = newSensitiveIdentitySet()
        collect(pending)
        if (pending.isEmpty()) return
        dropFromReplacement(pending)
        if (pending.isEmpty()) return
        dropSensitiveIdentitiesFrom(pending)
        if (pending.isEmpty()) return
        clear(pending)
    }

    /** 收集本子树（含条目 history）可达的全部敏感实例身份 */
    internal fun collectSensitiveIdentities(into: MutableSet<Any>) {
        entries.forEach { it.collectSensitiveIdentities(into) }
        subgroups.forEach { it.collectSensitiveIdentities(into) }
    }

    /**
     * 存活判定：把本子树（含条目 history）可达的敏感实例从候选集合 [pending] 中移除。
     * [pending] 必须是身份集合（如 [newSensitiveIdentitySet]），其 remove 走引用相等。
     */
    internal fun dropSensitiveIdentitiesFrom(pending: MutableSet<Any>) {
        entries.forEach { it.dropSensitiveIdentitiesFrom(pending) }
        subgroups.forEach { it.dropSensitiveIdentitiesFrom(pending) }
    }

    /** 擦除本子树中**仍留在候选集合 [pending] 内**（即真正下线）的敏感实例 */
    internal fun clearSensitiveIdentitiesIn(pending: Set<Any>) {
        entries.forEach { it.clearSensitiveIdentitiesIn(pending) }
        subgroups.forEach { it.clearSensitiveIdentitiesIn(pending) }
    }

    /** 擦除本子树中未被身份集合 [live] 引用的敏感实例 */
    internal fun clearSensitiveIdentitiesNotIn(live: Set<Any>) {
        entries.forEach { it.clearSensitiveIdentitiesNotIn(live) }
        subgroups.forEach { it.clearSensitiveIdentitiesNotIn(live) }
    }

    /**
     * 是否与 [other] 共享全部**承载敏感实例的容器**（子条目列表 / 子分组列表按同一引用）：
     * 成立即表示本节点可达的敏感实例与 [other] 可达者完全同一批，替换不产生任何下线实例。
     */
    private fun sharesSensitiveContainersWith(other: KdbxGroup): Boolean =
        entries === other.entries && subgroups === other.subgroups
}

/**
 * 敏感实例的**身份集合**（引用相等语义）：`ProtectedString` / `KdbxAttachment` 的 `equals`
 * 是内容等值（HMAC 标签）语义，擦除判定必须按对象身份，故一律经本工厂构造集合。
 */
private fun newSensitiveIdentitySet(): MutableSet<Any> =
    java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
