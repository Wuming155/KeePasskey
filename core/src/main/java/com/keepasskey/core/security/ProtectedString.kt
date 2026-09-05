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
 * **密文形态驻留**（经 [InMemoryCipher] 确定性加密，IV 随实例存储），内存 dump 与字符串扫描
 * 无法直接读出明文；仅 [readChars]/[readUtf8]/[readString] 读取的瞬间解密出临时副本，
 * 副本用毕立即擦除。加密是确定性的——相同明文恒得相同密文，因此 equals/hashCode 直接
 * 比较密文即可（同步变更检测与三方合并的比较路径不解密、不物化明文）。
 */
class ProtectedString(
    val isProtected: Boolean = true,
    bytes: ByteArray
) : Closeable {

    /** 驻留态：isProtected 时为密文，否则为明文副本 */
    private val data: ByteArray

    /** 驻留解密 IV（与密文一并驻留；非保护或空值时为 null） */
    private val memoryIv: ByteArray?

    private var isCleared = false

    init {
        if (isProtected && bytes.isNotEmpty()) {
            val sealed = InMemoryCipher.seal(bytes)
            memoryIv = sealed.iv
            data = sealed.data
        } else {
            memoryIv = null
            data = bytes.clone()
        }
    }

    constructor(text: String, isProtected: Boolean = true) : this(
        isProtected = isProtected,
        bytes = text.toByteArray(StandardCharsets.UTF_8)
    )

    constructor(chars: CharArray, isProtected: Boolean = true) : this(
        isProtected = isProtected,
        bytes = charsToUtf8(chars)
    )

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
     * 显式擦除敏感内存（密文与 IV 一并清零）
     */
    fun clear() {
        if (!isCleared) {
            Arrays.fill(data, 0.toByte())
            memoryIv?.fill(0)
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
        // 确定性加密：相同明文恒得相同（IV, 密文），密文比较即明文比较，无需解密
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        if (isCleared) return 0
        var result = isProtected.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        return if (isProtected) {
            "ProtectedString(protected=true, len=${if (isCleared) 0 else data.size})"
        } else {
            readString()
        }
    }

    companion object {
        val EMPTY = ProtectedString(isProtected = false, bytes = ByteArray(0))

        private fun charsToUtf8(chars: CharArray): ByteArray {
            val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            return bytes
        }
    }
}
