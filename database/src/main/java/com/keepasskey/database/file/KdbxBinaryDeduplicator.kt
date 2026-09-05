package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup

/**
 * KDBX 附件二进制池去重器（遵循官方 ProtectedBinarySet 语义）。
 * 将整库中的所有附件按字节内容与保护标志进行池化去重，更新各条目及历史快照中的引用索引。
 */
object KdbxBinaryDeduplicator {

    fun deduplicate(
        rootGroup: KdbxGroup,
        existingPool: List<InnerHeader.BinaryItem>
    ): Pair<KdbxGroup, List<InnerHeader.BinaryItem>> {
        val dedupList = mutableListOf<InnerHeader.BinaryItem>()
        val updatedGroup = deduplicateGroup(rootGroup, existingPool, dedupList)
        return Pair(updatedGroup, dedupList)
    }

    private fun deduplicateGroup(
        group: KdbxGroup,
        existingPool: List<InnerHeader.BinaryItem>,
        dedupList: MutableList<InnerHeader.BinaryItem>
    ): KdbxGroup {
        val updatedEntries = group.entries.map { entry ->
            deduplicateEntry(entry, existingPool, dedupList)
        }
        val updatedSubgroups = group.subgroups.map { sub ->
            deduplicateGroup(sub, existingPool, dedupList)
        }
        return group.copy(entries = updatedEntries, subgroups = updatedSubgroups)
    }

    private fun deduplicateEntry(
        entry: KdbxEntry,
        existingPool: List<InnerHeader.BinaryItem>,
        dedupList: MutableList<InnerHeader.BinaryItem>
    ): KdbxEntry {
        val updatedAttachments = entry.attachments.map { att ->
            val dataBytes = if (att.data.isNotEmpty()) {
                att.data
            } else if (att.refIndex in existingPool.indices) {
                existingPool[att.refIndex].data
            } else {
                ByteArray(0)
            }
            val flag: Byte = if (att.isProtected) 1.toByte() else {
                if (att.refIndex in existingPool.indices) existingPool[att.refIndex].flags else 0.toByte()
            }
            val existingIndex = dedupList.indexOfFirst { it.flags == flag && it.data.contentEquals(dataBytes) }
            val finalIndex = if (existingIndex >= 0) {
                existingIndex
            } else {
                val idx = dedupList.size
                dedupList.add(InnerHeader.BinaryItem(flag, dataBytes))
                idx
            }
            KdbxAttachment(
                name = att.name,
                refIndex = finalIndex,
                isProtected = att.isProtected,
                data = dataBytes
            )
        }

        val updatedHistory = entry.history.map { histEntry ->
            deduplicateEntry(histEntry, existingPool, dedupList)
        }

        return entry.copy(
            attachments = updatedAttachments,
            history = updatedHistory
        )
    }
}
