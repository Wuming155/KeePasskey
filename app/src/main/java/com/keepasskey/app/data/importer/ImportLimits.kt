package com.keepasskey.app.data.importer

/**
 * ISSUE-P3-19：明文导入框架的防御性上限与解析常量集中定义（工程规则：禁止魔法数字）。
 *
 * 全部上限均为 **fail-closed** 语义：超限即拒绝，绝不产生「部分落库 + 静默成功」。
 * 上限值本身与 UI 文案无关，属安全闸门参数。
 */
object ImportLimits {

    /** 单次导入条目数上限；超限整批拒绝（[ImportLimitExceededException]）。 */
    const val MAX_ENTRIES_PER_IMPORT: Int = 10_000

    /** 单个导入文件的字节上限（64 MiB）：明文导出文件量级远低于此，超限即拒绝。 */
    const val MAX_IMPORT_BYTES: Int = 64 * 1024 * 1024

    /** 单个字段的字符上限（1 Mi 字符）：防单字段（畸形引号未闭合）撑爆内存。 */
    const val MAX_FIELD_CHARS: Int = 1 shl 20

    /** 报告内保留的警告条数上限；超出部分折叠为一条 [ImportWarningReason.WARNINGS_TRUNCATED]。 */
    const val MAX_WARNINGS: Int = 64

    /** XML 元素嵌套深度上限（与库内 `KdbxXmlParser.MAX_XML_DEPTH` 同界，防解析炸弹）。 */
    const val MAX_XML_DEPTH: Int = 64

    /** 落库时允许匹配/创建的分组链最大层级；更深的尾部层级被丢弃（条目仍安全落库）。 */
    const val MAX_GROUP_PATH_DEPTH: Int = 32
}
