package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString

/**
 * 条目合并附属策略（附件 / 父组归属 / 字段差异）——自 [KdbxEntryMerger] 拆出（行数门禁）。
 */
internal object EntryMergeHelpers {

    /** 附件合并：单侧变更取该侧；双侧变更按名称并集（远端先入、本地覆盖同名项）。 */
    fun mergeAttachments(
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
    fun resolveMergedParentGroup(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): KdbxUuid? = when {
        local.parentGroupId != base?.parentGroupId && remote.parentGroupId == base?.parentGroupId -> local.parentGroupId
        local.parentGroupId == base?.parentGroupId && remote.parentGroupId != base?.parentGroupId -> remote.parentGroupId
        else -> if (remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)) remote.parentGroupId else local.parentGroupId
    }

    fun isFieldDifferent(a: ProtectedString?, b: ProtectedString?): Boolean {
        if (a == null && b == null) return false
        if (a == null || b == null) return true
        return a != b
    }
}
