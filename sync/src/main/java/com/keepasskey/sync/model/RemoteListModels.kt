package com.keepasskey.sync.model

/**
 * 远端目录浏览：目录下的一项（文件或子目录）。
 *
 * 全部字段非敏感（路径 / 名称 / 类型 / 大小 / 时间），可直接进 UI 状态流。
 */
data class RemoteListEntry(
    /** 相对远端根的路径（以 `/` 开头，不含服务器 URL）。 */
    val path: String,
    /** 显示名（路径末段）。 */
    val name: String,
    /** 是否为目录（collection / 前缀「目录」）。 */
    val isDirectory: Boolean,
    /** 文件大小（字节）；目录恒 0。 */
    val contentLength: Long = 0L,
    /** 最后修改时间（毫秒）；未知时为 0。 */
    val lastModifiedMillis: Long = 0L
)

/**
 * 远端目录浏览：一页结果。
 *
 * **保守降级口径（ISSUE-P3-387 AC；keepass2android 教训）**：列举结果**不可尽信**。
 * 实现侧对失败 / 解析落空一律返回 [Result.failure]，**禁止**把失败伪装成「空目录」。
 * [truncated] 为 true 时 [nextCursor] 非空，调用方可继续拉取下一页。
 */
data class RemoteListPage(
    /** 本页条目（目录优先、名称升序的稳定顺序由实现保证）。 */
    val entries: List<RemoteListEntry>,
    /** 下一页游标；null = 已是最后一页 / 本协议单次全量返回。 */
    val nextCursor: String? = null,
    /** 是否被分页/上限截断（true 时 UI 应展示「加载更多」而非当作完整目录）。 */
    val truncated: Boolean = false
)
