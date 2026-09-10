package com.keepasskey.app.data.importer

/**
 * 冲突/重复处理策略（落库阶段）。
 *
 * 判定键：[EntryKey] = （目标分组 id，去空白标题，去空白用户名）三元组精确匹配
 * （区分大小写）。不比对密码与 URL——密码比对会把明文拉进比较逻辑，且同一站点改密后
 * 不应被视为「不同条目」。
 */
enum class ImportConflictPolicy {

    /**
     * **默认策略**：同分组同标题同用户名的既有条目已存在时**跳过该条**（计入 skipped + 警告），
     * 绝不覆盖既有密码。
     *
     * 选它作默认的理由（密码管理器语境）：
     * 1. 覆盖是单向不可逆的数据损失——被覆盖的可能是用户当前仍在用的新密码；
     * 2. 跳过同时满足两个目标：既不产生重复条目（重复导入幂等），也不破坏既有数据；
     * 3. 被丢弃的导入数据不是「静默」的：报告显式给出 `skipped` 计数与逐条警告。
     */
    SKIP_EXISTING,

    /** 一律新建：不比对既有条目，重复导入会产生重复条目（最保守，绝不触碰既有数据）。 */
    CREATE_DUPLICATE,

    /** 显式选择：以导入源的密码覆盖既有条目密码（走 `saveEntry` 的 CharArray 提交与擦除契约）。 */
    UPDATE_PASSWORD
}

/**
 * 落库阶段的结果报告（**全部字段非敏感**，可直接进 UI 状态流）。
 *
 * 计数恒等式：`imported + updated + skipped + failed == parsed`；
 * 解析阶段被跳过的源记录另行由 [sourceSkipped] 表达（那部分从未进入落库队列）。
 */
data class ImportOutcome(
    /** 来源数据源。 */
    val source: ImportSource,
    /** 解析器输出、进入落库队列的条目总数。 */
    val parsed: Int,
    /** 解析阶段即被跳过的源记录数（缺必要字段），来自 `ImportReport.skipped`。 */
    val sourceSkipped: Int,
    /** 新建并入树的条目数。 */
    val imported: Int,
    /** 按 [ImportConflictPolicy.UPDATE_PASSWORD] 覆盖密码的条目数。 */
    val updated: Int,
    /** 因冲突策略跳过（未改动既有条目）的条目数。 */
    val skipped: Int,
    /** 落库失败的条目数（已逐条记入 [warnings]）。 */
    val failed: Int,
    /** [imported] 中已被归位至回收站的条目数（源数据里的已删除条目）。 */
    val movedToRecycleBin: Int,
    /** 全程警告快照（技术定位符 + 稳定编码，见 [ImportWarningReason]）。 */
    val warnings: List<ImportWarning>
) {
    /** 真正写入库内的条目数。 */
    val written: Int get() = imported + updated

    /** 解析器零产出（UI 据此给出「未解析出条目」的明确提示，而非静默成功）。 */
    val isEmpty: Boolean get() = parsed == 0 && sourceSkipped == 0
}
