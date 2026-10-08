package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString

/**
 * 不可变条目领域模型。
 * 遵循敏感数据铁律：敏感密码采用 [ProtectedString] 封装。
 */
data class KdbxEntry(
    val id: KdbxUuid = KdbxUuid.random(),
    val parentGroupId: KdbxUuid? = null,
    val iconId: Int = 0,
    val customIconId: KdbxUuid? = null,
    val fields: Map<String, ProtectedString> = emptyMap(),
    val customFields: List<KdbxCustomField> = emptyList(),
    val times: KdbxTimes = KdbxTimes(),
    val history: List<KdbxEntry> = emptyList(),
    val tags: List<String> = emptyList(),
    val attachments: List<KdbxAttachment> = emptyList(),
    val autoType: KdbxAutoType? = null,
    val backgroundColor: String? = null,
    val foregroundColor: String? = null,
    val overrideUrl: String? = null,
    val qualityCheck: Boolean = true,
    val previousParentGroup: KdbxUuid? = null,
    val customData: Map<String, String> = emptyMap()
) {
    val title: String
        get() = fields[KdbxConstants.Fields.TITLE]?.readString().orEmpty()

    val userName: String
        get() = fields[KdbxConstants.Fields.USER_NAME]?.readString().orEmpty()

    val password: ProtectedString?
        get() = fields[KdbxConstants.Fields.PASSWORD]

    val url: String
        get() = fields[KdbxConstants.Fields.URL]?.readString().orEmpty()

    val notes: String
        get() = fields[KdbxConstants.Fields.NOTES]?.readString().orEmpty()

    /**
     * 展示面读取（`ISSUE-P2-534`）：与 [title] / [userName] / [url] / [notes] 四个裸 getter
     * **语义完全相同**，唯一差别是「字段实例已被并发擦除」时降级为空串而**不抛**。
     *
     * 为何必须入口化：§473 真机闪退的崩溃点正是 [userName]——裸 getter 内部就是
     * `ProtectedString.readString()`（fail-fast），而展示面（UI 投影 / 检索 / 差异展示）与会话层
     * 「整树替换时就地清零」**不共享锁**，因此「投影面顺手写 `entry.userName`」这种语义等价的
     * 回退会把 fail-fast 重新带回展示面。机检 `tools/doc/check_projection_read_safety.py`
     * 据此把「登记在案的投影面文件引用这四个裸 getter」判红，并要求改走本组读口。
     *
     * **使用边界（违反即事故）**：仅限非持久化的展示 / 检索 / 差异消费面。写路径、序列化、
     * 凭据下发、`{REF:}` 取值面**必须**继续走裸 getter（fail-fast）或 `cleared` 预判 ——
     * 就地降级会把空值写进用户的库，或把空账号 / 空口令填进目标应用。
     */
    fun displayTitle(): String = displayFieldForDisplay(KdbxConstants.Fields.TITLE)

    /** 展示面读取：[userName] 的降级变体（边界见 [displayTitle]）。 */
    fun displayUserName(): String = displayFieldForDisplay(KdbxConstants.Fields.USER_NAME)

    /** 展示面读取：[url] 的降级变体（边界见 [displayTitle]）。 */
    fun displayUrl(): String = displayFieldForDisplay(KdbxConstants.Fields.URL)

    /** 展示面读取：[notes] 的降级变体（边界见 [displayTitle]）。 */
    fun displayNotes(): String = displayFieldForDisplay(KdbxConstants.Fields.NOTES)

    /** 标准字段的展示面取值：键缺失与实例已清零同义，均得空串。 */
    private fun displayFieldForDisplay(key: String): String =
        fields[key]?.readStringForDisplay().orEmpty()

    /**
     * `ISSUE-P2-534`：本条目是否含**已清零**的字段（＝正被会话层擦除，属「被替换下线」的旧实例）。
     *
     * 供**交付面 / 凭据面**在读取前预判中止使用：把已清零字段读成空串会向目标应用填入空账号 /
     * 空口令，或把空值写回库，比如实失败更糟。判据只读 `cleared` 观测位，**不物化明文**。
     *
     * 判据取「任一字段已清零」而非逐个键名比对：会话层的擦除是**子树级**的
     * （`clearSensitiveData` / `clearSupersededSensitiveData` 一次清掉整棵下线子树），
     * 故「有字段被擦」即等价于「本条正在下线」。宁可少给一个候选（下一轮请求即恢复），
     * 也不给一个空值。
     */
    fun hasClearedFields(): Boolean = fields.values.any { it.cleared }

    fun withField(key: String, value: ProtectedString): KdbxEntry {
        val newFields = fields.toMutableMap()
        newFields[key] = value
        return copy(
            fields = newFields,
            times = times.withModified()
        )
    }

    fun withField(key: String, value: String, isProtected: Boolean = false): KdbxEntry {
        return withField(key, ProtectedString(value, isProtected))
    }

    fun clearSensitiveData() {
        fields.values.forEach { it.clear() }
        customFields.forEach { it.value.clear() }
        attachments.forEach { it.clear() }
        history.forEach { it.clearSensitiveData() }
    }

    /**
     * ISSUE-P2-06 定点擦除：只清本节点**直接持有**的受保护实例
     * （fields / customFields / attachments），不递归。
     *
     * copy-on-write 会让新树与旧树共享未被修改的 [ProtectedString] / [KdbxAttachment]
     * 实例（如 [withField] 仅在 fields 中替换目标键、copy() 保留其余引用），
     * 因此**严禁**改用 [clearSensitiveData] 递归擦除下线旧树——那会连带清掉存活树
     * 仍在引用的共享实例，造成数据丢失。跨树的下线擦除请使用
     * [KdbxGroup.clearSupersededSensitiveData]，由身份集合判定真正的“下线”实例。
     *
     * history 不在本方法的无条件擦除范围内：copy() 会让新旧节点共享同一 history 列表，
     * 无条件清会破坏存活条目；下线 history 项由 [clearSensitiveIdentitiesNotIn] 按身份处理。
     */
    fun clearOwnSensitiveData() {
        fields.values.forEach { it.clear() }
        customFields.forEach { it.value.clear() }
        attachments.forEach { it.clear() }
    }

    /** 收集本节点（含 history）可达的全部敏感实例身份（配合身份集合使用，按引用相等判定） */
    internal fun collectSensitiveIdentities(into: MutableSet<Any>) {
        fields.values.forEach { into.add(it) }
        customFields.forEach { into.add(it.value) }
        attachments.forEach { into.add(it) }
        history.forEach { it.collectSensitiveIdentities(into) }
    }

    /**
     * 擦除本节点（含 history）中**未被身份集合 [live] 引用**的敏感实例。
     * [live] 必须是身份集合（如 Collections.newSetFromMap(IdentityHashMap())），
     * 其 contains 走引用相等，避免把共享同一对象的存活实例一并擦除。
     */
    internal fun clearSensitiveIdentitiesNotIn(live: Set<Any>) {
        fields.values.forEach { if (it !in live) it.clear() }
        customFields.forEach { if (it.value !in live) it.value.clear() }
        attachments.forEach { if (it !in live) it.clear() }
        history.forEach { it.clearSensitiveIdentitiesNotIn(live) }
    }

    /**
     * 存活判定（ISSUE-P3-156）：把本节点（含 history）可达的敏感实例从候选集合 [pending] 中移除。
     * [pending] 必须是身份集合，其 remove 走引用相等。
     */
    internal fun dropSensitiveIdentitiesFrom(pending: MutableSet<Any>) {
        fields.values.forEach { pending.remove(it) }
        customFields.forEach { pending.remove(it.value) }
        attachments.forEach { pending.remove(it) }
        history.forEach { it.dropSensitiveIdentitiesFrom(pending) }
    }

    /** 擦除本节点（含 history）中**仍留在候选集合 [pending] 内**（即真正下线）的敏感实例 */
    internal fun clearSensitiveIdentitiesIn(pending: Set<Any>) {
        fields.values.forEach { if (it in pending) it.clear() }
        customFields.forEach { if (it.value in pending) it.value.clear() }
        attachments.forEach { if (it in pending) it.clear() }
        history.forEach { it.clearSensitiveIdentitiesIn(pending) }
    }

    /**
     * 是否与 [other] 共享全部**承载敏感实例的容器**（字段 / 自定义字段 / 附件 / 历史列表按同一引用）：
     * 成立即表示本节点可达的敏感实例与 [other] 可达者完全同一批，替换不产生任何下线实例。
     */
    internal fun sharesSensitiveContainersWith(other: KdbxEntry): Boolean =
        fields === other.fields && customFields === other.customFields &&
            attachments === other.attachments && history === other.history

    override fun toString(): String {
        // P2-4 整改：数据类默认 toString 会展开 fields/customFields/history 等集合，
        // 任何隐式字符串化（日志、调试、异常消息）都不该物化字段内容——
        // 此处仅呈现结构摘要；字段明文一律经 ProtectedString 显式 readChars()/readString() 按需读取
        return "KdbxEntry(id=$id, parentGroupId=$parentGroupId, iconId=$iconId, " +
            "fields=${fields.size}, customFields=${customFields.size}, " +
            "attachments=${attachments.size}, history=${history.size}, tags=$tags)"
    }
}
