package com.keepasskey.app.data.importer

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 导入失败的**用户可读归类**（非敏感）。
 *
 * 设计理由：跨模块结果类型 [com.keepasskey.core.result.KdbxResult.Failure] 的
 * `userMessage` 不能承载裸异常 message（见 ISSUE-P1-10：外部输入可能携带路径/主机等标识），
 * 而数据层又不得硬编码中文文案。故此处以 `@StringRes` 收敛分类，
 * 框架各层统一返回 `Failure(error, userMessage = null)`，由 UI 层经 [classify] 取资源文案。
 */
enum class ImportFailureReason(@StringRes val messageRes: Int) {
    /** 密码库未解锁 / 会话不可写。 */
    LOCKED(R.string.import_error_locked),

    /** 该数据源的解析器尚未注册（如另一批次负责的 Bitwarden / 1PUX 未安装）。 */
    SOURCE_UNAVAILABLE(R.string.import_error_source_unavailable),

    /** 所选文件扩展名与数据源不匹配。 */
    UNSUPPORTED_FILE(R.string.import_error_unsupported_file),

    /** 非 UTF-8 编码或含非法字节序列（XML 严格拒绝 / CSV 替换后告警）。 */
    ENCODING(R.string.import_error_encoding),

    /** CSV 表头缺少必需列。 */
    MISSING_REQUIRED_COLUMN(R.string.import_error_missing_column),

    /** 超出防御性上限（条目数 / 文件体积 / 字段长度）。 */
    LIMIT_EXCEEDED(R.string.import_error_limit),

    /** 文件结构非法或已损坏（根元素不符、XML/CSV 语法错误）。 */
    MALFORMED(R.string.import_error_malformed),

    /** 读取所选文件失败（SAF 通道异常）。 */
    IO(R.string.import_error_io),

    /** 落库失败（写入内存树或落盘失败）。 */
    PERSIST(R.string.import_error_persist);

    companion object {
        /**
         * 由异常类型归类；无法识别者归入 [MALFORMED]（fail-closed，不谎报成功）。
         *
         * 顺序敏感：子类型必须先于 [ImportFormatException] 判定。
         */
        fun classify(error: Throwable): ImportFailureReason = when (error) {
            is ImportVaultLockedException -> LOCKED
            is ImportEncodingException -> ENCODING
            is ImportRequiredColumnMissingException -> MISSING_REQUIRED_COLUMN
            is ImportLimitExceededException -> LIMIT_EXCEEDED
            is ImportPersistException -> PERSIST
            is ImportFormatException -> MALFORMED
            is java.io.IOException -> IO
            else -> MALFORMED
        }
    }
}
