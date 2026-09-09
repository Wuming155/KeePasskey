package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup

/**
 * KDBX 附件二进制池去重器（遵循官方 ProtectedBinarySet 语义）。
 * 将整库中的所有附件按字节内容与保护标志进行池化去重，更新各条目及历史快照中的引用索引。
 */
object KdbxBinaryDeduplicator {

    /**
     * 二进制指纹（flags + 字节内容）：P3-5 整改——以哈希索引替代 O(n²) 线性遍历，
     * 等值语义为字节数组内容比较（[ByteArray.contentEquals]）。
     */
    private class Fingerprint(private val flags: Byte, private val data: ByteArray) {
        private val hash: Int = 31 * flags.hashCode() + data.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is Fingerprint && other.flags == flags && other.data.contentEquals(data)
    }

    fun deduplicate(
        rootGroup: KdbxGroup,
        existingPool: List<InnerHeader.BinaryItem>
    ): Pair<KdbxGroup, List<InnerHeader.BinaryItem>> {
        val dedupList = mutableListOf<InnerHeader.BinaryItem>()
        // P3-5 整改：指纹索引整库共享（与 dedupList 同生命周期），跨条目/跨历史去重均命中
        val fingerprintIndex = HashMap<Fingerprint, Int>()
        val updatedGroup = deduplicateGroup(rootGroup, existingPool, dedupList, fingerprintIndex)
        return Pair(updatedGroup, dedupList)
    }

    private fun deduplicateGroup(
        group: KdbxGroup,
        existingPool: List<InnerHeader.BinaryItem>,
        dedupList: MutableList<InnerHeader.BinaryItem>,
        fingerprintIndex: HashMap<Fingerprint, Int>
    ): KdbxGroup {
        val updatedEntries = group.entries.map { entry ->
            deduplicateEntry(entry, existingPool, dedupList, fingerprintIndex)
        }
        val updatedSubgroups = group.subgroups.map { sub ->
            deduplicateGroup(sub, existingPool, dedupList, fingerprintIndex)
        }
        return group.copy(entries = updatedEntries, subgroups = updatedSubgroups)
    }

    private fun deduplicateEntry(
        entry: KdbxEntry,
        existingPool: List<InnerHeader.BinaryItem>,
        dedupList: MutableList<InnerHeader.BinaryItem>,
        fingerprintIndex: HashMap<Fingerprint, Int>
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
            val finalIndex = fingerprintIndex.getOrPut(Fingerprint(flag, dataBytes)) {
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
            deduplicateEntry(histEntry, existingPool, dedupList, fingerprintIndex)
        }

        return entry.copy(
            attachments = updatedAttachments,
            history = updatedHistory
        )
    }
}
