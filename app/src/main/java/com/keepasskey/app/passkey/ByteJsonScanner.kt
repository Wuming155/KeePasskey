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
 *
 * ## 工作缓冲的擦除（`ISSUE-P3-647`）
 *
 * 每个字符串值经 [ByteSink] 逐步解码；[ByteSink] **扩容时先清零被遗弃的旧代次**再做复制，
 * `stringNode` 的 `finally` 清当前代次，交给 AST 的独立副本则随树（[ByteJson.wipe]）由
 * `PasskeyCxfReader.read` 的 `finally` 递归清零。`scan` 自身成功 / 语法失败两条路径都收口，
 * 不把任何明文字节留给 GC。
 */
object ByteJsonScanner {

    /** 嵌套深度硬上限（防御性；真实形态远低于此值） */
    const val MAX_DEPTH: Int = 32

    /** 扫描并解析；输入不是单个合法 JSON 值时返回 null（绝不抛出）。 */
    fun scan(text: ByteArray): ByteJson? = scan(text, null)

    /**
     * 同上，并把扫描器的**每一个**字符串工作数组（`ByteSink` 的初始缓冲、每次扩容后的新缓冲）
     * 与交给 AST 的字符串副本在分配时交给 [probe] 保留引用。
     *
     * 只供同模块契约测试直接观测清零（`ISSUE-P3-647`）：不引入全局钩子，观察者由调用方显式传入、
     * 生命周期止于单次扫描。观察者**只许保存引用**，不得回写或清零这些数组。
     */
    internal fun scan(text: ByteArray, probe: ByteJsonScanProbe?): ByteJson? {
        val scanner = Scanner(text, probe)
        val node = scanner.parseDocument() ?: run {
            scanner.wipeTemporaryNodes()
            return null
        }
        if (!scanner.atEnd()) {
            scanner.wipeTemporaryNodes()
            return null
        }
        scanner.releaseTemporaryNodeReferences()
        return node
    }

    /**
     * 递归下降扫描器（一次线性遍历）。
     *
     * 只在**内部**存在：节点类型 [ByteJson] 不含父引用，故调用方拿到的是纯值树。
     *
     * [probe] 只用于契约测试直接保留字符串工作数组的引用（见 [scan]）：
     * 生产路径传 null，不产生额外引用。
     */
    private class Scanner(
        private val src: ByteArray,
        private val probe: ByteJsonScanProbe?
    ) {

        private var pos = 0
        private val allocatedNodes = mutableListOf<ByteJson>()

        fun wipeTemporaryNodes() {
            allocatedNodes.forEach { it.wipe() }
            allocatedNodes.clear()
        }

        fun releaseTemporaryNodeReferences() = allocatedNodes.clear()

        private fun <T : ByteJson> track(node: T): T = node.also { allocatedNodes += it }

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
                return track(ByteJson.Obj(members))
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.size || src[pos].toInt() != DQUOTE) return null
                val keyNode = stringNode() ?: return null
                val key = try { keyNode.asUtf8String() ?: return null } finally { keyNode.wipe() }
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
                        return track(ByteJson.Obj(members))
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
                return track(ByteJson.Arr(items))
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
                        return track(ByteJson.Arr(items))
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
            val out = ByteSink(probe)
            try {
                while (true) {
                    if (pos >= src.size) return null
                    val c = src[pos].toInt() and 0xFF
                    pos++
                    when {
                        c == DQUOTE -> {
                            val bytes = out.toByteArray()
                            probe?.stringCopies?.add(bytes)
                            return track(ByteJson.Str(bytes))
                        }
                        c == BACKSLASH -> if (!appendEscaped(out)) return null
                        c < 0x20 -> return null // 串内裸控制字符：RFC 8259 要求转义，一律拒
                        else -> out.add(c)
                    }
                }
            } finally {
                out.wipe()
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
