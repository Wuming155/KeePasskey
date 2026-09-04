package com.keepasskey.core.security

import java.io.Closeable
import java.util.Arrays

/**
 * 可清零的安全字节数组封装。
 * 遵循敏感数据铁律：内存敏感序列在使用完成后立即调用 [clear] 或 [use] 显式清零，杜绝常驻堆内存。
 */
class ClearableByteArray(
    byteArray: ByteArray
) : Closeable {

    private val data: ByteArray = byteArray.clone()
    private var isCleared = false

    val size: Int
        get() {
            checkNotCleared()
            return data.size
        }

    fun toByteArray(): ByteArray {
        checkNotCleared()
        return data.clone()
    }

    fun access(block: (ByteArray) -> Unit) {
        checkNotCleared()
        block(data)
    }

    fun <R> useBytes(block: (ByteArray) -> R): R {
        checkNotCleared()
        return block(data)
    }

    /**
     * 显式清零敏感内存
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
        check(!isCleared) { "ClearableByteArray 已经清零，禁止继续访问" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClearableByteArray) return false
        if (isCleared || other.isCleared) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        if (isCleared) return 0
        return data.contentHashCode()
    }

    override fun toString(): String {
        return if (isCleared) "ClearableByteArray(cleared)" else "ClearableByteArray(size=${data.size})"
    }
}
