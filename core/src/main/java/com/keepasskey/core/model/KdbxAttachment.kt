package com.keepasskey.core.model

import com.keepasskey.core.security.ClearableByteArray
import java.io.Closeable

/**
 * KDBX 条目附件
 */
class KdbxAttachment(
    val name: String,
    val data: ByteArray,
    val refIndex: Int? = null
) : Closeable {

    fun clear() {
        data.fill(0)
    }

    override fun close() {
        clear()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdbxAttachment) return false
        if (name != other.name) return false
        if (refIndex != other.refIndex) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (refIndex ?: 0)
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "KdbxAttachment(name='$name', size=${data.size}, ref=$refIndex)"
    }
}
