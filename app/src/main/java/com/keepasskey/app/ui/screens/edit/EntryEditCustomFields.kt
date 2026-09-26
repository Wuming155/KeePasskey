package com.keepasskey.app.ui.screens.edit

/**
 * 编辑页「自定义字段」四写入口的持有者（`ISSUE-P3-337` 第 4 片（下）自 [EntryEditViewModel]
 * 纯结构性搬移，逐条行为不变；搬移动机 = `count_line_tiers` 的 `tier1(>500)` 恒 0 硬线）。
 *
 * 本类同时是 **AC⑪① 只读锁的执行点**：`updateCustomField` / `removeCustomField` 经
 * [withUpdatedCustomField] / [withoutCustomField] 就地拒绝被锁字段，
 * [updateProtectedValue]（私钥 PEM 与 `userHandle` 走的正是这条受保护明文通道）在此显式判锁。
 * 三道锁都收在本类，编辑页才谈得上「不存在改得动凭据材料的通路」。
 */
internal class EntryEditCustomFieldEditor(
    private val state: () -> EntryEditUiState,
    private val update: ((EntryEditUiState) -> EntryEditUiState) -> Unit,
    private val protectedChars: MutableMap<String, CharArray>
) {

    fun add() {
        val newField = buildNewCustomField("field_${System.currentTimeMillis()}")
        update { it.copy(customFields = it.customFields + newField, isDirty = true) }
    }

    /**
     * 非受保护字段明文 / 键名 / 保护标记的通用编辑入口。
     * TASK-10：保护标记切换时同步迁移明文存储位置——
     * - 非受保护 → 受保护：明文迁入 CharArray 私有链路，状态值转为空串（掩码投影语义）；
     * - 受保护 → 非受保护：明文迁回状态 String（非受保护字段按格式边界以明文存储），Char 副本擦除。
     *
     * AC⑪① 的判锁放在**函数开头**，而不是只靠 [withUpdatedCustomField] 挡下列表更新：
     * 上面两条切换分支都会动 [protectedChars]，被锁字段若走到那里就已经把凭据材料物化成
     * 一份明文副本留在内存里——「状态没变」不等于「没留下明文」。
     */
    fun updateField(id: String, key: String, value: String, isProtected: Boolean) {
        if (isLockedCustomField(state().customFields, id)) return
        val previous = state().customFields.firstOrNull { it.id == id }
        var effectiveValue = value
        when {
            previous != null && !previous.isProtected && isProtected && value.isNotEmpty() -> {
                protectedChars[id]?.fill('0')
                protectedChars[id] = value.toCharArray()
                effectiveValue = ""
            }
            previous != null && previous.isProtected && !isProtected -> {
                val chars = protectedChars.remove(id)
                if (chars != null) {
                    effectiveValue = String(chars)
                    chars.fill('0')
                }
            }
        }
        update { current ->
            current.copy(
                customFields = withUpdatedCustomField(current.customFields, id, key, effectiveValue, isProtected),
                isDirty = true
            )
        }
    }

    /**
     * TASK-10：受保护字段明文输入的 CharArray 桥接上行（语义同 `onPasswordChangeSecure`）。
     * 桥接数组归组件所有（组件自行清零），此处复制私有副本长期持有。
     *
     * AC⑪①：被锁字段（私钥 PEM / `credentialId` / `userHandle` / 两枚 PRF 种子）**直接拒收**，
     * 不入 [protectedChars]；入参已由组件负责擦除，本函数不重复擦。
     */
    fun updateProtectedValue(id: String, chars: CharArray) {
        if (isLockedCustomField(state().customFields, id)) return
        protectedChars[id]?.fill('0')
        protectedChars[id] = chars.copyOf()
        update { it.copy(isDirty = true) }
    }

    /** 移除字段（被锁字段由 [withoutCustomField] 挡下；连带擦除该 id 的明文副本）。 */
    fun remove(id: String) {
        protectedChars.remove(id)?.fill('0')
        update { current ->
            current.copy(customFields = withoutCustomField(current.customFields, id), isDirty = true)
        }
    }
}
