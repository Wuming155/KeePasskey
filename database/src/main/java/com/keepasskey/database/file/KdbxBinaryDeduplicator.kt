package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import java.io.InputStream

/**
 * KDBX 附件二进制池去重器（遵循官方 ProtectedBinarySet 语义）。
 * 将整库中的所有附件按字节内容与保护标志进行池化去重，更新各条目及历史快照中的引用索引。
 *
 * ISSUE-P2-24：去重全程**不要求整份物化**——指纹取 `(flags, size, 内容哈希)`，
 * 仅在同指纹碰撞时才以流式逐字节比对确认；落盘大附件复用既有 [InnerHeader.BinaryItem] 引用，
 * 不再读回内存。等价语义仍是「flags + 字节内容」完全一致（流式比对兜底哈希碰撞，绝不误合并）。
 */
object KdbxBinaryDeduplicator {

    /**
     * 二进制指纹：`flags + size + 内容哈希`。以哈希索引替代 O(n²) 线性遍历（P3-5 整改），
     * 碰撞由 [contentEquals] 流式逐字节复核，故不存在误合并。
     */
    private class Fingerprint(
        private val flags: Byte,
        private val size: Long,
        private val contentHash: Int
    ) {
        override fun hashCode(): Int = 31 * (31 * flags.hashCode() + size.hashCode()) + contentHash

        override fun equals(other: Any?): Boolean =
            other is Fingerprint &&
                other.flags == flags &&
                other.size == size &&
                other.contentHash == contentHash
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
            val existingItem = existingPool.getOrNull(att.refIndex)
            val flag: Byte = if (att.isProtected) {
                1.toByte()
            } else {
                existingItem?.flags ?: 0.toByte()
            }
            val candidate = candidateItem(att, existingItem, flag)
            val fingerprint = Fingerprint(flag, candidate.size, candidate.contentHash())
            val finalIndex = resolveIndex(fingerprint, candidate, dedupList, fingerprintIndex)
            val pooled = dedupList[finalIndex]
            // 池为唯一权威源：内存条目交付独立副本；落盘条目交付按需引用
            // （其 load() 每次返回独立副本，别名隔离契约与 P3-07 一致）。
            KdbxAttachment(
                name = att.name,
                refIndex = finalIndex,
                isProtected = att.isProtected,
                data = if (pooled.isSpilled) ByteArray(0) else pooled.data.copyOf(),
                source = if (pooled.isSpilled) pooled else null
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

    /**
     * 构造候选池条目（不整份物化落盘附件）：
     * 1. 附件自带落盘来源 → 仅改写 flags（复用 store key，零读取）；
     * 2. 附件自带内存副本 → 克隆入池（入池必须独立副本，避免外部清零旧数组连带清零池）；
     * 3. 回退旧池条目（其数据本就在池中，直接复用）；
     * 4. 兜底空数组。
     */
    private fun candidateItem(
        att: KdbxAttachment,
        existingItem: InnerHeader.BinaryItem?,
        flag: Byte
    ): InnerHeader.BinaryItem {
        val source = att.binarySource()
        if (source is InnerHeader.BinaryItem) return source.withFlags(flag)
        val inline = att.inlineBytes()
        if (inline.isNotEmpty()) return InnerHeader.BinaryItem(flag, inline.clone())
        if (source != null) return InnerHeader.BinaryItem(flag, source.load())
        if (existingItem != null) return existingItem.withFlags(flag)
        return InnerHeader.BinaryItem(flag, ByteArray(0))
    }

    /** 指纹命中时以流式逐字节复核，确认后才复用索引；否则追加新池条目。 */
    private fun resolveIndex(
        fingerprint: Fingerprint,
        candidate: InnerHeader.BinaryItem,
        dedupList: MutableList<InnerHeader.BinaryItem>,
        fingerprintIndex: HashMap<Fingerprint, Int>
    ): Int {
        val existingIndex = fingerprintIndex[fingerprint]
        if (existingIndex != null && contentEquals(dedupList[existingIndex], candidate)) {
            return existingIndex
        }
        val index = dedupList.size
        dedupList.add(candidate)
        fingerprintIndex[fingerprint] = index
        return index
    }

    /** 流式逐字节比对（不整份物化），作为哈希碰撞的最终裁决。 */
    private fun contentEquals(a: InnerHeader.BinaryItem, b: InnerHeader.BinaryItem): Boolean {
        if (a === b) return true
        if (a.flags != b.flags || a.size != b.size) return false
        a.openStream().use { left ->
            b.openStream().use { right ->
                return streamsEqual(left, right)
            }
        }
    }

    private fun streamsEqual(left: InputStream, right: InputStream): Boolean {
        val leftBuffer = ByteArray(COMPARE_BUFFER_BYTES)
        val rightBuffer = ByteArray(COMPARE_BUFFER_BYTES)
        while (true) {
            val leftRead = left.read(leftBuffer)
            val rightRead = right.read(rightBuffer)
            if (leftRead != rightRead) return false
            if (leftRead < 0) return true
            for (i in 0 until leftRead) {
                if (leftBuffer[i] != rightBuffer[i]) return false
            }
        }
    }

    /** 流式比对缓冲（8 KiB）。 */
    private const val COMPARE_BUFFER_BYTES = 8 * 1024
}
