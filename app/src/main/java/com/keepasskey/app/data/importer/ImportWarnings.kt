package com.keepasskey.app.data.importer

/**
 * 导入警告的**稳定技术编码**（解析器 ↔ 落库编排器 ↔ UI 三方共享的枚举）。
 *
 * [ImportWarning.reason] 按既定契约承载本枚举的 [code]（字符串），UI 层经 [fromCode]
 * 反查后映射到 `strings.xml` 文案。因此本枚举**严禁**承载任何条目字段明文或面向用户的文案，
 * 新增编码必须同步在 `strings.xml` / `values-en/strings.xml` 登记对应资源。
 */
enum class ImportWarningReason(val code: String) {
    /** 源中出现空记录（无任何可用字段）。 */
    EMPTY_ROW("EMPTY_ROW"),

    /** 记录/条目缺少全部必要字段（标题、用户名、密码均为空），已跳过。 */
    MISSING_REQUIRED_VALUE("MISSING_REQUIRED_VALUE"),

    /** CSV 行列数与表头不一致（多出的列被忽略，缺失的列按空值处理）。 */
    COLUMN_COUNT_MISMATCH("COLUMN_COUNT_MISMATCH"),

    /** 文件含非法 UTF-8 字节序列，已按替换字符（U+FFFD）处理。 */
    INVALID_UTF8("INVALID_UTF8"),

    /** 源中该字段为受保护值（`Protected="True"`）且本次导入无解密随机流，字段被跳过。 */
    PROTECTED_VALUE_SKIPPED("PROTECTED_VALUE_SKIPPED"),

    /** 源含历史修订（`<History>`）子树，因导入契约无历史字段而未导入。 */
    HISTORY_IGNORED("HISTORY_IGNORED"),

    /** 源含额外自定义字段，因导入契约无自定义字段而未导入。 */
    CUSTOM_FIELD_DROPPED("CUSTOM_FIELD_DROPPED"),

    /** 同分组内已存在同名同用户名条目，按冲突策略跳过（未覆盖既有密码）。 */
    DUPLICATE_ENTRY_SKIPPED("DUPLICATE_ENTRY_SKIPPED"),

    /** 分组创建失败，该条目已回退至根分组（数据未丢失）。 */
    GROUP_CREATE_FAILED("GROUP_CREATE_FAILED"),

    /** 条目落库或回收站归位失败。 */
    ENTRY_SAVE_FAILED("ENTRY_SAVE_FAILED"),

    /** 警告过多，剩余条目已折叠（原因为本编码）。 */
    WARNINGS_TRUNCATED("WARNINGS_TRUNCATED"),

    // ===== ISSUE-P3-19 集成补齐（编排者）：解析器子批（Bitwarden JSON / 1PUX）的跳过与降级面 =====
    // 补齐动因：`ImportReportDialog.warningReasonRes` 是对本枚举的**穷尽 `when`**，
    // 未登记的编码经 `fromCode` 反查为 null → UI 一律显示「未知警告」，
    // 用户看不到「为何这些条目没被导入」。故按解析器子批上报的清单就地登记。

    /** Bitwarden：源中条目 `type != 1`（非登录类型，如安全笔记/银行卡/身份），已跳过。 */
    NON_LOGIN_ITEM_SKIPPED("NON_LOGIN_ITEM_SKIPPED"),

    /** 1PUX：条目 `categoryUuid` 非登录类别（LOGIN = "001"），已跳过。 */
    NON_LOGIN_CATEGORY_SKIPPED("NON_LOGIN_CATEGORY_SKIPPED"),

    /** Bitwarden：`folderId` 在 `folders` 表中未匹配，条目落至根分组。 */
    FOLDER_NOT_FOUND("FOLDER_NOT_FOUND"),

    /** 源中条目节点不是对象（结构非法），已跳过。 */
    ITEM_NOT_OBJECT("ITEM_NOT_OBJECT"),

    /** 可选字段类型非法（例如 `uris` 不是数组），已按空值处理。 */
    OPTIONAL_FIELD_INVALID("OPTIONAL_FIELD_INVALID"),

    /** 条目标题为空/空白，将使用默认名（`R.string.import_untitled_entry`）。 */
    TITLE_MISSING("TITLE_MISSING"),

    /** 1PUX：账户名与保险库名均为空，条目落至根分组。 */
    GROUP_NAME_MISSING("GROUP_NAME_MISSING");

    companion object {
        /** 按稳定编码反查；未知编码返回 null（UI 层回退为「未知警告」资源文案）。 */
        fun fromCode(code: String): ImportWarningReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 导入诊断的机器可读定位符常量。
 *
 * 定位符**不是文案**：UI 层按 `strings.xml` 模板原样嵌入展示，因此严禁在此写入
 * 条目字段明文（标题/用户名/密码），仅允许文档级、记录序号级或分组路径级定位。
 */
object ImportWarningLocation {

    /** 整份文档级别（编码、历史修订、自定义字段等全局问题）。 */
    const val DOCUMENT: String = "doc"

    /** CSV 记录级别：`row:3`。 */
    fun row(recordIndex: Int): String = "row:$recordIndex"

    /** 落库批次内条目序号级别（1 基）：`entry:12`。 */
    fun entry(ordinal: Int): String = "entry:$ordinal"

    /** 分组路径级别：`path:Work/Email`；空路径回退文档级。 */
    fun groupPath(path: List<String>): String =
        if (path.isEmpty()) DOCUMENT else "path:" + path.joinToString(PATH_SEPARATOR)

    private const val PATH_SEPARATOR = "/"
}

/**
 * 有上限的警告收集器。
 *
 * 存在的理由：脏数据文件可能逐行产生警告（1 万行 → 1 万条警告），无上限收集既撑爆报告
 * 也让 UI 无法渲染；超出 [max] 后只累加计数，并在最终快照尾部追加一条
 * [ImportWarningReason.WARNINGS_TRUNCATED] 汇总项，**不静默丢弃**。
 */
internal class ImportWarningCollector(private val max: Int = ImportLimits.MAX_WARNINGS) {

    private val collected = mutableListOf<ImportWarning>()
    private var overflowCount = 0

    fun add(location: String, reason: ImportWarningReason) {
        if (collected.size < max) {
            collected += ImportWarning(location = location, reason = reason.code)
        } else {
            overflowCount++
        }
    }

    /** 生成只读快照；存在溢出时追加汇总警告（可重复调用）。 */
    fun snapshot(): List<ImportWarning> {
        val snapshot = collected.toList()
        if (overflowCount == 0) return snapshot
        return snapshot + ImportWarning(
            location = ImportWarningLocation.DOCUMENT,
            reason = ImportWarningReason.WARNINGS_TRUNCATED.code
        )
    }
}
