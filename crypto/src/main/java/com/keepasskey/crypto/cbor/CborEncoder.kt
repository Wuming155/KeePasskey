package com.keepasskey.crypto.cbor

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 确定性 CBOR (Concise Binary Object Representation, RFC 8949) 编码器。
 * 针对 WebAuthn / FIDO2 / COSE 签名与凭据交换场景进行了最小化与高保真实现：
 * 1. 严格支持无符号整数、负整数（Long 范围）、byte string、text string、array 与 map；
 * 2. 整数与长度均采用 RFC 8949 规定的最短字节表示形式（Core Deterministic Encoding）；
 * 3. 映射表 (Map) 强制 RFC 8949 §4.2.1 Canonical 键序：按键自身的 CBOR 编码字节流
 *    以字典序升序排列后写出（TASK-27 整改，此前按插入序编码——与外部 WebAuthn
 *    实现互验签名时键序不一致将直接失败）；出现重复键时 fail-fast 拒绝编码；
 * 4. 零第三方依赖、不可变链式调用与即时字节导出。
 */
class CborEncoder private constructor(
    private val buffer: ByteArrayOutputStream
) {

    constructor() : this(ByteArrayOutputStream())

    /**
     * 写入有符号整数（根据正负自动匹配无符号或负数 Major Type）
     * - 当 value >= 0 时，使用 Major Type 0 (无符号整数)；
     * - 当 value < 0 时，使用 Major Type 1 (负整数，编码值为 -1 - value)。
     */
    fun writeInt(value: Long): CborEncoder {
        if (value >= 0) {
            writeTypeAndArgument(CborConstants.MAJOR_UNSIGNED_INT, value)
        } else {
            // RFC 8949: 负整数编码值为 -(value + 1)
            // 当 value = Long.MIN_VALUE (-9223372036854775808L) 时，
            // value + 1 = -9223372036854775807L，相反数为 9223372036854775807L (Long.MAX_VALUE)，安全不溢出
            val argument = -(value + 1)
            writeTypeAndArgument(CborConstants.MAJOR_NEGATIVE_INT, argument)
        }
        return this
    }

    /**
     * 写入定长字节数组 (Major Type 2, Byte String)
     */
    fun writeByteString(bytes: ByteArray): CborEncoder {
        writeTypeAndArgument(CborConstants.MAJOR_BYTE_STRING, bytes.size.toLong())
        buffer.write(bytes, 0, bytes.size)
        return this
    }

    /**
     * 写入 UTF-8 文本字符串 (Major Type 3, Text String)
     */
    fun writeTextString(text: String): CborEncoder {
        val utf8Bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeTypeAndArgument(CborConstants.MAJOR_TEXT_STRING, utf8Bytes.size.toLong())
        buffer.write(utf8Bytes, 0, utf8Bytes.size)
        return this
    }

    /**
     * 写入数组头部 (Major Type 4, Array Header)
     * 调用方随后应依次写入对应数量的子项
     */
    fun writeArrayHeader(size: Int): CborEncoder {
        require(size >= 0) { "数组元素个数不能为负数: $size" }
        writeTypeAndArgument(CborConstants.MAJOR_ARRAY, size.toLong())
        return this
    }

    /**
     * 写入映射表头部 (Major Type 5, Map Header)
     * 调用方随后应依次写入对应数量的键值对 (Key, Value)
     */
    fun writeMapHeader(size: Int): CborEncoder {
        require(size >= 0) { "Map 键值对数量不能为负数: $size" }
        writeTypeAndArgument(CborConstants.MAJOR_MAP, size.toLong())
        return this
    }

    /**
     * 写入布尔值 (Major Type 7, Simple Value)
     */
    fun writeBoolean(value: Boolean): CborEncoder {
        val simple = if (value) CborConstants.SIMPLE_TRUE else CborConstants.SIMPLE_FALSE
        buffer.write((CborConstants.MAJOR_SIMPLE shl 5) or simple)
        return this
    }

    /**
     * 写入 Null (Major Type 7, Simple Value)
     */
    fun writeNull(): CborEncoder {
        buffer.write((CborConstants.MAJOR_SIMPLE shl 5) or CborConstants.SIMPLE_NULL)
        return this
    }

    /**
     * 写入裸字节序列（用于直接内联已编码的子 CBOR 片段）
     */
    fun writeRaw(bytes: ByteArray): CborEncoder {
        buffer.write(bytes, 0, bytes.size)
        return this
    }

    /**
     * 将对象递归自动分发并写入编码流
     */
    fun writeItem(item: Any?): CborEncoder {
        when (item) {
            null -> writeNull()
            is Boolean -> writeBoolean(item)
            is Byte -> writeInt(item.toLong())
            is Short -> writeInt(item.toLong())
            is Int -> writeInt(item.toLong())
            is Long -> writeInt(item)
            is ByteArray -> writeByteString(item)
            is String -> writeTextString(item)
            is List<*> -> {
                writeArrayHeader(item.size)
                for (elem in item) {
                    writeItem(elem)
                }
            }
            is Array<*> -> {
                writeArrayHeader(item.size)
                for (elem in item) {
                    writeItem(elem)
                }
            }
            is Map<*, *> -> {
                writeCanonicalMap(item)
            }
            else -> throw IllegalArgumentException("不支持的 CBOR 编码数据类型: ${item.javaClass.name}")
        }
        return this
    }

    /**
     * RFC 8949 §4.2.1 Canonical 键序写 Map。
     *
     * 排序依据是**键自身的 CBOR 编码字节流**的字典序（而非键的逻辑值比较）：
     * 确定性编码的核心不变量为「编码后的字节流唯一」，按编码字节排序可直接保证
     * 键序与最终字节流顺序一致；同 major type 下短编码键（首字节更小）天然排在前面，
     * 与 RFC 附录示例（如 {10: -1, "n": ...} 中整数键先于文本键）一致。
     * 重复键（编码字节流相同）在确定性编码中属非法输入，fail-fast 抛出。
     */
    private fun writeCanonicalMap(item: Map<*, *>) {
        val sortedEntries = item.entries
            .map { (key, value) ->
                val keyBytes = CborEncoder().writeItem(key).toByteArray()
                Triple(key, value, keyBytes)
            }
            .sortedWith { a, b -> compareEncodedBytes(a.third, b.third) }

        // 重复键检测：确定性编码要求键唯一，相邻编码相同即为重复
        for (i in 1 until sortedEntries.size) {
            if (compareEncodedBytes(sortedEntries[i - 1].third, sortedEntries[i].third) == 0) {
                throw IllegalArgumentException("CBOR 确定性编码检测到重复的 Map 键: ${sortedEntries[i].first}")
            }
        }

        writeMapHeader(item.size)
        for ((key, value, _) in sortedEntries) {
            writeItem(key)
            writeItem(value)
        }
    }

    /** 编码字节流字典序比较（避免依赖 java.util.Arrays.compare 的平台可用性） */
    private fun compareEncodedBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }

    /**
     * 导出当前已写入的全部 CBOR 规范字节流
     */
    fun toByteArray(): ByteArray {
        return buffer.toByteArray()
    }

    /**
     * 按照 RFC 8949 确定性规则写入首字节与紧随的长度/参数字段（采用最短表示法）
     */
    private fun writeTypeAndArgument(majorType: Int, argument: Long) {
        require(argument >= 0) { "Argument 参数必须为非负整数: $argument" }
        val majorByte = majorType shl 5

        when {
            argument <= CborConstants.AI_DIRECT_MAX -> {
                buffer.write(majorByte or argument.toInt())
            }
            argument <= CborConstants.MASK_ONE_BYTE -> {
                buffer.write(majorByte or CborConstants.AI_ONE_BYTE)
                buffer.write(argument.toInt() and 0xFF)
            }
            argument <= CborConstants.MASK_TWO_BYTES -> {
                buffer.write(majorByte or CborConstants.AI_TWO_BYTES)
                buffer.write((argument ushr 8).toInt() and 0xFF)
                buffer.write(argument.toInt() and 0xFF)
            }
            argument <= CborConstants.MASK_FOUR_BYTES -> {
                buffer.write(majorByte or CborConstants.AI_FOUR_BYTES)
                buffer.write((argument ushr 24).toInt() and 0xFF)
                buffer.write((argument ushr 16).toInt() and 0xFF)
                buffer.write((argument ushr 8).toInt() and 0xFF)
                buffer.write(argument.toInt() and 0xFF)
            }
            else -> {
                buffer.write(majorByte or CborConstants.AI_EIGHT_BYTES)
                for (shift in 56 downTo 0 step 8) {
                    buffer.write((argument ushr shift).toInt() and 0xFF)
                }
            }
        }
    }

    companion object {
        /**
         * 创建全新的编码器实例
         */
        fun create(): CborEncoder = CborEncoder()

        /**
         * 快速编码单个对象为 CBOR 字节流
         */
        fun encode(item: Any?): ByteArray {
            return CborEncoder().writeItem(item).toByteArray()
        }

        /**
         * 快速编码 Map（强制 RFC 8949 Canonical 键序，见 [writeCanonicalMap]）为 CBOR 字节流
         */
        fun encodeMap(map: Map<*, *>): ByteArray {
            return CborEncoder().writeItem(map).toByteArray()
        }
    }
}
