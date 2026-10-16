package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry

/**
 * `ISSUE-P1-631`：**只重裁差异字段**的整批策略实现（自 [KdbxMerger] 结构性拆出，控文件行数）。
 *
 * 唯一入口 [KdbxMerger.resolveConflictFieldsOnly]（KDoc 见该处）；本件只承载字段应用逻辑，
 * 不新增任何对外 API。
 */
internal object KdbxConflictFieldResolver {

    /**
     * `DELETE_REMOTE` 的适用字段集（可删除的标准字段）；单一清单，与
     * [KdbxMerger.resolveConflictByFields] 共用（自 KdbxMerger 迁出，禁两处各写一份）。
     */
    val DELETABLE_STANDARD_FIELDS = setOf(
        KdbxConstants.Fields.TITLE,
        KdbxConstants.Fields.USER_NAME,
        KdbxConstants.Fields.PASSWORD,
        KdbxConstants.Fields.URL,
        KdbxConstants.Fields.NOTES,
        KdbxConstants.Fields.OTP
    )

    /**
     * 以三方合并产物 [merged] 为底版，把 [pair] 的差异字段整体取 [choice] 一侧。
     *
     * 字段级采用语义：
     * - 所选侧存在该字段 ⇒ 取其值；所选侧缺失该字段（分叉形态之一是「删 vs 改」）⇒ 删除之
     *   （仅限 [DELETABLE_STANDARD_FIELDS] 与自定义字段）；标量 / 联合到期等语义键保持合并产物取值；
     * - 任一侧值被实际采用时刷新 `lastModificationTime`（与 [KdbxMerger.resolveConflictByFields] 同口径）。
     */
    fun resolve(
        merged: KdbxEntry,
        pair: ConflictedEntryPair,
        choice: ConflictResolutionChoice
    ): KdbxEntry {
        val side = when (choice) {
            ConflictResolutionChoice.KEEP_LOCAL -> pair.localEntry
            ConflictResolutionChoice.KEEP_REMOTE -> pair.remoteEntry
            ConflictResolutionChoice.DUPLICATE_BOTH -> throw IllegalArgumentException(
                "DUPLICATE_BOTH 不适用字段级重裁；双方保留由调用方组装"
            )
        }
        val sideChoices = pair.modifiedFields.associateWith { key ->
            when {
                key.startsWith(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX) -> {
                    val customKey = key.removePrefix(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX)
                    if (side.customFields.any { it.key == customKey }) {
                        FieldResolutionChoice.KEEP_REMOTE
                    } else {
                        FieldResolutionChoice.DELETE_REMOTE
                    }
                }
                key in DELETABLE_STANDARD_FIELDS ->
                    if (key in side.fields) FieldResolutionChoice.KEEP_REMOTE
                    else FieldResolutionChoice.DELETE_REMOTE
                // 标量 / 联合到期 / 颜色 / AutoType 等语义键：整值取所选侧（不可删除）
                else -> FieldResolutionChoice.KEEP_REMOTE
            }
        }
        // 把所选侧装进 pair.remoteEntry 槽位：本原语只借用 resolveConflictByFields 的字段词汇表，
        // 「remote」此处语义＝「用户钦点的一侧」；合并产物作为底版（local 槽位）。
        return KdbxMerger.resolveConflictByFields(
            pair = pair.copy(localEntry = merged, remoteEntry = side),
            fieldChoices = sideChoices
        )
    }
}
