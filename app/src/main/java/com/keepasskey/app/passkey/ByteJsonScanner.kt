package com.keepasskey.app.passkey

/**
 * 字节通道 JSON 定位扫描器（`ISSUE-P3-337` 口径 2 的支撑件，纯 Kotlin、零 Android 依赖）。
 *
 * ## 为什么不用现成的 `SimpleJson` / `org.json`（口径 2 要求 KDoc 写明理由）
 *
 * - `SimpleJson.parse(text: String)` 的入参是 `String`：CXF 载荷的 `key` 成员是 PKCS#8 私钥的
 *   Base64URL 文本，一旦进 `String` 就成为**不可擦除的堆残留**，直接违反 `AGENTS.md` §3
 *   敏感数据铁律（同族纪律见 `core/.../PasskeyKeyText.kt` 的「全部 API 只接受 / 返回 `ByteArray`」）；
 * - `org.json` 在宿主 JVM 单测里是**未实现桩**（同一理由见 [CallingOriginResolver] 的注释），
 *   而本条目的正确性主要由宿主用例担保，不可依赖平台桩；
 * - 故此处只做**最小**实现：一次线性扫描把 JSON 收成 [ByteJson] 节点树，**字符串值一律以
 *   `ByteArray`（UTF-8、转义已解码）承载**，只有键名（协议词表、非秘密）允许转 `String`。
 *
 * ## 防御性边界
 *
 * 扫码面接受**任意**二维码，解析器抛异常会直接击穿取景对话框 ⇒
 * 一切畸形输入一律**返回 `null`**（fail-closed），并在下列硬界处提前失败：
 * 嵌套深度 [MAX_DEPTH]（规范文档形态实际最深 7 层，见 [PasskeyCxfReader] 的定位路径）、
 * 转义表外的 `\x`、串内裸控制字符（< 0x20）、非 `null/true/false` 的裸词、截断输入。
 * 载荷**总字节**上限由调用方在进入本扫描器前判定
 * （[PasskeyCxfReader.MAX_IMPORT_PAYLOAD_BYTES]）。
 */
object ByteJsonScanner {

    /** 嵌套深度硬上限（防御性；真实形态远低于此值） */
    const val MAX_DEPTH: Int = 32

    /** 扫描并解析；输入不是单个合法 JSON 值时返回 null（绝不抛出）。 */
    fun scan(text: ByteArray): ByteJson? {
        val scanner = Scanner(text)
        val node = scanner.parseDocument() ?: return null
        if (!scanner.atEnd()) return null
        return node
    }

    /**
     * 递归下降扫描器（一次线性遍历）。
     *
     * 只在**内部**存在：节点类型 [ByteJson] 不含父引用，故调用方拿到的是纯值树。
     */
    private class Scanner(private val src: ByteArray) {

        private var pos = 0

        fun atEnd(): Boolean {
            skipWhitespace()
            return pos == src.size
        }

        fun parseDocument(): ByteJson? = value(0)

        private fun skipWhitespace() {
            while (pos < src.size) {
                val c = src[pos].toInt() and 0xFF
                if (c != SP && c != TAB && c != LF && c != CR) break
                pos++
            }
        }

        /** 深度递减、任何一处失败即整棵放弃（上层同样返回 null）。 */
        private fun value(depth: Int): ByteJson? {
            if (depth > MAX_DEPTH) return null
            skipWhitespace()
            if (pos >= src.size) return null
            val c = src[pos].toInt() and 0xFF
            return when {
                c == LCURLY -> objectNode(depth)
                c == LSQUARE -> arrayNode(depth)
                c == DQUOTE -> stringNode()
                c == MINUS || c in DIGIT_RANGE -> numberNode()
                else -> literalNode()
            }
        }

        private fun objectNode(depth: Int): ByteJson? {
            pos++ // '{'
            val members = ArrayList<Pair<String, ByteJson>>(8)
            skipWhitespace()
            if (pos < src.size && src[pos].toInt() == RCURLY) {
                pos++
                return ByteJson.Obj(members)
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.size || src[pos].toInt() != DQUOTE) return null
                val keyNode = stringNode() ?: return null
                val key = keyNode.asUtf8String() ?: return null
                skipWhitespace()
                if (pos >= src.size || src[pos].toInt() != COLON) return null
                pos++
                val child = value(depth + 1) ?: return null
                members += key to child
                skipWhitespace()
                if (pos >= src.size) return null
                when (src[pos].toInt()) {
                    COMMA -> pos++
                    RCURLY -> {
                        pos++
                        return ByteJson.Obj(members)
                    }
                    else -> return null
                }
            }
        }

        private fun arrayNode(depth: Int): ByteJson? {
            pos++ // '['
            val items = ArrayList<ByteJson>(4)
            skipWhitespace()
            if (pos < src.size && src[pos].toInt() == RSQUARE) {
                pos++
                return ByteJson.Arr(items)
            }
            while (true) {
                val item = value(depth + 1) ?: return null
                items += item
                skipWhitespace()
                if (pos >= src.size) return null
                when (src[pos].toInt()) {
                    COMMA -> pos++
                    RSQUARE -> {
                        pos++
                        return ByteJson.Arr(items)
                    }
                    else -> return null
                }
            }
        }

        /**
         * 字符串：转义解码后就地写入缓冲，输出 `ByteArray`（UTF-8）。
         * 非 ASCII 字节**原样透传**（输入本身即 UTF-8），`\uXXXX` 与代理对按 UTF-8 编码展开。
         */
        private fun stringNode(): ByteJson.Str? {
            pos++ // '"'
            val out = ByteSink()
            while (true) {
                if (pos >= src.size) return null
                val c = src[pos].toInt() and 0xFF
                pos++
                when {
                    c == DQUOTE -> return ByteJson.Str(out.toByteArray())
                    c == BACKSLASH -> if (!appendEscaped(out)) return null
                    c < 0x20 -> return null // 串内裸控制字符：RFC 8259 要求转义，一律拒
                    else -> out.add(c)
                }
            }
        }

        private fun appendEscaped(out: ByteSink): Boolean {
            if (pos >= src.size) return false
            return when (val c = src[pos].toInt() and 0xFF) {
                DQUOTE -> { out.add(DQUOTE); pos++; true }
                BACKSLASH -> { out.add(BACKSLASH); pos++; true }
                SLASH -> { out.add(SLASH); pos++; true }
                'b'.code -> { out.add(0x08); pos++; true }
                'f'.code -> { out.add(0x0C); pos++; true }
                'n'.code -> { out.add(LF); pos++; true }
                'r'.code -> { out.add(CR); pos++; true }
                't'.code -> { out.add(TAB); pos++; true }
                'u'.code -> { pos++; appendUnicodeEscape(out) }
                else -> false
            }
        }

        /** `\uXXXX`（已吃掉 'u'）：必要时与紧随的 `\uXXXX` 组成代理对，按 UTF-8 写出。 */
        private fun appendUnicodeEscape(out: ByteSink): Boolean {
            val first = readHex4() ?: return false
            if (first !in HIGH_SURROGATE_RANGE) {
                out.appendCodePoint(first)
                return true
            }
            val mark = pos
            if (pos + 1 < src.size && src[pos].toInt() == BACKSLASH && src[pos + 1].toInt() == 'u'.code) {
                pos += 2
                val second = readHex4()
                if (second != null && second in LOW_SURROGATE_RANGE) {
                    val combined = 0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
                    out.appendCodePoint(combined)
                    return true
                }
                pos = mark // 代理对不成立：把第二个 `\u` 留给后续正常解析
            }
            out.appendCodePoint(first)
            return true
        }

        private fun readHex4(): Int? {
            if (pos + 4 > src.size) return null
            var acc = 0
            repeat(4) {
                val digit = Character.digit(src[pos].toInt() and 0xFF, 16)
                if (digit < 0) return null
                acc = (acc shl 4) or digit
                pos++
            }
            return acc
        }

        private fun numberNode(): ByteJson.Num? {
            val start = pos
            if (pos < src.size && src[pos].toInt() == MINUS) pos++
            while (pos < src.size && src[pos].toInt() in DIGIT_RANGE) pos++
            if (pos < src.size && src[pos].toInt() == DOT) {
                pos++
                while (pos < src.size && src[pos].toInt() in DIGIT_RANGE) pos++
            }
            if (pos < src.size && (src[pos].toInt() == 'e'.code || src[pos].toInt() == 'E'.code)) {
                pos++
                if (pos < src.size && (src[pos].toInt() == PLUS || src[pos].toInt() == MINUS)) pos++
                while (pos < src.size && src[pos].toInt() in DIGIT_RANGE) pos++
            }
            if (pos == start) return null
            return ByteJson.Num(src.decodeToString(start, pos).toLongOrNull())
        }

        private fun literalNode(): ByteJson? {
            if (matchLiteral("true")) return ByteJson.Bool(true)
            if (matchLiteral("false")) return ByteJson.Bool(false)
            if (matchLiteral("null")) return ByteJson.NullValue
            return null // 裸词一律拒（JSON 只允许这三个字面量）
        }

        private fun matchLiteral(word: String): Boolean {
            if (pos + word.length > src.size) return false
            for (i in word.indices) {
                if (src[pos + i].toInt() != word[i].code) return false
            }
            pos += word.length
            return true
        }
    }

    // ---------------- 字节常量（避免正文里散落魔法数） ----------------

    private const val SP = 0x20
    private const val TAB = 0x09
    private const val LF = 0x0A
    private const val CR = 0x0D
    private const val DQUOTE = 0x22
    private const val BACKSLASH = 0x5C
    private const val SLASH = 0x2F
    private const val LCURLY = 0x7B
    private const val RCURLY = 0x7D
    private const val LSQUARE = 0x5B
    private const val RSQUARE = 0x5D
    private const val COLON = 0x3A
    private const val COMMA = 0x2C
    private const val DOT = 0x2E
    private const val MINUS = 0x2D
    private const val PLUS = 0x2B
    private val DIGIT_RANGE = '0'.code..'9'.code
    private val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
    private val LOW_SURROGATE_RANGE = 0xDC00..0xDFFF
}

/**
 * [ByteJsonScanner] 的节点树。**值一律是 `ByteArray`**（[ByteJson.Str]），
 * 这样私钥 / PRF 秘密从输入到解码结果全程不落 `String`；
 * 只有键名以 `String` 承载（协议词表，非秘密）。
 *
 * 同一对象内**重复键**取**首个**出现（[ByteJson.Obj.member]），并在 [PasskeyCxfReader] 的 KDoc
 * 里登记为口径——「后写覆盖」会让载荷尾部悄悄改写仪式字段，不是好的默认。
 */
sealed class ByteJson {

    /** JSON 对象：成员按输入顺序保留，重复键不合并。 */
    class Obj(val members: List<Pair<String, ByteJson>>) : ByteJson() {

        /** 首个同名成员的值；不存在返回 null。 */
        fun member(key: String): ByteJson? = members.firstOrNull { it.first == key }?.second

        /** 是否含某键（只判存在，不看值）。 */
        fun has(key: String): Boolean = members.any { it.first == key }

        /** 键集合（供诊断与「未知成员忽略」计数，不含值）。 */
        fun keys(): List<String> = members.map { it.first }
    }

    /** JSON 数组。 */
    class Arr(val items: List<ByteJson>) : ByteJson()

    /** JSON 字符串值：已解码转义的 UTF-8 字节（可能承载秘密，调用方负责清零）。 */
    class Str(val bytes: ByteArray) : ByteJson() {

        /** 空串判定（长度 0 与「只有空白」都算空，规范展示字段允许空串）。 */
        fun isBlankUtf8(): Boolean = bytes.isEmpty() || bytes.all { it.isBlankByte() }

        /** 非敏感值转文本（键名、rpId、用户名等）；私钥 / PRF **禁止**调用本方法。 */
        fun asUtf8String(): String? = runCatching { bytes.decodeToString() }.getOrNull()

        /**
         * 就地清零本节点的字节副本（`AGENTS.md` §3 铁律）。
         *
         * 只清零**扫描器自己分配的副本**，不动调用方传入的载荷数组——
         * 后者归扫码链上层按各自的擦除契约处理。
         */
        fun wipe() = bytes.fill(0)
    }

    /** JSON 数字：整数形态可得时 [longValue] 非空（版本号判定用），否则为 null（含小数 / 溢出）。 */
    class Num(val longValue: Long?) : ByteJson()

    /** JSON 布尔。 */
    class Bool(val value: Boolean) : ByteJson()

    /** JSON `null`（与「键缺失」在口径上不同，故独立成型）。 */
    object NullValue : ByteJson()
}

/** 仅服务于扫描器的可增长字节缓冲（避免每字节一次数组拷贝）。 */
private class ByteSink {

    private var buf = ByteArray(64)
    private var size = 0

    fun add(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    /** 按 UTF-8 写出一个码点（1~4 字节），供 `\uXXXX` 与代理对使用。 */
    fun appendCodePoint(cp: Int) {
        when {
            cp < 0x80 -> add(cp)
            cp < 0x800 -> {
                ensure(2)
                buf[size++] = (0xC0 or (cp shr 6)).toByte()
                buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            }
            cp < 0x10000 -> {
                ensure(3)
                buf[size++] = (0xE0 or (cp shr 12)).toByte()
                buf[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            }
            else -> {
                ensure(4)
                buf[size++] = (0xF0 or (cp shr 18)).toByte()
                buf[size++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                buf[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            }
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size * 2
        while (cap < size + extra) cap *= 2
        buf = buf.copyOf(cap)
    }
}

private fun Byte.isBlankByte(): Boolean = this == 0x20.toByte() || this == 0x09.toByte()
