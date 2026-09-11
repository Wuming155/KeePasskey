package com.keepasskey.core.otp

/**
 * ISSUE-P3-49：HOTP 计数器在 `otpauth://` URI 原文中的递增工具。
 *
 * KeePass / KeePassXC 将 HOTP 计数器持久化在 `otp` 字段的
 * `otpauth://hotp/...&counter=N` 中；每次取码必须把计数器推进 1 并写回，
 * 否则同一计数器会重复出码（RFC 4226 语义错误）。
 *
 * **全程字符语义**：本工具在调用方提供的 `CharArray` 上就地扫描 / 拼接，仅把**数值子串**
 * 转为 `Long`；`secret=` 段（种子）始终只存在于调用方字符数组内，
 * 既不物化为不可擦除的 `String`，也不触发任何编码转换。
 * 返回**全新数组**，归调用方所有；调用方用毕须显式 `fill('0')`。
 */
object HotpCounterSupport {

    private const val OTPAUTH_PREFIX = "otpauth://"
    private const val COUNTER_KEY = "counter="

    private const val CHAR_AMPERSAND = '&'
    private const val CHAR_QUESTION = '?'
    private const val CHAR_0 = '0'
    private const val CHAR_9 = '9'

    /**
     * 把 `otpauth://` URI 中的 `counter` 参数值递增 1，返回全新 `CharArray`。
     *
     * - 已存在 `counter=N`：替换为 `counter=N+1`（其余查询参数与顺序不变）；
     * - 不存在该参数：在查询串末尾追加 `&counter=1`（无查询串则追加 `?counter=1`）；
     * - 非法输入（空 / 非 `otpauth://` 前缀 / 计数器非纯数字 / 已达 `Long.MAX_VALUE`）返回 **null**，
     *   调用方须据此 fail-closed（绝不落库一个语义不明的计数器）。
     */
    fun incrementCounter(uri: CharArray): CharArray? {
        if (uri.isEmpty()) return null
        if (!startsWithIgnoreCase(uri, OTPAUTH_PREFIX)) return null
        // `counter=` 只在查询串内有意义：仅在该区间检索，避免匹配到标签 / 路径段
        val queryStart = indexOfChar(uri, CHAR_QUESTION, 0)
        val keyIndex = if (queryStart in uri.indices) {
            indexOfIgnoreCase(uri, COUNTER_KEY, start = queryStart + 1, end = uri.size)
        } else {
            -1
        }
        return if (keyIndex >= 0) {
            replaceCounterValue(uri, keyIndex + COUNTER_KEY.length)
        } else {
            appendCounter(uri, queryStart in uri.indices)
        }
    }

    private fun replaceCounterValue(uri: CharArray, valueStart: Int): CharArray? {
        var valueEnd = valueStart
        while (valueEnd < uri.size && uri[valueEnd] != CHAR_AMPERSAND) valueEnd++
        if (valueEnd == valueStart) return null
        for (i in valueStart until valueEnd) {
            if (uri[i] < CHAR_0 || uri[i] > CHAR_9) return null
        }
        val current = String(uri, valueStart, valueEnd - valueStart).toLongOrNull() ?: return null
        if (current >= Long.MAX_VALUE) return null
        val next = (current + 1).toString()
        val removed = valueEnd - valueStart
        val result = CharArray(uri.size - removed + next.length)
        uri.copyInto(result, 0, 0, valueStart)
        for (k in next.indices) result[valueStart + k] = next[k]
        uri.copyInto(result, valueStart + next.length, valueEnd, uri.size)
        return result
    }

    private fun appendCounter(uri: CharArray, hasQuery: Boolean): CharArray {
        val suffix = if (hasQuery) "&counter=1" else "?counter=1"
        val result = CharArray(uri.size + suffix.length)
        uri.copyInto(result, 0, 0, uri.size)
        for (k in suffix.indices) result[uri.size + k] = suffix[k]
        return result
    }

    private fun startsWithIgnoreCase(chars: CharArray, prefix: String): Boolean {
        if (chars.size < prefix.length) return false
        for (i in prefix.indices) {
            if (lower(chars[i]) != lower(prefix[i])) return false
        }
        return true
    }

    private fun indexOfChar(chars: CharArray, target: Char, from: Int): Int {
        for (i in from until chars.size) if (chars[i] == target) return i
        return -1
    }

    private fun indexOfIgnoreCase(chars: CharArray, needle: String, start: Int, end: Int): Int {
        if (needle.isEmpty() || end - start < needle.length) return -1
        outer@ for (i in start..(end - needle.length)) {
            for (j in needle.indices) {
                if (lower(chars[i + j]) != lower(needle[j])) continue@outer
            }
            return i
        }
        return -1
    }

    private fun lower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c
}
