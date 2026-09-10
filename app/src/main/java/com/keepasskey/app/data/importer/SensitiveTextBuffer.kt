package com.keepasskey.app.data.importer

/**
 * 可确定性擦除的敏感文本缓冲（`CharArray` 承载，绝不落 `String`）。
 *
 * 使用场景：SAX 的 `characters(ch, start, length)` 直接给出字符数组，密码等敏感字段可在
 * **第一现场**写入本缓冲，全程不产生 `String`；映射完成后经 [toCharArrayAndClear]
 * 交出独占副本并立即擦除工作缓冲，随后由 [ImportedEntry] / `saveEntry` 的擦除契约接管。
 *
 * 扩容时旧缓冲会被显式清零后再丢弃，避免明文残留在被 GC 回收前的堆内存中。
 */
internal class SensitiveTextBuffer(
    initialCapacity: Int = INITIAL_CAPACITY,
    private val maxChars: Int = ImportLimits.MAX_FIELD_CHARS
) {

    private var buffer = CharArray(initialCapacity.coerceAtLeast(MIN_CAPACITY))
    private var length = 0

    /** 追加一段字符；超长即 fail-closed（[ImportLimitExceededException]）。 */
    fun append(chars: CharArray, start: Int, count: Int) {
        if (length + count > maxChars) throw ImportLimitExceededException(OVER_FIELD_LIMIT)
        ensureCapacity(length + count)
        chars.copyInto(buffer, destinationOffset = length, startIndex = start, endIndex = start + count)
        length += count
    }

    /** 交出独占副本并清空工作缓冲（归调用方所有）。 */
    fun toCharArrayAndClear(): CharArray {
        val out = buffer.copyOf(length)
        clear()
        return out
    }

    /** 显式清零工作缓冲；可重复调用。 */
    fun clear() {
        buffer.fill(NUL_CHAR)
        length = 0
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var newSize = buffer.size
        while (newSize < needed) newSize = newSize shl 1
        val grown = CharArray(newSize)
        buffer.copyInto(grown, destinationOffset = 0, startIndex = 0, endIndex = length)
        buffer.fill(NUL_CHAR)
        buffer = grown
    }

    private companion object {
        const val MIN_CAPACITY = 16
        const val INITIAL_CAPACITY = 64
        const val NUL_CHAR = '\u0000'
        const val OVER_FIELD_LIMIT = "单个字段超出导入长度上限"
    }
}
