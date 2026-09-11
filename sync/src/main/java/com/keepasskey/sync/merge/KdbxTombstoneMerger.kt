package com.keepasskey.sync.merge

import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxUuid

/**
 * 墓碑（deletedObjects）合并单元（自 [KdbxMerger] 结构性拆出，零行为变更）。
 *
 * 三方墓碑按 UUID 去重，仅保留**未被存活集合覆盖**的墓碑，并取最晚删除时间戳。
 */
internal object KdbxTombstoneMerger {

    fun merge(
        baseDeleted: List<DeletedObject>,
        localDeleted: List<DeletedObject>,
        remoteDeleted: List<DeletedObject>,
        survivingUuids: Set<KdbxUuid>
    ): List<DeletedObject> {
        val candidateTombstones = (baseDeleted + localDeleted + remoteDeleted)
            .groupBy { it.id }

        val mergedDeletedObjects = mutableListOf<DeletedObject>()
        for ((id, list) in candidateTombstones) {
            // 若存活（修改方胜或重建胜），从墓碑中剔除
            if (!survivingUuids.contains(id)) {
                val latest = list.maxByOrNull { it.deletionTime }!!
                mergedDeletedObjects.add(latest)
            }
        }
        return mergedDeletedObjects
    }
}
