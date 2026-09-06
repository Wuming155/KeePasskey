package com.keepasskey.core.security

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * 保护字符串模型。
 * 用于保存主密码、字段密码等敏感信息。
 * 内部优先采用字节数组承载，支持显式清零，杜绝常驻 GC 堆字符串池。
 *
 * P3 整改（对齐 KeePassDX `protectInMemory`）：`isProtected = true` 的实例在堆内存中以
 * **密文形态驻留**（经 [InMemoryCipher] 随机化加密，IV 随实例存储），内存 dump 与字符串扫描
 * 无法直接读出明文；仅 [readChars]/[readUtf8]/[readString] 读取的瞬间解密出临时副本，
 * 副本用毕立即擦除。
 *
 * 2026-09 加解密审查整改：等值语义与加密解耦——加密每次使用随机 IV（同明文两次密封的
 * 密文不同，堆中不存在可跨实例关联的确定性密文）；equals/hashCode 改为比较
 * **HMAC 等值标签**（常时时间比较），同步变更检测与三方合并的比较路径依然不解密、
 * 不物化明文。
 */
class ProtectedString(
    val isProtected: Boolean = true,
    bytes: ByteArray
) : Closeable {

    /** 驻留态：isProtected 时为密文，否则为明文副本 */
    private val data: ByteArray

    /** 驻留解密 IV（与密文一并驻留；非保护或空值时为 null） */
    private val memoryIv: ByteArray?

    /** 等值标签：HMAC-SHA256(eqKey, 明文)（非保护或空值时为 null，equals/hashCode 使用） */
    private val memoryTag: ByteArray?

    private var isCleared = false

    init {
        if (isProtected && bytes.isNotEmpty()) {
            val sealed = InMemoryCipher.seal(bytes)
            memoryIv = sealed.iv
            memoryTag = sealed.tag
            data = sealed.data
        } else {
            memoryIv = null
            memoryTag = null
            data = bytes.clone()
        }
    }

    constructor(text: String, isProtected: Boolean = true) : this(
        isProtected = isProtected,
        bytes = text.toByteArray(StandardCharsets.UTF_8),
        owned = true
    )

    constructor(chars: CharArray, isProtected: Boolean = true) : this(
        isProtected = isProtected,
        bytes = charsToUtf8(chars),
        owned = true
    )

    /**
     * P0-7 整改：自有明文中间量构造通道。
     * String / CharArray 便捷构造在内部生成的 UTF-8 明文副本归本构造通道所有——
     * 经主构造完成密文驻留（或明文克隆）后在此立即清零，杜绝明文副本滞留堆内等待 GC。
     * 主构造的 bytes 入参保持既有借用语义（归调用方所有，不由本类清零）。
     */
    private constructor(isProtected: Boolean, bytes: ByteArray, owned: Boolean) : this(
        isProtected = isProtected,
        bytes = bytes
    ) {
        check(owned) { "仅自有中间量允许走此构造通道" }
        Arrays.fill(bytes, 0.toByte())
    }

    val length: Int
        get() {
            checkNotCleared()
            // CTR 无填充：密文长度 == 明文长度
            return data.size
        }

    val isEmpty: Boolean
        get() = length == 0

    /**
     * 读取字符数组副本，调用方需在使用完毕后显式清零该 CharArray
     */
    fun readChars(): CharArray {
        checkNotCleared()
        val plain = plainBytes()
        return try {
            val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain))
            val chars = CharArray(cb.remaining())
            cb.get(chars)
            // P0-7 整改：解码器内部 CharBuffer 同样承载过明文，一并清零，
            // 明文副本仅存活于返回给调用方的 CharArray
            if (cb.hasArray()) Arrays.fill(cb.array(), '0')
            chars
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    /**
     * 读取 UTF-8 字节数组副本，调用方需在使用完毕后显式清零该 ByteArray
     */
    fun readUtf8(): ByteArray {
        checkNotCleared()
        return plainBytes()
    }

    /**
     * 将保护值转为 String。注意：一旦调用，明文字符串将驻留 JVM 堆内存，请仅在必要交互边界使用。
     */
    fun readString(): String {
        checkNotCleared()
        val plain = plainBytes()
        return try {
            String(plain, StandardCharsets.UTF_8)
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    /**
     * 安全闭包使用 CharArray，并在退出时自动清零
     */
    inline fun <R> useChars(block: (CharArray) -> R): R {
        val chars = readChars()
        try {
            return block(chars)
        } finally {
            Arrays.fill(chars, '0')
        }
    }

    /**
     * 安全闭包使用 UTF-8 ByteArray，并在退出时自动清零
     */
    inline fun <R> useUtf8(block: (ByteArray) -> R): R {
        val bytes = readUtf8()
        try {
            return block(bytes)
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    /**
     * 显式擦除敏感内存（密文、IV 与等值标签一并清零）
     */
    fun clear() {
        if (!isCleared) {
            Arrays.fill(data, 0.toByte())
            memoryIv?.fill(0)
            memoryTag?.fill(0)
            isCleared = true
        }
    }

    override fun close() {
        clear()
    }

    private fun checkNotCleared() {
        check(!isCleared) { "ProtectedString 已经清零，禁止继续访问" }
    }

    /** 解密出明文新副本（非保护实例直接克隆驻留值）；调用方用毕负责清零 */
    private fun plainBytes(): ByteArray {
        return if (memoryIv != null) {
            InMemoryCipher.unseal(memoryIv, data)
        } else {
            data.clone()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtectedString) return false
        if (isCleared || other.isCleared) return false
        if (isProtected != other.isProtected) return false
        // 等值标签（HMAC）常时时间比较：相同明文恒得相同标签，不解密、不物化明文；
        // 空值/非保护实例走明文副本路径（数据为空或非敏感，直接内容比较）
        val a = memoryTag
        val b = other.memoryTag
        return if (a != null && b != null) {
            InMemoryCipher.tagsEqual(a, b)
        } else {
            data.contentEquals(other.data)
        }
    }

    override fun hashCode(): Int {
        if (isCleared) return 0
        var result = isProtected.hashCode()
        // 等值标签为 HMAC 伪随机输出，直接作为哈希输入安全且分布均匀
        result = 31 * result + (memoryTag ?: data).contentHashCode()
        return result
    }

    override fun toString(): String {
        // P2-4 整改：toString 绝不返回明文——无论 isProtected 与否仅返回类型与长度描述。
        // 防止日志、字符串模板、数据类 toString 等隐式转换物化敏感内容（含
        // MemoryProtection 允许 ProtectPassword=False 的非保护字段）；
        // 明文一律经显式 readChars()/readUtf8()/readString() 按需读取
        return "ProtectedString(protected=$isProtected, len=${if (isCleared) 0 else data.size})"
    }

    companion object {
        val EMPTY = ProtectedString(isProtected = false, bytes = ByteArray(0))

        private fun charsToUtf8(chars: CharArray): ByteArray {
            val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            // P0-7 整改：编码器内部 ByteBuffer 同样承载过明文，一并清零
            if (bb.hasArray()) Arrays.fill(bb.array(), 0.toByte())
            return bytes
        }
    }
}
