package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult

/**
 * ISSUE-P3-19 明文导入框架：**跨解析器共享的稳定契约**。
 *
 * 本文件由框架子批（框架 + KeePass XML + 浏览器 CSV）与解析器子批
 * （Bitwarden JSON + 1Password 1PUX）共同依赖，**双方均不得修改本文件的既有声明**
 * （允许在各自文件内新增类型，不允许改动这里已定型的签名与语义）。
 *
 * 敏感数据纪律（`.codebuddy/rules/engineering-rules.md`）：
 * - [ImportedEntry.password] / [ImportedEntry.totpSecret] 一律以 [CharArray] 承载，
 *   调用方（落库编排器）在 `finally` 中显式清零，**严禁落到 String / 日志 / 异常消息**；
 * - 解析器内部不可避免的「整份文本解码为 String」属已知残余面，须在实现处以注释如实登记，
 *   并保证密码在字段映射的**第一现场**即转为 [CharArray]，不参与任何拼接、比较、日志。
 */

/**
 * 导入数据源：与设置页「导入数据源」的 4 个选项一一对应。
 * 使用 [id] 作为稳定持久化标识（禁止用裸 String 传状态）。
 */
enum class ImportSource(val id: String, val displayName: String) {
    KEEPASS_XML("keepass_xml", "KeePass XML"),
    BITWARDEN_JSON("bitwarden_json", "Bitwarden JSON"),
    BROWSER_CSV("browser_csv", "Browser CSV"),
    ONEPASSWORD_1PUX("onepassword_1pux", "1Password 1PUX");

    companion object {
        /** 按稳定 id 反查；未知 id 返回 null（fail-closed，调用方自行降级）。 */
        fun fromId(id: String): ImportSource? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 解析器输出的一条待导入条目（**尚未落库**）。
 *
 * 刻意不使用 `data class`：[password] / [totpSecret] 为可变数组，
 * 生成的 `equals` / `hashCode` / `toString` 会破坏擦除语义并可能泄露明文。
 */
class ImportedEntry(
    /** 条目标题（非敏感，缺省时由落库编排器回退为「未命名条目」）。 */
    val title: String,
    /** 用户名（非敏感）。 */
    val username: String,
    /** 密码明文——**借用语义**，由落库编排器负责用毕清零。 */
    val password: CharArray,
    /** 站点地址；无则空串。 */
    val url: String,
    /** 备注原文（非敏感）；无则空串。 */
    val notes: String,
    /** 分组路径（自顶向下，不含根分组名）；空列表表示落至根分组。 */
    val groupPath: List<String>,
    /** TOTP 配置原文（otpauth URI 或 Base32 种子）；无则 null。落库编排器负责清零。 */
    val totpSecret: CharArray? = null,
    /** 是否落至回收站（源数据已删除条目）；默认 false。 */
    val deleted: Boolean = false
) {
    /** 显式清零本条目的全部敏感序列；可重复调用。 */
    fun clear() {
        password.fill('\u0000')
        totpSecret?.fill('\u0000')
    }
}

/**
 * 非敏感的导入诊断信息（用于结果报告 UI 与单测断言）。
 * [detail] 严禁包含条目字段明文。
 */
data class ImportWarning(
    /** 源数据内的定位描述（行号 / 索引 / 路径），用于用户自查。 */
    val location: String,
    /** 人类可读原因（中英双语由 UI 层经 strings.xml 组装，此处保留技术描述）。 */
    val reason: String
)

/**
 * 一次解析的统计报告（**全部字段非敏感**）。
 * [parsed] 为成功映射的条目数，[skipped] 为因缺失必要字段或格式非法被跳过的条目数。
 */
data class ImportReport(
    val source: ImportSource,
    val parsed: Int,
    val skipped: Int,
    val warnings: List<ImportWarning> = emptyList()
) {
    /** 报告是否「零产出」——UI 据此给出明确的空结果提示，而不是静默成功。 */
    val isEmpty: Boolean get() = parsed == 0
}

/** 解析成功后的载荷：条目集合 + 统计报告。 */
data class ImportBatch(
    val report: ImportReport,
    val entries: List<ImportedEntry>
)

/**
 * 单源解析器（策略模式，对齐工程规则「开闭原则」）。
 * 新增数据源只加实现类，不改调用方。
 */
interface EntryImporter {
    /** 本解析器负责的数据源。 */
    val source: ImportSource

    /** 受支持的文件扩展名（小写、不含点），供 UI 选择器过滤与安全校验。 */
    val supportedExtensions: Set<String>

    /**
     * 解析 [bytes]（[fileName] 仅供扩展名/来源提示，不用于 IO）。
     *
     * **fail-closed 契约**：任何结构性非法输入（签名不符、JSON/ZIP 损坏、必填列缺失、
     * 超大条目数）一律返回 [KdbxResult.Failure]，**不得**返回部分成功而不声明
     * （部分跳过必须经 [ImportReport.skipped] + [ImportWarning] 显式声明）。
     * 禁止抛出裸异常越过本接口。
     */
    suspend fun parse(bytes: ByteArray, fileName: String): KdbxResult<ImportBatch>
}

/** 导入过程中的结构性错误（非敏感消息，严禁携带字段明文）。 */
open class ImportFormatException(message: String) : Exception(message)

/** 输入超出防御性上限（条目数 / 单文件体积 / 解压后体积）。 */
class ImportLimitExceededException(message: String) : ImportFormatException(message)
