package com.keepasskey.app.data.importer

import com.keepasskey.core.result.KdbxResult

/**
 * 解析阶段的统一安全闸门与失败归一（XML / CSV 解析器共用）。
 *
 * 单一职责：① 上限校验；② 把任意解析异常归一为非敏感的 [KdbxResult.Failure]，
 * 并保证**已产出的条目敏感数组被清零**（异常/提前返回路径同样覆盖）。
 */
internal object ImportParseGuard {

    /** 文件体积闸门：超限 fail-closed。 */
    fun requireFileSize(byteCount: Int) {
        if (byteCount > ImportLimits.MAX_IMPORT_BYTES) throw ImportLimitExceededException(OVER_FILE_SIZE)
    }

    /** 条目数闸门：每产出一条即校验，超限立即中止（不继续吃内存）。 */
    fun requireEntryCapacity(producedCount: Int) {
        if (producedCount > ImportLimits.MAX_ENTRIES_PER_IMPORT) {
            throw ImportLimitExceededException(OVER_ENTRY_COUNT)
        }
    }

    /**
     * 归一为失败结果，并清零 [produced] 中全部条目的敏感数组。
     *
     * 说明：`userMessage` 刻意留空——由 [ImportFailureReason.classify] 在 UI 边界映射资源文案，
     * 避免数据层硬编码文案，也避免裸异常 message 上浮。
     */
    fun <T> failure(error: Throwable, produced: List<ImportedEntry>): KdbxResult<T> {
        produced.forEach { it.clear() }
        return KdbxResult.Failure(error)
    }

    /**
     * 剥离 SAX/解析器包装，定位真正的导入异常。
     *
     * SAX 实现可能把 handler 抛出的运行时异常包进 `SAXException`，若不剥离，
     * [ImportFailureReason.classify] 会把「超限/编码」误判为「格式非法」。
     * 沿 cause 链最多追溯 [MAX_CAUSE_DEPTH] 层（防环）。
     */
    fun unwrap(error: Throwable): Throwable {
        var current = error
        repeat(MAX_CAUSE_DEPTH) {
            val cause = current.cause ?: return current
            if (cause === current) return current
            if (cause is ImportFormatException) return cause
            current = cause
        }
        return current
    }

    private const val MAX_CAUSE_DEPTH = 8
    private const val OVER_FILE_SIZE = "导入文件超出体积上限"
    private const val OVER_ENTRY_COUNT = "导入条目数超出上限"
}
