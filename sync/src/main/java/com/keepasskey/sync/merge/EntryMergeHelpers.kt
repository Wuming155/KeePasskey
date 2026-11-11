package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString

/**
 * 条目合并附属策略（附件 / 父组归属 / 字段差异）——自 [KdbxEntryMerger] 拆出（行数门禁）。
 */
internal object EntryMergeHelpers {

    /**
     * 附件合并：按**名称**做三方存在性 / 内容裁决（ENG-20261111-04）。
     *
     * 逐名称分派，绝不以基线附件兜底——拆分前的「基线 + 远端 + 本地」并集会让两侧一致删除的
     * 附件复活，并被默认自动合并路径序列化上传：
     * - 双方均无该名称（含双方一致删除）→ 不保留；
     * - 单侧删除、对侧相对基线内容未改 → 删除生效；
     * - 删除 vs 修改 → 保留修改方；
     * - 单侧新增 → 保留；
     * - 双侧同名内容分歧 → 沿用 [KdbxEntryMerger] 的字段分歧口径（远端最后修改时间较新取远端，
     *   否则取本地）。附件不在 [KdbxMerger] 的冲突键词汇表内，故不产生冲突项。
     *
     * 名称顺序沿用「基线 → 远端 → 本地」首次出现序，只为输出稳定，不表达任何存在性偏好。
     */
    fun mergeAttachments(
        base: KdbxEntry?,
        local: KdbxEntry,
        remote: KdbxEntry
    ): List<KdbxAttachment> {
        val baseByName = base?.attachments.orEmpty().associateBy { it.name }
        val localByName = local.attachments.associateBy { it.name }
        val remoteByName = remote.attachments.associateBy { it.name }
        val remoteNewer = remote.times.lastModificationTime.isAfter(local.times.lastModificationTime)

        val names = LinkedHashSet<String>()
        names.addAll(baseByName.keys)
        names.addAll(remoteByName.keys)
        names.addAll(localByName.keys)

        return names.mapNotNull { name ->
            val b = baseByName[name]
            val l = localByName[name]
            val r = remoteByName[name]
            when {
                // 双方均无该名称：一致删除 / 一致未新增
                l == null && r == null -> null
                // 本地缺失：远端新增即保留；远端相对基线未改则删除生效；改过则删除 vs 修改取修改方
                l == null -> r?.takeIf { b == null || !sameAttachmentContent(b, it) }
                r == null -> l?.takeIf { b == null || !sameAttachmentContent(b, it) }
                sameAttachmentContent(l, r) -> l
                // 基线无此名称：双侧各自新增同名附件，内容分歧按 LWW 裁决
                b == null -> if (remoteNewer) r else l
                sameAttachmentContent(l, b) -> r
                sameAttachmentContent(r, b) -> l
                else -> if (remoteNewer) r else l
            }
        }
    }

    /**
     * 附件内容等同，忽略 `refIndex`：池索引由保存时的池去重按遍历序重排
     * （`KdbxBinaryDeduplicator`），同一份字节在不同库文件里索引可以不同，不构成内容修改。
     * 其余判据（名称 / 保护标志 / 大小 / 字节）仍交 [KdbxAttachment.equals] 裁决，不另写一份。
     */
    private fun sameAttachmentContent(a: KdbxAttachment, b: KdbxAttachment): Boolean {
        if (a == b) return true
        if (a.name != b.name) return false
        return withoutPoolIndex(a) == withoutPoolIndex(b)
    }

    /** 比较视图：`refIndex` 置固定占位值；只用于 [KdbxAttachment.equals]，绝不对其 `close()`（内联字节是借用视图）。 */
    private fun withoutPoolIndex(attachment: KdbxAttachment): KdbxAttachment = KdbxAttachment(
        name = attachment.name,
        refIndex = 0,
        isProtected = attachment.isProtected,
        data = attachment.inlineBytes(),
        source = attachment.binarySource()
    )

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
