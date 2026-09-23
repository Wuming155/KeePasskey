package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString

/**
 * 条目级三方合并单元（自 [KdbxMerger] 结构性拆出，零行为变更）。
 *
 * 负责条目存活裁决、字段级/自定义字段/标签/附件/历史合并与冲突清单生成，
 * 以及条目归属分组的回退规则；分组与墓碑处理分别由 [KdbxGroupMerger] / [KdbxTombstoneMerger] 承担。
 */
internal object KdbxEntryMerger {

    /**
     * ISSUE-P2-279：标量字段的**单一词汇表**——[isModified] 的判修改集、
     * [mergeConflictedEntry] 的实际合并集与 `diffFields` 上报集一律由本表驱动，
     * 禁止在多处各写一份字段清单。此前图标 / 覆写 URL / 质量检查判定进修改集，
     * 却不进合并集（`local.copy` 原样保留本地值）与冲突清单 ⇒ 远端改动静默丢失。
     *
     * 字段口径：
     * - `iconId` / `customIconId` / `overrideUrl`：本仓有真实写入者
     *   （`VaultEntryWriteCoordinator` / `VaultGroupCoordinator`），跨端合并必须生效；
     * - `qualityCheck`：本仓**无写入者**（仅序列化写出 / 健康检查读取，AC③ 适用范围声明）——
     *   保留在词汇表内是为合并 KeePassXC / 官方桌面端产生的改动（对端关闭某条目质量检查后
     *   同步回本端），非「判定含它但永不产生」的空转。
     *
     * 类型安全：每条的 `read` / `write` 绑定同一字段，泛型擦除点（`Any?`）的转型
     * 只在 `write` 闭包内发生，由词汇表自身保证不错位。
     */
    private class ScalarFieldSpec(
        val displayName: String,
        val read: (KdbxEntry) -> Any?,
        val write: (KdbxEntry, Any?) -> KdbxEntry
    )

    private val MERGED_SCALAR_FIELDS: List<ScalarFieldSpec> = listOf(
        ScalarFieldSpec("图标 (Icon)", { it.iconId }, { e, v -> e.copy(iconId = v as Int) }),
        ScalarFieldSpec(
            "自定义图标 (CustomIcon)",
            { it.customIconId },
            { e, v -> e.copy(customIconId = v as KdbxUuid?) }
        ),
        ScalarFieldSpec("覆写 URL (OverrideUrl)", { it.overrideUrl }, { e, v -> e.copy(overrideUrl = v as String?) }),
        ScalarFieldSpec("质量检查 (QualityCheck)", { it.qualityCheck }, { e, v -> e.copy(qualityCheck = v as Boolean) })
    )

    /**
     * 按 UUID 逐条目裁决三方存活集合，并汇总同字段分歧的冲突清单。
     *
     * 返回 `(survivingEntries, conflicts)`，语义与拆分前 `KdbxMerger.mergeDatabases` 内联分支逐字一致。
     */
    fun mergeSurvivingEntries(
        allEntryUuids: Set<KdbxUuid>,
        baseEntries: Map<KdbxUuid, KdbxEntry>,
        localEntries: Map<KdbxUuid, KdbxEntry>,
        remoteEntries: Map<KdbxUuid, KdbxEntry>,
        localDeleted: Map<KdbxUuid, DeletedObject>,
        remoteDeleted: Map<KdbxUuid, DeletedObject>
    ): Pair<List<KdbxEntry>, List<ConflictedEntryPair>> {
        val survivingEntries = mutableListOf<KdbxEntry>()
        val conflicts = mutableListOf<ConflictedEntryPair>()

        for (entryId in allEntryUuids) {
            val be = baseEntries[entryId]
            val le = localEntries[entryId]
            val re = remoteEntries[entryId]
            val ld = localDeleted[entryId]
            val rd = remoteDeleted[entryId]

            val isLocalDeleted = le == null && (ld != null || be != null)
            val isRemoteDeleted = re == null && (rd != null || be != null)

            when {
                le == null && re == null -> {
                    // 双方均无此条目 / 双方均已删除
                }
                isLocalDeleted && re != null -> {
                    val rModTime = re.times.lastModificationTime
                    val reRecreated = ld != null && rModTime.isAfter(ld.deletionTime)
                    val reModified = isModified(be, re)
                    if (reRecreated || reModified) {
                        // 修改胜 / 重建胜
                        survivingEntries.add(re)
                    }
                }
                isRemoteDeleted && le != null -> {
                    val lModTime = le.times.lastModificationTime
                    val leRecreated = rd != null && lModTime.isAfter(rd.deletionTime)
                    val leModified = isModified(be, le)
                    if (leRecreated || leModified) {
                        survivingEntries.add(le)
                    }
                }
                le != null && re != null -> {
                    val lModified = isModified(be, le)
                    val rModified = isModified(be, re)
                    when {
                        !lModified && !rModified -> survivingEntries.add(le)
                        lModified && !rModified -> survivingEntries.add(le)
                        !lModified && rModified -> survivingEntries.add(re)
                        else -> {
                            val (mergedEntry, conflictPair) = mergeConflictedEntry(be, le, re)
                            survivingEntries.add(mergedEntry)
                            if (conflictPair != null) {
                                conflicts.add(conflictPair)
                            }
                        }
                    }
                }
                le != null && re == null -> {
                    // 单侧新建条目（base 与墓碑中均无记录）：新建方保留。
                    // 缺失此分支会导致合并静默丢弃所有一端新建的条目（静默数据丢失）
                    survivingEntries.add(le)
                }
                le == null && re != null -> {
                    survivingEntries.add(re)
                }
            }
        }

        return Pair(survivingEntries, conflicts)
    }

    /**
     * 条目相对 base 是否被修改。
     * ISSUE-P3-03 (43a)：由 private 放宽为 internal —— 「每次询问」策略的决策清单扩充
     * （[BothModifiedEntryCollector]）必须复用同一修改判定，避免两处判定口径漂移。
     *
     * **口径边界（`ISSUE-P2-91` 复核，勿与另一处混用）**：本判定回答的是
     * 「条目相对 **base** 是否被修改」（冲突裁决用），故**必须**把 `times.lastModificationTime`
     * 计入；而「本地是否需要重新序列化上传」的问题由 `app` 模块的 `KdbxContentComparator`
     * 回答，那一侧**刻意不比 `times`**（`KdbxTimes` 含使用性字段 `lastAccessTime` / `usageCount`，
     * 纳入会让「触碰但内容等同」被判成变更 ⇒ 无意义重传并前移远端 ETag）。
     * 两处口径**刻意不同**，理由各自就地声明；边界登记于 `docs/architecture/已知工程限界.md` §10。
     */
    fun isModified(base: KdbxEntry?, current: KdbxEntry): Boolean {
        if (base == null) return true
        // ProtectedString.equals 为字节数组内容比较，直接用 Map 相等性判断，
        // 不经 readString() 将全库密码物化为不可清除的 String
        if (base.fields != current.fields) return true
        if (base.customFields != current.customFields) return true
        if (base.tags != current.tags) return true
        if (base.attachments != current.attachments) return true
        if (base.parentGroupId != current.parentGroupId) return true
        // ISSUE-P2-279：标量字段判定与合并共用同一词汇表（禁两份清单）
        if (MERGED_SCALAR_FIELDS.any { it.read(base) != it.read(current) }) return true
        if (base.times.lastModificationTime != current.times.lastModificationTime) return true
        return false
    }

    /**
     * 条目分配至所属分组：parentGroupId 失链时（删除vs修改复活场景）优先回退到
     * previousParentGroup（KeePassXC Merger 复活规则：跨设备移动/复活条目应回到原父组
     * 而非无条件抛到根组），仍无处可挂才归属根组；回退时同步改写条目 parentGroupId。
     */
    fun assignEntriesToGroups(
        survivingEntries: List<KdbxEntry>,
        sanitizedGroups: Map<KdbxUuid, KdbxGroup>,
        rootId: KdbxUuid
    ): Map<KdbxUuid, List<KdbxEntry>> {
        return survivingEntries
            .map { entry ->
                val pid = entry.parentGroupId
                if (pid != null && sanitizedGroups.containsKey(pid)) {
                    entry
                } else {
                    val target = entry.previousParentGroup
                        ?.takeIf { sanitizedGroups.containsKey(it) }
                        ?: rootId
                    entry.copy(parentGroupId = target)
                }
            }
            .groupBy { it.parentGroupId!! }
    }

    private fun mergeConflictedEntry(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): Pair<KdbxEntry, ConflictedEntryPair?> {
        val diffFields = mutableListOf<String>()

        // 字段级三方合并
        val mergedFields = mergeStandardFields(base, local, remote, diffFields)
        val mergedCustomFields = mergeCustomFields(base, local, remote, diffFields)

        // 标签合并 (Union)
        val mergedTags = (local.tags + remote.tags).distinct()

        val mergedAttachments = mergeAttachments(base, local, remote)

        // 历史版本合并（对齐官方 MergeIn：三方历史并集，按最后修改时间去重后升序排列；
        // 时间戳碰撞时优先保留本地侧快照）
        val mergedHistory = (local.history + remote.history + base?.history.orEmpty())
            .distinctBy { it.times.lastModificationTime }
            .sortedBy { it.times.lastModificationTime }

        // 时间戳取最新
        val maxMod = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) {
            remote.times.lastModificationTime
        } else {
            local.times.lastModificationTime
        }

        val mergedEntry = mergeScalarFields(base, local, remote, diffFields).copy(
            fields = mergedFields,
            customFields = mergedCustomFields,
            tags = mergedTags,
            attachments = mergedAttachments,
            history = mergedHistory,
            times = local.times.copy(lastModificationTime = maxMod),
            parentGroupId = resolveMergedParentGroup(base, local, remote)
        )

        return Pair(mergedEntry, conflictPairOf(local, remote, diffFields))
    }

    /**
     * ISSUE-P2-279：标量字段三方合并（判定 / 合并 / 上报同一词汇表 [MERGED_SCALAR_FIELDS] 驱动）。
     *
     * 单侧变更取该侧；双侧同值取本地；双侧异值**按 LWW 明确裁决**（与标准字段冲突的
     * 取胜口径一致）并把字段名记入 [diffFields] 留痕——冲突清单由此覆盖图标 / 覆写 URL /
     * 质量检查分歧，不再「判定为修改却静默丢远端值」。
     *
     * 以 `local` 为底版逐字段覆写取胜值；未变动的字段保持 `read(local)` 原值。
     */
    private fun mergeScalarFields(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry,
        diffFields: MutableList<String>
    ): KdbxEntry {
        val lTime = local.times.lastModificationTime
        val rTime = remote.times.lastModificationTime
        var merged = local
        for (spec in MERGED_SCALAR_FIELDS) {
            val bv = base?.let(spec.read)
            val lv = spec.read(local)
            val rv = spec.read(remote)
            val winner = when {
                lv != bv && rv == bv -> lv
                lv == bv && rv != bv -> rv
                lv == rv -> lv
                else -> {
                    diffFields.add(spec.displayName)
                    if (rTime.isAfter(lTime)) rv else lv
                }
            }
            merged = spec.write(merged, winner)
        }
        return merged
    }

    /**
     * 字段级差异非空时构成「冲突对」，交由上层冲突解决页展示；为空则静默合并、不上报冲突。
     *
     * §182 自 [mergeConflictedEntry] 原样搬出（只搬不改逻辑），使该函数只剩「按字段装配合并结果」一件事。
     */
    private fun conflictPairOf(
        local: KdbxEntry,
        remote: KdbxEntry,
        diffFields: List<String>
    ): ConflictedEntryPair? = if (diffFields.isNotEmpty()) {
        ConflictedEntryPair(
            entryId = local.id.toHexString(),
            localEntry = local,
            remoteEntry = remote,
            modifiedFields = diffFields
        )
    } else {
        null
    }

    /** 标准字段三方合并：单侧变更取该侧，双侧同值取本地，双侧异值按最后修改时间取胜方并记入 [diffFields]。 */
    private fun mergeStandardFields(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry,
        diffFields: MutableList<String>
    ): Map<String, ProtectedString> {
        val allFieldKeys = (local.fields.keys + remote.fields.keys + (base?.fields?.keys ?: emptySet())).toSet()
        val mergedFields = mutableMapOf<String, ProtectedString>()

        for (key in allFieldKeys) {
            val bv = base?.fields?.get(key)
            val lv = local.fields[key]
            val rv = remote.fields[key]

            val lChanged = isFieldDifferent(lv, bv)
            val rChanged = isFieldDifferent(rv, bv)

            when {
                lChanged && !rChanged -> if (lv != null) mergedFields[key] = lv
                !lChanged && rChanged -> if (rv != null) mergedFields[key] = rv
                !lChanged && !rChanged -> if (lv != null) mergedFields[key] = lv
                else -> {
                    // 双方均修改
                    if (!isFieldDifferent(lv, rv)) {
                        if (lv != null) mergedFields[key] = lv
                    } else {
                        // 冲突字段
                        diffFields.add(getFieldDisplayName(key))
                        val picked = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) rv else lv
                        if (picked != null) mergedFields[key] = picked
                    }
                }
            }
        }
        return mergedFields
    }

    /** 自定义字段三方合并：判定口径同标准字段，冲突记入 [diffFields]。 */
    private fun mergeCustomFields(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry,
        diffFields: MutableList<String>
    ): List<KdbxCustomField> {
        val baseCustomMap = base?.customFields?.associateBy { it.key } ?: emptyMap()
        val localCustomMap = local.customFields.associateBy { it.key }
        val remoteCustomMap = remote.customFields.associateBy { it.key }
        val allCustomKeys = (localCustomMap.keys + remoteCustomMap.keys + baseCustomMap.keys).toSet()
        val mergedCustomFields = mutableListOf<KdbxCustomField>()

        for (key in allCustomKeys) {
            val bc = baseCustomMap[key]
            val lc = localCustomMap[key]
            val rc = remoteCustomMap[key]

            // KdbxCustomField 为 data class，其 value 的 ProtectedString.equals
            // 为字节数组内容比较，无需物化明文
            val lChanged = lc?.value != bc?.value
            val rChanged = rc?.value != bc?.value

            when {
                lChanged && !rChanged -> if (lc != null) mergedCustomFields.add(lc)
                !lChanged && rChanged -> if (rc != null) mergedCustomFields.add(rc)
                !lChanged && !rChanged -> if (lc != null) mergedCustomFields.add(lc)
                else -> {
                    if (lc?.value == rc?.value) {
                        if (lc != null) mergedCustomFields.add(lc)
                    } else {
                        diffFields.add("自定义字段: $key")
                        val picked = if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) rc else lc
                        if (picked != null) mergedCustomFields.add(picked)
                    }
                }
            }
        }
        return mergedCustomFields
    }

    /** 附件合并：单侧变更取该侧；双侧变更按名称并集（远端先入、本地覆盖同名项）。 */
    private fun mergeAttachments(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): List<KdbxAttachment> {
        val bAttachments: List<KdbxAttachment> = base?.attachments ?: emptyList()
        return when {
            local.attachments != bAttachments && remote.attachments == bAttachments -> local.attachments
            local.attachments == bAttachments && remote.attachments != bAttachments -> remote.attachments
            else -> {
                val attMap = mutableMapOf<String, KdbxAttachment>()
                bAttachments.forEach { attMap[it.name] = it }
                remote.attachments.forEach { attMap[it.name] = it }
                local.attachments.forEach { attMap[it.name] = it }
                attMap.values.toList()
            }
        }
    }

    /** 父分组归属：单侧移动取该侧；双侧异动按最后修改时间取胜方。 */
    private fun resolveMergedParentGroup(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): KdbxUuid? = when {
        local.parentGroupId != base?.parentGroupId && remote.parentGroupId == base?.parentGroupId -> local.parentGroupId
        local.parentGroupId == base?.parentGroupId && remote.parentGroupId != base?.parentGroupId -> remote.parentGroupId
        else -> if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) remote.parentGroupId else local.parentGroupId
    }

    private fun isFieldDifferent(a: ProtectedString?, b: ProtectedString?): Boolean {
        if (a == null && b == null) return false
        if (a == null || b == null) return true
        // ProtectedString.equals 为字节数组内容比较，不物化明文 String
        return a != b
    }

    private fun getFieldDisplayName(key: String): String {
        return when (key) {
            KdbxConstants.Fields.TITLE -> "标题 (Title)"
            KdbxConstants.Fields.USER_NAME -> "用户名 (Username)"
            KdbxConstants.Fields.PASSWORD -> "密码 (Password)"
            KdbxConstants.Fields.URL -> "网址 (URL)"
            KdbxConstants.Fields.NOTES -> "备注 (Notes)"
            else -> key
        }
    }
}
