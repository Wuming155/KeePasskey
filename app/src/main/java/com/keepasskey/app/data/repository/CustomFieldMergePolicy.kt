package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString

/**
 * 自定义字段合并策略（`ISSUE-P2-634`）。
 *
 * ## 缺陷背景
 *
 * 整改前 `mergeCustomFields` 无条件保留「未出现在 UI 键集合中的既有字段」。该规则本意是
 * 保护编辑页看不见的扩展属性（Passkey 材料），实际后果是：
 * - **删除无效**：UI 已经移除的字段被原样补回，用户以为敏感值已清除，库里仍在；
 * - **改名反增**：改名后旧键被补回、新键又写入，一个字段变成两个。
 *
 * ## 现行语义（三类意图分开处理）
 *
 * 1. **删除**：载入时存在（[loadedCustomFieldKeys] 有该 `UiCustomField.id`）、保存时该 id
 *    已不在 UI 列表中 ⇒ 旧键**退役**（不再补回）；
 * 2. **改名**：同一 id 的键发生了变化 ⇒ 旧键退役、新键按 UI 落地；
 * 3. **保留**：
 *    - Passkey schema 键永不退役（`ISSUE-P3-337` AC⑪① 的只读锁是 UI 侧第一道闸，
 *      这里是写入侧第二道——凭据材料缺失会让条目不可断言）；
 *    - 载入基线里没有的既有键（并发同步 / 其它窗口新增）一律保留——**不认识的键不删**，
 *      否则一次陈旧投影就能抹掉别人的字段。
 *
 * 无载入基线（新建条目、导入、草稿通道等未记录基线的调用方）时退化为整改前的
 * 「保留全部未在 UI 出现的既有字段」，行为零变化。
 *
 * 本类同时承担**键合法性校验**（[findInvalidKey]）：重名键与标准字段键冲突都会在
 * KDBX 序列化时写出两个同名 `<String>`，官方实现按后者覆盖 ⇒ 静默丢值，故写入前拒绝。
 */
internal object CustomFieldMergePolicy {

    /**
     * 标准字段键（与 XML 读写侧把 `<String>` 分流进 `fields` / `customFields` 的口径一致）。
     * 自定义字段与其中任一重名，都会在序列化后与标准字段同键。
     */
    private val RESERVED_KEYS: Set<String> = setOf(
        KdbxConstants.Fields.TITLE,
        KdbxConstants.Fields.USER_NAME,
        KdbxConstants.Fields.PASSWORD,
        KdbxConstants.Fields.URL,
        KdbxConstants.Fields.NOTES
    )

    /**
     * 找出投影中第一个**不合法**的键（重名 / 与标准字段冲突）；全部合法时返回 null。
     *
     * 空键不参与校验：编辑页允许存在「刚添加、尚未命名」的行，保存投影已把这些行过滤掉
     * （见 `buildEntrySaveSnapshot`），不会落库。
     */
    fun findInvalidKey(fields: List<UiCustomField>): String? {
        val seen = HashSet<String>()
        for (field in fields) {
            val key = field.key
            if (key.isEmpty()) continue
            if (!seen.add(key)) return key
            // 官方 KeePass 的字段表按忽略大小写比较，故小写同名同样视为冲突
            if (RESERVED_KEYS.any { it.equals(key, ignoreCase = true) }) return key
        }
        return null
    }

    /**
     * 合并自定义字段：UI 列表（按原序）在前，保留的既有字段在后。
     *
     * @param loadedCustomFieldKeys `UiCustomField.id → 载入时的键名`；空表 = 无基线（退化语义见类 KDoc）
     */
    fun merge(
        existing: KdbxEntry,
        entry: UiVaultEntry,
        protectedFieldChars: Map<String, CharArray>,
        loadedCustomFieldKeys: Map<String, String>
    ): List<KdbxCustomField> {
        val merged = entry.customFields.map { mergeOne(existing, it, protectedFieldChars) }
        val uiKeys = merged.mapTo(HashSet(merged.size)) { it.key }
        val retired = retiredKeys(entry, loadedCustomFieldKeys)
        val preserved = existing.customFields.filter { it.key !in uiKeys && it.key !in retired }
        return merged + preserved
    }

    /**
     * 本次保存要**退役**（不再补回）的旧键：删除的、被改名的；Passkey 键除外。
     *
     * 「旧键仍被某一行 UI 使用」不需要单独判分支：保留集本就把「UI 里出现的键」整体排除
     * （`it.key !in uiKeys`），退役集合只影响**未出现在 UI** 的既有字段。
     */
    private fun retiredKeys(
        entry: UiVaultEntry,
        loadedCustomFieldKeys: Map<String, String>
    ): Set<String> {
        if (loadedCustomFieldKeys.isEmpty()) return emptySet()
        val currentKeyById = entry.customFields.associate { it.id to it.key }
        val retired = HashSet<String>()
        for ((id, loadedKey) in loadedCustomFieldKeys) {
            if (currentKeyById[id] == loadedKey) continue           // 键未变：普通编辑，不退役
            if (PasskeyData.isPasskeyFieldKey(loadedKey)) continue  // 凭据材料保留集
            retired += loadedKey
        }
        return retired
    }

    private fun mergeOne(
        existing: KdbxEntry,
        field: UiCustomField,
        protectedFieldChars: Map<String, CharArray>
    ): KdbxCustomField {
        // TASK-10：受保护自定义字段编辑态明文以 CharArray 显式提交（键为字段编辑 id），
        // 仅用户显式编辑过的字段出现在 protectedFieldChars 中。
        // 擦除语义：submittedChars 归 saveEntry 的 finally 契约擦除；回填路径的
        // readChars 独占副本在密封后立即擦除
        val submittedChars = if (field.isProtected) protectedFieldChars[field.id] else null
        // F2 整改：UI 投影中受保护字段的明文恒为空（按需解密），未编辑的受保护字段
        // 回写时「空值」视为未修改，回填既有条目的真实值——防止详情页回滚等携带掩码
        // 投影的保存路径清空受保护字段
        val existingField = existing.customFields.firstOrNull { it.key == field.key }
        return when {
            submittedChars != null ->
                KdbxCustomField(field.key, ProtectedString(submittedChars, isProtected = true))
            field.isProtected && field.value.isEmpty() -> {
                val backfillChars = existingField?.value?.readChars()
                try {
                    KdbxCustomField(field.key, ProtectedString(backfillChars ?: CharArray(0), isProtected = true))
                } finally {
                    backfillChars?.fill('0')
                }
            }
            else ->
                KdbxCustomField(field.key, ProtectedString(field.value, isProtected = field.isProtected))
        }
    }
}
