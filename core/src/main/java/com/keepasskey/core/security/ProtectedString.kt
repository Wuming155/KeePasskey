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
 */
class ProtectedString(
    val isProtected: Boolean = true,
    bytes: ByteArray
) : Closeable {

    private val data: ByteArray = bytes.clone()
    private var isCleared = false

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
            return data.size
        }

    val isEmpty: Boolean
        get() = length == 0

    /**
     * 读取字符数组副本，调用方需在使用完毕后显式清零该 CharArray
     */
    fun readChars(): CharArray {
        checkNotCleared()
        val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data))
        val chars = CharArray(cb.remaining())
        cb.get(chars)
        return chars
    }

    /**
     * 读取 UTF-8 字节数组副本，调用方需在使用完毕后显式清零该 ByteArray
     */
    fun readUtf8(): ByteArray {
        checkNotCleared()
        return data.clone()
    }

    /**
     * 将保护值转为 String。注意：一旦调用，明文字符串将驻留 JVM 堆内存，请仅在必要交互边界使用。
     */
    fun readString(): String {
        checkNotCleared()
        return String(data, StandardCharsets.UTF_8)
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
     * 显式擦除敏感内存
     */
    fun clear() {
        if (!isCleared) {
            Arrays.fill(data, 0.toByte())
            isCleared = true
        }
    }

    override fun close() {
        clear()
    }

    private fun checkNotCleared() {
        check(!isCleared) { "ProtectedString 已经清零，禁止继续访问" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtectedString) return false
        if (isCleared || other.isCleared) return false
        if (isProtected != other.isProtected) return false
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
