package com.keepasskey.database.xml

import com.keepasskey.database.file.InnerHeader

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
 * 取值依据（`ISSUE-P2-311` AC① 起）：**由写侧单字段上限同源派生**——
 * `MAX_ATTACHMENT_BYTES = InnerHeader.MAX_INNER_FIELD_BYTES − 1`（BINARY 字段 = 1 字节 flags 前缀 + 内容），
 * 使「UI 放行的最大附件」写出的恰好是读侧能接受的最长合法字段，写读两侧不可能互斥。
 * 原值为独立字面量 64 MiB（与派生值仅差 1 字节）：选择器以 `>` 判上界使**恰好 64 MiB** 的附件放行，
 * 写出字段长 `64 MiB + 1` 超过读侧单字段上限（64 MiB，`>` 判），下次打开整库判损坏——
 * 写读两侧对同一常量口径互斥正是本条缺陷的根因，故取值关系本身必须由派生保证。
 * （堆界论证与旧值 64 MiB 一致：64 MiB − 1 同样远低于低端机堆界，见 [AttachmentBudget] 类 KDoc。）
 */
object AttachmentSizeLimits {

    /**
     * 附件尺寸上界 = `MAX_INNER_FIELD_BYTES − 1`（三个约束共用的唯一定义处；
     * 由写侧单字段上限派生，非独立字面量）。
     */
    val MAX_ATTACHMENT_BYTES: Long = InnerHeader.MAX_INNER_FIELD_BYTES.toLong() - 1
}
