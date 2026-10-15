package com.keepasskey.app.data.importer

/**
 * 极简只读 JSON 解析器（Bitwarden 与 1PUX 两个 JSON 系解析器共享；
 * 自 `PuxArchiveReader.kt` 按职责拆出——本文件新增后该读取器回落出行数闸门 tier2 以下；
 * 拆分**零行为变更**，只做结构搬运）。
 *
 * ### 为何手写而不引依赖
 * `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 中**均无** kotlinx-serialization / gson /
 * org.json 依赖声明；Android 自带 `org.json` 在 JVM 单测中是未实现 stub。为满足「不新增依赖 +
 * JVM 可测」，此处手写只读解析器；同时刻意不复用 passkey 包的 `MinimalJson`（跨包引用 internal
 * 辅助对象会破坏「包即边界」纪律），改以同包 internal 形式落地。
 *
 * ### 输入与职责边界
 * 本类只做**文本 → JSON 结构**的语法解析（空白含 CRLF、`\uXXXX` 与代理对转义）。
 * 字节 → 文本的解码（严格 UTF-8、BOM 剥离、拒绝 UTF-16/32 与 NUL 伪文本）由同批的
 * `ImportUtf8Decoder` 统一负责，两个解析器均先行解码再调用 [parse]。
 *
 * ### fail-closed
 * 任何语法非法一律抛 [ImportFormatException]，消息仅含字符偏移、绝不含输入内容；
 * 递归深度上限 64 防恶意深嵌套导致栈溢出（超限同样归一为上述异常）；
 * 另有**节点总数**与**单容器成员数**两道预算（`ISSUE-P2-618`，见 [Limits]），
 * 在节点 / 成员分配前累计拦截——达到预算即抛 [ImportLimitExceededException] 中止解析，
 * 已构建的子树随递归栈展开不可达、交由 GC 回收。
 */
internal object ImportJson {

    /**
     * 解析预算；默认值为生产上限，单测注入更小值即可低成本覆盖超限分支
     * （与 [PuxArchiveReader.Limits] 同构）。
     */
    internal data class Limits(
        val maxNodes: Int = ImportLimits.MAX_JSON_NODES,
        val maxContainerMembers: Int = ImportLimits.MAX_JSON_CONTAINER_MEMBERS
    )

    /** 解析整份文本；要求整体是一个合法 JSON 值（允许首尾空白）。 */
    fun parse(text: String, limits: Limits = Limits()): Any? = Parser(text, limits).parseDocument()

    fun asObject(value: Any?): Map<*, *>? = value as? Map<*, *>

    fun asArray(value: Any?): List<*>? = value as? List<*>

    fun asString(value: Any?): String? = value as? String

    /** JSON 数值 → Long（`1` 与 `1.0` 均归一为 1；非数值返回 null）。 */
    fun asLong(value: Any?): Long? = (value as? Long) ?: (value as? Double)?.toLong()

    fun asBoolean(value: Any?): Boolean? = value as? Boolean

    /** 递归下降解析器；[pos] 为当前字符偏移。语法常量就近收敛于本类，不跨作用域取私有声明。 */
    private class Parser(private val text: String, private val limits: Limits) {
        private var pos = 0
        private var nodes = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (pos < text.length) fail()
            return value
        }

        /** 节点预算闸门：每个新节点（对象 / 数组 / 字符串 / 数值 / 字面量，含对象键）分配前调用。 */
        private fun accountNode() {
            nodes++
            if (nodes > limits.maxNodes) {
                throw ImportLimitExceededException("JSON 节点数超出上限（${limits.maxNodes}，偏移 $pos）")
            }
        }

        /** 容器成员预算闸门：追加第（上限+1）个成员前调用。 */
        private fun requireMemberCapacity(currentSize: Int) {
            if (currentSize >= limits.maxContainerMembers) {
                throw ImportLimitExceededException(
                    "JSON 容器成员数超出上限（${limits.maxContainerMembers}，偏移 $pos）"
                )
            }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isJsonWhitespace()) pos++
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH || pos >= text.length) fail()
            return when (val current = text[pos]) {
                OBJECT_OPEN -> readObject(depth)
                ARRAY_OPEN -> readArray(depth)
                STRING_QUOTE -> readString()
                't' -> readLiteral(LITERAL_TRUE, true)
                'f' -> readLiteral(LITERAL_FALSE, false)
                'n' -> readLiteral(LITERAL_NULL, null)
                else -> if (current == SIGN_MINUS || current.isDigit()) readNumber() else fail()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            accountNode()
            pos++
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (consume(OBJECT_CLOSE)) return members
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != STRING_QUOTE) fail()
                requireMemberCapacity(members.size)
                val key = readString()
                skipWhitespace()
                if (!consume(NAME_SEPARATOR)) fail()
                skipWhitespace()
                members[key] = readValue(depth + 1)
                skipWhitespace()
                if (consume(OBJECT_CLOSE)) return members
                if (!consume(VALUE_SEPARATOR)) fail()
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            accountNode()
            pos++
            val elements = ArrayList<Any?>()
            skipWhitespace()
            if (consume(ARRAY_CLOSE)) return elements
            while (true) {
                skipWhitespace()
                requireMemberCapacity(elements.size)
                elements += readValue(depth + 1)
                skipWhitespace()
                if (consume(ARRAY_CLOSE)) return elements
                if (!consume(VALUE_SEPARATOR)) fail()
            }
        }

        private fun readString(): String {
            accountNode()
            pos++
            val builder = StringBuilder()
            while (true) {
                if (pos >= text.length) fail()
                when (val current = text[pos]) {
                    STRING_QUOTE -> {
                        pos++
                        return builder.toString()
                    }
                    ESCAPE -> {
                        pos++
                        builder.append(readEscape())
                    }
                    else -> {
                        // 规范禁止字符串内出现裸控制字符：严格拒绝，避免后续解析歧义
                        if (current < MIN_PRINTABLE_CHAR) fail()
                        builder.append(current)
                        pos++
                    }
                }
            }
        }

        /** 消费一个转义序列（调用时 `pos` 指向反斜杠之后的字符）。 */
        private fun readEscape(): Char {
            if (pos >= text.length) fail()
            val escaped = text[pos]
            pos++
            return when (escaped) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                ESCAPE_UNICODE -> readHexChar()
                else -> fail()
            }
        }

        /** `\uXXXX`：代理对由两次转义各产出一个 Char，拼接后即为完整码点。 */
        private fun readHexChar(): Char {
            if (pos + HEX_DIGITS > text.length) fail()
            var code = 0
            repeat(HEX_DIGITS) {
                val digit = Character.digit(text[pos], 16)
                if (digit < 0) fail()
                code = code * 16 + digit
                pos++
            }
            return code.toChar()
        }

        private fun readNumber(): Any {
            accountNode()
            val start = pos
            if (pos < text.length && text[pos] == SIGN_MINUS) pos++
            readDigits()
            var fractional = false
            if (consume(FRACTION_SEPARATOR)) {
                fractional = true
                readDigits()
            }
            if (consume(EXPONENT_LOWER) || consume(EXPONENT_UPPER)) {
                fractional = true
                if (!consume(SIGN_PLUS)) consume(SIGN_MINUS)
                readDigits()
            }
            val token = text.substring(start, pos)
            return if (fractional) token.toDoubleOrNull() ?: fail() else token.toLongOrNull() ?: fail()
        }

        /** 至少消费一位数字（整数 / 小数 / 指数三处共用）。 */
        private fun readDigits() {
            val start = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos == start) fail()
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            accountNode()
            if (!text.startsWith(word, pos)) fail()
            pos += word.length
            return value
        }

        private fun consume(expected: Char): Boolean {
            if (pos < text.length && text[pos] == expected) {
                pos++
                return true
            }
            return false
        }

        /** 解析失败：消息只带偏移，绝不携带输入片段（防明文经异常消息外泄）。 */
        private fun fail(): Nothing = throw ImportFormatException("JSON 语法非法（偏移 $pos）")

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r'

        private companion object {
            const val OBJECT_OPEN = '{'
            const val OBJECT_CLOSE = '}'
            const val ARRAY_OPEN = '['
            const val ARRAY_CLOSE = ']'
            const val STRING_QUOTE = '"'
            const val ESCAPE = '\\'
            const val NAME_SEPARATOR = ':'
            const val VALUE_SEPARATOR = ','
            const val FRACTION_SEPARATOR = '.'
            const val EXPONENT_LOWER = 'e'
            const val EXPONENT_UPPER = 'E'
            const val SIGN_PLUS = '+'
            const val SIGN_MINUS = '-'
            const val ESCAPE_UNICODE = 'u'
            const val MIN_PRINTABLE_CHAR = ' '

            const val LITERAL_TRUE = "true"
            const val LITERAL_FALSE = "false"
            const val LITERAL_NULL = "null"

            /** 单个 `\uXXXX` 转义的十六进制位数。 */
            const val HEX_DIGITS = 4

            /** 嵌套深度上限：远超此深度必为异常输入，直接拒绝而非冒险递归。 */
            const val MAX_DEPTH = 64
        }
    }
}
