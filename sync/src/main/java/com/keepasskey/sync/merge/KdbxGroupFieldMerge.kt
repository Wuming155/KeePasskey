package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid

/**
 * 分组标量字段的三方合并单元（`ISSUE-P2-279` / `ISSUE-P1-631`，自 [KdbxGroupMerger] 结构性拆出）。
 *
 * 承载「分组相对 base 是否被修改」与「双方均改时的字段级合并 + 同字段双侧异值的冲突登记」。
 * 拆出动因有二：①行数门禁（文件不得越过 400 档）；②把**字段词汇表与其两个消费点
 * （判修改 / 判冲突 / 实际取值）放在同一文件**，避免判定与取值分居两处而漂移。
 *
 * 与存活裁决的关系：[KdbxGroupMerger] 负责「哪些分组存活」（含墓碑 / 删除 vs 修改），
 * 本件只在「双方均存活且均相对 base 有修改」时被调用。
 */
    /**
 * ISSUE-P2-279：分组标量字段的**单一词汇表**——[isGroupModified] 的判修改集与
 * [mergeGroupsBothModified] 的实际合并集由本表驱动，禁止两处各写一份字段清单。
 * 此前 `customIconId` 判定进修改集却不进合并集（`local.copy` 原样保留本地值）⇒
 * 他端改分组图标后同步回本端时静默丢失（本仓无分组 `customIconId` 写入者，
 * 需 KeePassXC / 官方桌面端改组图标方可达，核实见条目正文）。
 *
 * `ISSUE-P1-631`：追加 [key]——同字段双侧异值时**登记冲突**所需的机读键，
 * 取值即 [KdbxGroupConflictKeys] 常量（与消费侧共用同一份词汇，禁字面量对拍）。
 */
internal class GroupFieldSpec(
    val key: String,
    val read: (KdbxGroup) -> Any?,
    val write: (KdbxGroup, Any?) -> KdbxGroup
)

internal val MERGED_GROUP_FIELDS: List<GroupFieldSpec> = listOf(
    GroupFieldSpec(
        KdbxGroupConflictKeys.NAME,
        { it.name },
        { g, v -> g.copy(name = v as String) }
    ),
    GroupFieldSpec(
        KdbxGroupConflictKeys.NOTES,
        { it.notes },
        { g, v -> g.copy(notes = v as String) }
    ),
    GroupFieldSpec(
        KdbxGroupConflictKeys.ICON_ID,
        { it.iconId },
        { g, v -> g.copy(iconId = v as Int) }
    ),
    GroupFieldSpec(
        KdbxGroupConflictKeys.CUSTOM_ICON_ID,
        { it.customIconId },
        { g, v -> g.copy(customIconId = v as KdbxUuid?) }
    ),
    GroupFieldSpec(
        KdbxGroupConflictKeys.PARENT_GROUP_ID,
        { it.parentGroupId },
        { g, v -> g.copy(parentGroupId = v as KdbxUuid?) }
    )
    // ISSUE-P2-385：组级 customData（Secret Service 暴露组标记等第三方键）由
    // [mergeCustomDataMap] 单独并入，不进本词汇表——其为 Map 语义、需三方键并集。
)

/** `ISSUE-P1-631`：双方均改的字段级合并产物——值 + **同字段双侧异值**的差异键清单 */
internal class GroupFieldMergeResult(
    val group: KdbxGroup,
    val conflictingFields: List<String>
)

internal fun isGroupModified(base: KdbxGroup?, current: KdbxGroup): Boolean {
    if (base == null) return true
    // ISSUE-P2-279：判定与合并共用同一词汇表（禁两份清单）
    return MERGED_GROUP_FIELDS.any { it.read(base) != it.read(current) } ||
            base.customData != current.customData ||
            base.times.lastModificationTime != current.times.lastModificationTime
}

/**
 * 双方均修改时的分组字段级合并：逐字段三方裁决——单侧变更取该侧，双侧同值取本地，
 * 双侧异值按最后修改时间（LWW）取胜方；时间戳取较晚者。
 * 词汇表见 [MERGED_GROUP_FIELDS]（ISSUE-P2-279 起含 `customIconId`）。
 * ISSUE-P2-385：组级 customData 由 [mergeCustomDataMap] 并入。
 *
 * `ISSUE-P1-631`：LWW 只决定**存活的临时值**（保证树结构完整），同时把「双侧都相对 base
 * 改过、且两侧值不同」的字段键登记进 [GroupFieldMergeResult.conflictingFields]——
 * 它正是「按修改时间分不出对错、只能由人决定」的那一类，不得无声定案。
 * 判定式与取值式同源：`else` 分支（前三支都不命中）恰好等价于
 * `lv != bv && rv != bv && lv != rv`，故两者不可能漂移。
 */
internal fun mergeGroupsBothModified(
    base: KdbxGroup?,
    local: KdbxGroup,
    remote: KdbxGroup
): GroupFieldMergeResult {
    val lTime = local.times.lastModificationTime
    val rTime = remote.times.lastModificationTime
    val conflicting = mutableListOf<String>()

    var merged = local
    for (spec in MERGED_GROUP_FIELDS) {
        val bv = base?.let(spec.read)
        val lv = spec.read(local)
        val rv = spec.read(remote)
        val winner = when {
            lv != bv && rv == bv -> lv
            lv == bv && rv != bv -> rv
            lv == rv -> lv
            else -> {
                // 双侧都相对 base 改过且两侧值不同：记录冲突，值按 LWW 临时给出
                conflicting += spec.key
                if (rTime.isAfter(lTime)) rv else lv
            }
        }
        merged = spec.write(merged, winner)
    }

    val baseCustomData = base?.customData
    conflicting += conflictingCustomDataKeys(baseCustomData, local.customData, remote.customData)
    merged = merged.copy(
        customData = EntryCustomDataMerge.mergeCustomDataMap(
            baseCustomData,
            local.customData,
            remote.customData,
            lTime,
            rTime
        )
    )
    val maxMod = if (rTime.isAfter(lTime)) rTime else lTime
    return GroupFieldMergeResult(
        merged.copy(times = local.times.copy(lastModificationTime = maxMod)),
        conflicting
    )
}

/**
 * `ISSUE-P1-631`：组级 customData 中「双侧都相对 base 改过、且两侧值不同」的键。
 *
 * 判定式与 [mergeCustomDataMap] 的取值分支**同源**（该函数的 else 分支），
 * 键并集口径也一致——不得另写一套「哪些键算冲突」的判据。
 */
internal fun conflictingCustomDataKeys(
    base: Map<String, String>?,
    local: Map<String, String>,
    remote: Map<String, String>
): List<String> {
    if (local == remote) return emptyList()
    val baseMap = base.orEmpty()
    if (local == baseMap || remote == baseMap) return emptyList()
    val keys = baseMap.keys + local.keys + remote.keys
    val conflicting = mutableListOf<String>()
    for (key in keys) {
        val bv = baseMap[key]
        val lv = local[key]
        val rv = remote[key]
        val singleSideWins = (lv != bv && rv == bv) || (lv == bv && rv != bv) || lv == rv
        if (!singleSideWins) conflicting += KdbxGroupConflictKeys.customDataKey(key)
    }
    return conflicting
}

