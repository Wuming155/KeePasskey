package com.keepasskey.app.passkey

/**
 * [ByteJsonScanner] 的节点树（`ISSUE-P3-647` 为保持扫描器在行数分档内而按职责拆出）。
 *
 * **值一律是 `ByteArray`**（[Str]），这样私钥 / PRF 秘密从输入到解码结果全程不落 `String`；
 * 只有键名以 `String` 承载（协议词表，非秘密）。
 *
 * 同一对象内**重复键**取**首个**出现（[Obj.member]），并在 [PasskeyCxfReader] 的 KDoc
 * 里登记为口径——「后写覆盖」会让载荷尾部悄悄改写仪式字段，不是好的默认。
 *
 * 擦除：整棵树由 [wipe] 递归清零（[Str] 只清扫描器自己分配的副本，不动调用方载荷数组）；
 * 解析器在 `read` 的 `finally` 里对整棵树调用一次，覆盖未选中的多凭据、未知成员与失败分支。
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

        override fun wipe() = members.forEach { it.second.wipe() }
    }

    /** JSON 数组。 */
    class Arr(val items: List<ByteJson>) : ByteJson() {
        override fun wipe() = items.forEach { it.wipe() }
    }

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
        override fun wipe() = bytes.fill(0)
    }

    /** JSON 数字：整数形态可得时 [Num.longValue] 非空（版本号判定用），否则为 null（含小数 / 溢出）。 */
    class Num(val longValue: Long?) : ByteJson()

    /** JSON 布尔。 */
    class Bool(val value: Boolean) : ByteJson()

    /** JSON `null`（与「键缺失」在口径上不同，故独立成型）。 */
    object NullValue : ByteJson()

    open fun wipe() = Unit
}

private fun Byte.isBlankByte(): Boolean = this == 0x20.toByte() || this == 0x09.toByte()
