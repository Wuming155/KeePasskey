package com.keepasskey.app.passkey

/**
 * ASCII 空格码位（`ISSUE-P3-188` 第 4 目 §168 收敛：原先三处各写一份裸 `0x20`）。
 * 本包内两种用法共用同一个值，语义由比较符决定，不再各自造名：
 * - `< ASCII_SPACE`：**控制字符**（JSON 字符串内必须转义，见 [SimpleJson] 与
 *   [CallingOriginResolver] 的转义面）；
 * - `<= ASCII_SPACE`：**可剔除的 ASCII 空白**（Base64 解码前的两侧修剪，见
 *   [PasskeyAssertionActivity]）。
 */
internal const val ASCII_SPACE = 0x20

/**
 * 极简 JSON 解析器（**零依赖**，可 JVM 单测）。
 *
 * ## 为什么不用 `org.json.JSONObject`
 *
 * 1. **宿主单测不可用**：`org.json` 在 JVM 单测里是未实现的 Android 桩（调用即抛
 *    `Method put not mocked`），而通行密钥解析逻辑是**安全关键面**，必须有可执行的单测；
 * 2. **语义可控**：本解析器只做「读到什么就是什么」的严格解析——非法输入一律
 *    抛 [IllegalArgumentException]（由调用方 fail-closed 回落到安全缺省），
 *    不做 `JSONObject` 那样的隐式字符串强转（那会让 `{"id": 123}` 变成 `"123"`）。
 *
 * ## 边界
 *
 * - 支持：对象 / 数组 / 字符串（含 `\uXXXX` 与常用转义）/ 数字 / `true` / `false` / `null`；
 * - 数字一律解析为 [Double]（调用方按需取整，避免整型溢出差异）；
 * - **深度上限** [MAX_DEPTH]：超限抛异常，杜绝对抗性输入（深嵌套）触发栈溢出；
 * - 解析后不保留原文，不保留任何未消费的尾部内容（尾随非空白字符即判非法）。
 */
internal object SimpleJson {

    /** 嵌套深度上限（覆盖 WebAuthn 请求的真实形态，同时挡住对抗性深嵌套） */
    const val MAX_DEPTH: Int = 32

    /** 解析入口：返回 Map / List / String / Double / Boolean / null */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("JSON 尾部存在多余内容")
        return value
    }

    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    /** 取字符串字段（非字符串一律视为缺失——**不做隐式强转**） */
    fun string(container: Map<String, Any?>?, key: String): String? =
        container?.get(key) as? String

    /** 取整数字段（Double → Int，仅当数值为有限且可精确表达时） */
    fun int(container: Map<String, Any?>?, key: String): Int? {
        val number = container?.get(key) as? Double ?: return null
        if (!number.isFinite() || number != Math.floor(number)) return null
        return number.toInt()
    }

    fun objectAt(container: Map<String, Any?>?, key: String): Map<String, Any?>? =
        asObject(container?.get(key))

    fun arrayAt(container: Map<String, Any?>?, key: String): List<Any?>? =
        asArray(container?.get(key))

    fun isNull(container: Map<String, Any?>?, key: String): Boolean =
        container != null && container.containsKey(key) && container[key] == null

    private class Parser(private val text: String) {

        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isJsonWhitespace()) index++
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("JSON 嵌套深度超过上限 $MAX_DEPTH")
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("JSON 意外结束")
            return when (val c = text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else {
                    throw IllegalArgumentException("非法 JSON 字符: '$c'")
                }
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue(depth + 1)
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("对象成员间缺少分隔符，实际 '$c'")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result += parseValue(depth + 1)
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("数组元素间缺少分隔符，实际 '$c'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("字符串未闭合")
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("转义序列未完成")
                        when (val escaped = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(parseUnicodeEscape())
                            else -> throw IllegalArgumentException("非法转义字符: '\\$escaped'")
                        }
                    }
                    else -> {
                        if (c.code < ASCII_SPACE) throw IllegalArgumentException("字符串包含未转义控制字符")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > text.length) throw IllegalArgumentException("\\u 转义长度不足")
            var code = 0
            repeat(4) {
                val digit = text[index++].digitToIntOrNull(16)
                    ?: throw IllegalArgumentException("\\u 转义含非十六进制字符")
                code = code * 16 + digit
            }
            return code.toChar()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) {
                throw IllegalArgumentException("非法字面量（期望 $literal）")
            }
            index += literal.length
            return value
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index] in '0'..'9') index++
            if (!atEnd() && text[index] == '.') {
                index++
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            val token = text.substring(start, index)
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字: $token")
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (atEnd() || text[index] != expected) {
                throw IllegalArgumentException("期望 '$expected'，实际 '${if (atEnd()) "EOF" else text[index]}'")
            }
            index++
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[index]
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
}
