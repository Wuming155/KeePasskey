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
                // 入池必须使用独立副本：dataBytes 可能是旧池（existingPool）或原附件
                // 的内部数组别名，直接入池会让外部清零旧数组时连带清零新二进制池。
                dedupList.add(InnerHeader.BinaryItem(flag, dataBytes.clone()))
                idx
            }
            KdbxAttachment(
                name = att.name,
                refIndex = finalIndex,
                isProtected = att.isProtected,
                // 附件同样持独立拷贝：外部调用 attachment.clear()/close()（Closeable 契约）
                // 时只清零自身副本，严禁将内层 Header 二进制池（dedupList）中的数据一并清零。
                data = dataBytes.clone()
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
