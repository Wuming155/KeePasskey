package com.keepasskey.database.xml

/**
 * 附件尺寸上界的**单一数字来源**（`ISSUE-P3-295` AC③）。
 *
 * 该量级的三个约束共用同一取值，**禁两套数字**：
 * 1. 解析侧内联附件（含解压产物）的本次解析累计字节上限（`AttachmentBudget.MAX_INLINE_MATERIALIZED_BYTES`，
 *    由本常量派生）；
 * 2. 解析侧单个池条目被反复引用而允许物化的总字节上限
 *    （`AttachmentBudget.MAX_POOL_ITEM_MATERIALIZED_BYTES`）；
 * 3. **用户添加附件时的单文件上界**（[MAX_ATTACHMENT_BYTES]）——超限在读取字节**之前**即拒，
 *    避免把整份超大盘外文件读进内存后才判（那正是 OOM 发生的时刻）。
 *
 * 取值依据（与解析侧同一论证）：远低于低端机堆界（真机实测 192 MiB 堆），
 * 使单节点峰值（输出缓冲 + `toByteArray()` 副本）落在堆界之内。详见
 * [AttachmentBudget] 类 KDoc 的「内联累计上界取值」一节。
 */
object AttachmentSizeLimits {

    /** 附件尺寸上界：64 MiB（三个约束共用的唯一字面量）。 */
    const val MAX_ATTACHMENT_BYTES: Long = 64L * 1024 * 1024
}
