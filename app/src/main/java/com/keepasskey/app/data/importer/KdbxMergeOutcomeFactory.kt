package com.keepasskey.app.data.importer

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult

/**
 * ISSUE-P3-384 / P3-395：`.kdbx` 并入的轻量树装配与报告组装（自 [KdbxMergeController] 下沉）。
 *
 * 行数门禁：控制器保持 <400 行（tier2 棘轮）；本文件只承载纯装配/计数/警告逻辑。
 */
internal object KdbxMergeOutcomeFactory {

    /** 合并结果相对本地树的增量计数（非敏感）。 */
    internal data class MergeDelta(
        val entriesAdded: Int,
        val groupsAdded: Int,
        val conflicts: Int,
        val customIconsAdded: Int
    )

    /**
     * base=空根（UUID 同 local 根）+ local + remote 三棵轻量镜像。
     * 空底版：对端独有对象视为新增；同 UUID 冲突由 [KdbxMerger] 产出清单，调用方 KEEP_LOCAL。
     */
    fun buildMergeLites(
        localDb: KdbxDatabase,
        otherDb: KdbxDatabase
    ): Triple<KdbxDatabaseLite, KdbxDatabaseLite, KdbxDatabaseLite> {
        val localLite = KdbxDatabaseLite(
            rootGroup = localDb.rootGroup,
            deletedObjects = localDb.deletedObjects,
            customIcons = localDb.customIcons
        )
        val emptyRoot = localDb.rootGroup.copy(
            entries = emptyList(),
            subgroups = emptyList()
        )
        val baseLite = KdbxDatabaseLite(
            rootGroup = emptyRoot,
            deletedObjects = emptyList(),
            customIcons = emptyList()
        )
        val remoteLite = KdbxDatabaseLite(
            rootGroup = otherDb.rootGroup,
            deletedObjects = otherDb.deletedObjects,
            customIcons = otherDb.customIcons
        )
        return Triple(baseLite, localLite, remoteLite)
    }

    fun mergeDelta(
        localRoot: KdbxGroup,
        localCustomIcons: List<CustomIcon>,
        merged: MergeResult
    ): MergeDelta {
        val localEntryIds = localRoot.allEntries().map { it.id }.toSet()
        val localGroupIds = localRoot.allGroups().map { it.id }.toSet()
        val mergedEntryIds = merged.mergedRoot.allEntries().map { it.id }.toSet()
        val mergedGroupIds = merged.mergedRoot.allGroups().map { it.id }.toSet()
        val customIconsBefore = localCustomIcons.map { it.uuid }.toSet()
        return MergeDelta(
            entriesAdded = (mergedEntryIds - localEntryIds).size,
            groupsAdded = (mergedGroupIds - localGroupIds).size,
            // KEEP_LOCAL：冲突条目在合并树中保留的是本地实例 ⇒ 以「对端独有且被并入」计数
            conflicts = merged.conflicts.size,
            customIconsAdded = merged.mergedCustomIcons.count { it.uuid !in customIconsBefore }
        )
    }

    /** 合并报告警告（ISSUE-P3-395：0 新增时说明对端无独有对象）。 */
    fun mergeWarnings(delta: MergeDelta): List<ImportWarning> {
        val warnings = mutableListOf<ImportWarning>()
        if (delta.conflicts > 0) {
            warnings += ImportWarning(
                location = "kdbx-merge",
                reason = "conflicts_kept_local:${delta.conflicts}"
            )
        }
        if (delta.entriesAdded == 0 && delta.groupsAdded == 0 && delta.conflicts == 0) {
            warnings += ImportWarning(
                location = "kdbx-merge",
                reason = ImportWarningReason.KDBX_MERGE_NO_REMOTE_UNIQUE.code
            )
        }
        return warnings
    }

    fun mergeOutcome(delta: MergeDelta): ImportOutcome = ImportOutcome(
        source = ImportSource.KDBX_MERGE,
        parsed = delta.entriesAdded + delta.groupsAdded + delta.conflicts,
        sourceSkipped = 0,
        imported = delta.entriesAdded,
        updated = 0,
        skipped = delta.conflicts,
        failed = 0,
        movedToRecycleBin = 0,
        warnings = mergeWarnings(delta)
    )
}
